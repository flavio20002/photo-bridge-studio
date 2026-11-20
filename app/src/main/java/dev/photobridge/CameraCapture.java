package dev.photobridge;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Scatta UNA foto con Camera2 e restituisce il JPEG.
 *
 * La fotocamera viene aperta solo per la durata dello scatto:
 *   apri -> anteprima "invisibile" (ImageReader, nessuna Surface a schermo)
 *        -> autofocus (trigger AF e attesa del lock) -> attesa convergenza esposizione
 *        -> scatto JPEG -> chiudi tutto.
 */
final class CameraCapture {

    private static final long OPEN_TIMEOUT_MS = 8000;
    private static final long TOTAL_TIMEOUT_MS = 20000;
    private static final long FOCUS_TIMEOUT_MS = 4000;
    private static final long AE_TIMEOUT_MS = 2500;
    private static final int WARMUP_FRAMES = 6;
    private static final long MAX_PIXELS = 12600000L; // ~12 MP: buon compromesso qualità/peso
    private static final int JPEG_QUALITY = 95;

    private final Context ctx;

    CameraCapture(Context context) {
        this.ctx = context.getApplicationContext();
    }

    /** Un solo scatto alla volta. */
    synchronized byte[] capture(int rotation) throws Exception {
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw new IOException("permesso fotocamera non concesso");
        }
        CameraManager mgr = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        String id = pickBackCamera(mgr);
        if (id == null) throw new IOException("nessuna fotocamera posteriore trovata");

        Shot shot = new Shot(mgr, id, rotation);
        try {
            return shot.run();
        } finally {
            shot.close();
        }
    }

    private static String pickBackCamera(CameraManager mgr) throws CameraAccessException {
        for (String id : mgr.getCameraIdList()) {
            CameraCharacteristics cc = mgr.getCameraCharacteristics(id);
            Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing.intValue() != CameraCharacteristics.LENS_FACING_BACK) continue;
            int[] caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (caps != null) {
                boolean compat = false;
                for (int c : caps) {
                    if (c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) {
                        compat = true;
                    }
                }
                if (!compat) continue; // salta sensori di profondità, IR, ecc.
            }
            return id;
        }
        return null;
    }

    private static long area(Size s) {
        return (long) s.getWidth() * s.getHeight();
    }

    private static <T> T await(CompletableFuture<T> f, long ms, String timeoutMessage) throws Exception {
        try {
            return f.get(ms, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException(timeoutMessage);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }

    // ------------------------------------------------------------------------------------

    private static final int PH_WARMUP = 0;
    private static final int PH_FOCUS = 1;
    private static final int PH_METER = 2;

    private static final class Shot {
        private final CameraManager mgr;
        private final String id;
        private final int rotation;
        private final CameraCharacteristics cc;

        private final HandlerThread thread = new HandlerThread("photobridge-camera");
        private Handler handler;

        private final CompletableFuture<byte[]> result = new CompletableFuture<byte[]>();
        private final CompletableFuture<CameraDevice> opened = new CompletableFuture<CameraDevice>();
        private volatile boolean closed;

        private volatile CameraDevice device;
        private volatile CameraCaptureSession session;
        private ImageReader jpegReader;
        private ImageReader previewReader;

        private int afMode = -1; // -1: messa a fuoco non disponibile (focale fissa)

        // stato macchina (usato solo dal thread della fotocamera)
        private int phase = PH_WARMUP;
        private int frames;
        private long phaseStart;
        private boolean stillSent;

        Shot(CameraManager mgr, String id, int rotation) throws CameraAccessException {
            this.mgr = mgr;
            this.id = id;
            this.rotation = rotation;
            this.cc = mgr.getCameraCharacteristics(id);
        }

        byte[] run() throws Exception {
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) throw new IOException("configurazione stream non disponibile");
            Size jpegSize = pickJpegSize(map);
            Size previewSize = pickPreviewSize(map, jpegSize);
            chooseAutofocus();

            thread.start();
            handler = new Handler(thread.getLooper());

            jpegReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 1);
            previewReader = ImageReader.newInstance(previewSize.getWidth(), previewSize.getHeight(),
                    ImageFormat.YUV_420_888, 2);

            jpegReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override public void onImageAvailable(ImageReader reader) { onJpeg(reader); }
            }, handler);
            // I frame dell'anteprima servono solo a far girare AF/AE: li scartiamo subito.
            previewReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override public void onImageAvailable(ImageReader reader) {
                    try {
                        Image img = reader.acquireLatestImage();
                        if (img != null) img.close();
                    } catch (Throwable ignored) {
                    }
                }
            }, handler);

            mgr.openCamera(id, stateCallback, handler);
            CameraDevice dev = await(opened, OPEN_TIMEOUT_MS,
                    "timeout apertura fotocamera (è in uso da un'altra app?)");

            List<Surface> surfaces = new ArrayList<Surface>();
            surfaces.add(previewReader.getSurface());
            surfaces.add(jpegReader.getSurface());
            dev.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    if (closed) return;
                    try {
                        startPreview();
                    } catch (Throwable t) {
                        fail(t);
                    }
                }

                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    fail(new IOException("configurazione sessione fotocamera fallita"));
                }
            }, handler);

            return await(result, TOTAL_TIMEOUT_MS, "timeout: la fotocamera non ha prodotto l'immagine");
        }

        void close() {
            closed = true;
            try { if (session != null) session.close(); } catch (Throwable ignored) { }
            try { if (device != null) device.close(); } catch (Throwable ignored) { }
            try { if (jpegReader != null) jpegReader.close(); } catch (Throwable ignored) { }
            try { if (previewReader != null) previewReader.close(); } catch (Throwable ignored) { }
            thread.quitSafely();
        }

        private void fail(Throwable t) {
            result.completeExceptionally(t);
            opened.completeExceptionally(t);
        }

        private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice d) {
                device = d;
                if (closed) {
                    d.close();
                    return;
                }
                opened.complete(d);
            }

            @Override public void onDisconnected(CameraDevice d) {
                d.close();
                fail(new IOException("fotocamera disconnessa"));
            }

            @Override public void onError(CameraDevice d, int error) {
                d.close();
                fail(new IOException("errore fotocamera (codice " + error + ")"));
            }
        };

        // --------------------------- scelta parametri ---------------------------

        private void chooseAutofocus() {
            int[] modes = cc.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            Float minFocus = cc.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            boolean fixedFocus = minFocus != null && minFocus.floatValue() == 0f;
            if (modes == null || fixedFocus) {
                afMode = -1;
                return;
            }
            boolean hasContinuous = false;
            boolean hasAuto = false;
            for (int m : modes) {
                if (m == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) hasContinuous = true;
                if (m == CameraMetadata.CONTROL_AF_MODE_AUTO) hasAuto = true;
            }
            if (hasContinuous) afMode = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
            else if (hasAuto) afMode = CameraMetadata.CONTROL_AF_MODE_AUTO;
            else afMode = -1;
        }

        private Size pickJpegSize(StreamConfigurationMap map) throws IOException {
            Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
            if (sizes == null || sizes.length == 0) throw new IOException("nessuna risoluzione JPEG disponibile");
            Size best = null;
            Size smallest = sizes[0];
            for (Size s : sizes) {
                long a = area(s);
                if (a < area(smallest)) smallest = s;
                if (a <= MAX_PIXELS && (best == null || a > area(best))) best = s;
            }
            return best != null ? best : smallest;
        }

        private Size pickPreviewSize(StreamConfigurationMap map, Size jpeg) throws IOException {
            Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            if (sizes == null || sizes.length == 0) throw new IOException("nessuna risoluzione di anteprima disponibile");
            double target = (double) jpeg.getWidth() / jpeg.getHeight();
            Size best = null;
            Size smallest = sizes[0];
            for (Size s : sizes) {
                long a = area(s);
                if (a < area(smallest)) smallest = s;
                double ratio = (double) s.getWidth() / s.getHeight();
                if (s.getWidth() <= 1280 && Math.abs(ratio - target) < 0.02 && (best == null || a > area(best))) {
                    best = s;
                }
            }
            return best != null ? best : smallest;
        }

        private int jpegOrientation() {
            Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int sensor = so != null ? so.intValue() : 90;
            // fotocamera posteriore: orientamento = sensore + rotazione del telefono
            return (sensor + rotation + 360) % 360;
        }

        // --------------------------- sequenza di scatto ---------------------------

        private CaptureRequest.Builder newPreviewBuilder() throws CameraAccessException {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(previewReader.getSurface());
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            if (afMode >= 0) b.set(CaptureRequest.CONTROL_AF_MODE, afMode);
            return b;
        }

        private void startPreview() throws CameraAccessException {
            CaptureRequest.Builder b = newPreviewBuilder();
            phase = PH_WARMUP;
            frames = 0;
            phaseStart = SystemClock.elapsedRealtime();
            session.setRepeatingRequest(b.build(), progressCallback, handler);
        }

        private final CameraCaptureSession.CaptureCallback progressCallback =
                new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request,
                                                   TotalCaptureResult r) {
                        try {
                            onProgress(r);
                        } catch (Throwable t) {
                            fail(t);
                        }
                    }
                };

        private void onProgress(TotalCaptureResult r) throws CameraAccessException {
            if (stillSent || closed) return;
            long now = SystemClock.elapsedRealtime();
            Integer ae = r.get(CaptureResult.CONTROL_AE_STATE);
            Integer af = r.get(CaptureResult.CONTROL_AF_STATE);

            if (phase == PH_WARMUP) {
                frames++;
                if (frames >= WARMUP_FRAMES) {
                    if (afMode >= 0) {
                        triggerAutofocus();
                        phase = PH_FOCUS;
                    } else {
                        phase = PH_METER;
                    }
                    phaseStart = now;
                }
            } else if (phase == PH_FOCUS) {
                boolean locked = af != null
                        && (af.intValue() == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                        || af.intValue() == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED);
                if (locked || now - phaseStart > FOCUS_TIMEOUT_MS) {
                    phase = PH_METER;
                    phaseStart = now;
                }
            }

            if (phase == PH_METER) {
                boolean aeReady = ae == null
                        || ae.intValue() == CaptureResult.CONTROL_AE_STATE_CONVERGED
                        || ae.intValue() == CaptureResult.CONTROL_AE_STATE_LOCKED
                        || ae.intValue() == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED;
                if (aeReady || now - phaseStart > AE_TIMEOUT_MS) {
                    takeStill();
                }
            }
        }

        /** Avvia la messa a fuoco automatica; il lock resta attivo finché non c'è un CANCEL. */
        private void triggerAutofocus() throws CameraAccessException {
            CaptureRequest.Builder b = newPreviewBuilder();
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            session.capture(b.build(), null, handler);
        }

        private void takeStill() throws CameraAccessException {
            stillSent = true;
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(jpegReader.getSurface());
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            if (afMode >= 0) b.set(CaptureRequest.CONTROL_AF_MODE, afMode);
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
            b.set(CaptureRequest.JPEG_QUALITY, (byte) JPEG_QUALITY);
            session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest request, CaptureFailure failure) {
                    fail(new IOException("scatto fallito (motivo " + failure.getReason() + ")"));
                }
            }, handler);
        }

        private void onJpeg(ImageReader reader) {
            Image img = null;
            try {
                img = reader.acquireNextImage();
                ByteBuffer buf = img.getPlanes()[0].getBuffer();
                byte[] data = new byte[buf.remaining()];
                buf.get(data);
                result.complete(data);
            } catch (Throwable t) {
                fail(t);
            } finally {
                if (img != null) img.close();
            }
        }
    }
}
