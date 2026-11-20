package dev.photobridge;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Mini server HTTP/1.1 senza dipendenze.
 *
 *   GET  /ping                 -> {"ok":true}
 *   GET|POST /capture[?rotation=0|90|180|270]  -> image/jpeg
 *
 * Ogni richiesta deve avere il PIN nell'header "X-Token" (oppure ?pin=...).
 */
final class HttpServer {

    interface Listener {
        void onEvent(String message);
    }

    private final Context ctx;
    private final int port;
    private final CameraCapture camera;
    private final Listener listener;
    private final Object failedAuthLock = new Object();

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running;

    HttpServer(Context ctx, int port, CameraCapture camera, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.port = port;
        this.camera = camera;
        this.listener = listener;
    }

    void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        pool = Executors.newCachedThreadPool();
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "photobridge-accept");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        if (pool != null) pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = serverSocket.accept();
                pool.execute(new Runnable() {
                    @Override public void run() { handle(s); }
                });
            } catch (IOException e) {
                if (!running || serverSocket.isClosed()) return;
            } catch (RejectedExecutionException e) {
                return;
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(10000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                respond(out, 400, "text/plain; charset=utf-8", text("Richiesta non valida"));
                return;
            }
            String method = parts[0];
            String target = parts[1];

            Map<String, String> headers = new HashMap<String, String>();
            String line;
            int count = 0;
            while ((line = readLine(in)) != null && !line.isEmpty() && count++ < 100) {
                int i = line.indexOf(':');
                if (i > 0) {
                    headers.put(line.substring(0, i).trim().toLowerCase(Locale.ROOT),
                            line.substring(i + 1).trim());
                }
            }

            String path = target;
            Map<String, String> query = new HashMap<String, String>();
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                parseQuery(target.substring(q + 1), query);
            }

            String token = headers.get("x-token");
            if (token == null) token = query.get("pin");
            if (!authorized(token)) {
                // Rallenta i tentativi a forza bruta: gli errori sono serializzati.
                synchronized (failedAuthLock) {
                    try { Thread.sleep(400); } catch (InterruptedException ignored) { }
                }
                respond(out, 401, "text/plain; charset=utf-8", text("PIN errato o mancante"));
                return;
            }

            boolean methodOk = "GET".equals(method) || "POST".equals(method);
            if ("/ping".equals(path) && methodOk) {
                respond(out, 200, "application/json", text("{\"ok\":true,\"app\":\"PhotoBridge\"}"));
            } else if ("/capture".equals(path) && methodOk) {
                int rotation = parseRotation(query.get("rotation"));
                try {
                    byte[] jpg = camera.capture(rotation);
                    respond(out, 200, "image/jpeg", jpg);
                    listener.onEvent("Foto inviata alle " + now() + " (" + (jpg.length / 1024) + " KB)");
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    respond(out, 500, "text/plain; charset=utf-8", text("Errore fotocamera: " + msg));
                    listener.onEvent("Errore alle " + now() + ": " + msg);
                }
            } else {
                respond(out, 404, "text/plain; charset=utf-8", text("Non trovato"));
            }
        } catch (IOException ignored) {
            // client disconnesso o timeout
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private boolean authorized(String token) {
        if (token == null) return false;
        byte[] a = token.getBytes(StandardCharsets.UTF_8);
        byte[] b = Config.pin(ctx).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private static int parseRotation(String value) {
        if (value == null) return 0;
        try {
            int r = Integer.parseInt(value.trim());
            return (((r / 90) * 90) % 360 + 360) % 360;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void parseQuery(String qs, Map<String, String> out) {
        for (String pair : qs.split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            try {
                String k = URLDecoder.decode(i >= 0 ? pair.substring(0, i) : pair, "UTF-8");
                String v = i >= 0 ? URLDecoder.decode(pair.substring(i + 1), "UTF-8") : "";
                out.put(k, v);
            } catch (Exception ignored) {
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("Riga troppo lunga");
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            default: return "Internal Server Error";
        }
    }

    private static void respond(OutputStream out, int code, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }
}
