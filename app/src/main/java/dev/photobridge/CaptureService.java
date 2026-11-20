package dev.photobridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Foreground service (tipo "camera") che tiene vivo il server anche con lo schermo spento.
 * Mantiene un WakeLock parziale (CPU) e un WifiLock (Wi-Fi sveglio).
 */
public class CaptureService extends Service {

    static final String ACTION_STOP = "dev.photobridge.STOP";
    private static final String CHANNEL_ID = "photobridge";
    private static final int NOTIF_ID = 1;

    // stato letto dalla MainActivity
    static volatile boolean running;
    static volatile String lastEvent = "";
    static volatile String error = "";

    private HttpServer server;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;

        try {
            // 64 = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA (costante numerica: funziona con qualsiasi android.jar)
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, 64);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            error = "Impossibile avviare il servizio: " + e.getMessage();
            stopSelf();
            return START_NOT_STICKY;
        }

        acquireLocks();
        try {
            server = new HttpServer(this, Config.PORT, new CameraCapture(this), new HttpServer.Listener() {
                @Override public void onEvent(String message) { lastEvent = message; }
            });
            server.start();
            running = true;
            error = "";
        } catch (Exception e) {
            error = "Impossibile aprire la porta " + Config.PORT + ": " + e.getMessage();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        if (server != null) server.stop();
        releaseLocks();
        stopForeground(true);
        super.onDestroy();
    }

    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "photobridge:server");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            int mode = Build.VERSION.SDK_INT >= 29
                    ? 4 /* WIFI_MODE_FULL_LOW_LATENCY */
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "photobridge:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(
                new NotificationChannel(CHANNEL_ID, "PhotoBridge", NotificationManager.IMPORTANCE_LOW));

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CaptureService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);

        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Ferma", stop).build();

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("PhotoBridge attivo")
                .setContentText("In ascolto sulla porta " + Config.PORT)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(stopAction)
                .build();
    }
}
