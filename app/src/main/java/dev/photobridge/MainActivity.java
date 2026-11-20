package dev.photobridge;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Schermata unica: mostra IP:porta e PIN, avvia/ferma il server. */
public class MainActivity extends Activity {

    private static final int REQ_PERMISSIONS = 1;

    private TextView addressView;
    private TextView pinView;
    private TextView statusView;
    private Button toggleButton;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateUi();
            ui.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        applyKeepScreenOn(Config.keepScreenOn(this));
        if (!CaptureService.running) ensurePermissionsAndStart();
    }

    @Override protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresh);
    }

    // ---------------------------------- UI ----------------------------------

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView text(String s, int sp, boolean bold, boolean mono) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(mono ? Typeface.MONOSPACE : Typeface.DEFAULT, Typeface.BOLD);
        else if (mono) t.setTypeface(Typeface.MONOSPACE);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(32), dp(20), dp(20));

        root.addView(text("PhotoBridge", 26, true, false));
        root.addView(text("Indirizzo da inserire nel plugin Obsidian:", 14, false, false));

        addressView = text("", 22, true, true);
        addressView.setTextIsSelectable(true);
        root.addView(addressView);

        pinView = text("", 22, true, true);
        pinView.setTextIsSelectable(true);
        root.addView(pinView);

        statusView = text("", 15, false, false);
        root.addView(statusView);

        toggleButton = new Button(this);
        toggleButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (CaptureService.running) {
                    stopService(new Intent(MainActivity.this, CaptureService.class));
                } else {
                    ensurePermissionsAndStart();
                }
            }
        });
        root.addView(toggleButton);

        CheckBox keep = new CheckBox(this);
        keep.setText("Tieni lo schermo acceso mentre l'app è aperta");
        keep.setChecked(Config.keepScreenOn(this));
        keep.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                Config.setKeepScreenOn(MainActivity.this, checked);
                applyKeepScreenOn(checked);
            }
        });
        root.addView(keep);

        Button newPin = new Button(this);
        newPin.setText("Genera nuovo PIN");
        newPin.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Config.newPin(MainActivity.this);
                updateUi();
            }
        });
        root.addView(newPin);

        Button battery = new Button(this);
        battery.setText("Escludi dall'ottimizzazione batteria");
        battery.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                            Uri.parse("package:" + getPackageName())));
                } catch (ActivityNotFoundException ignored) {
                }
            }
        });
        root.addView(battery);

        root.addView(text("Con lo schermo spento il server resta attivo (servizio in primo piano), "
                + "ma alcuni telefoni bloccano la fotocamera o il Wi-Fi quando si blocca lo schermo. "
                + "In quel caso lascia attiva l'opzione \"schermo acceso\" e non bloccare il telefono.",
                13, false, false));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void applyKeepScreenOn(boolean on) {
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void updateUi() {
        List<String> addresses = localAddresses();
        if (addresses.isEmpty()) {
            addressView.setText("Nessuna rete: attiva il Wi-Fi o l'hotspot");
        } else {
            StringBuilder sb = new StringBuilder();
            for (String a : addresses) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(a).append(':').append(Config.PORT);
            }
            addressView.setText(sb.toString());
        }
        pinView.setText("PIN: " + Config.pin(this));

        String status;
        if (CaptureService.running) status = "● Server attivo";
        else if (!CaptureService.error.isEmpty()) status = "⚠ " + CaptureService.error;
        else status = "○ Server fermo";
        if (!CaptureService.lastEvent.isEmpty()) status += "\n" + CaptureService.lastEvent;
        statusView.setText(status);

        toggleButton.setText(CaptureService.running ? "Ferma server" : "Avvia server");
    }

    // ------------------------------ permessi / avvio ------------------------------

    private void ensurePermissionsAndStart() {
        List<String> missing = new ArrayList<String>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            missing.add("android.permission.POST_NOTIFICATIONS");
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMISSIONS);
            return;
        }
        startServer();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSIONS) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startServer();
        } else {
            CaptureService.error = "Permesso fotocamera negato: concedilo dalle impostazioni dell'app";
            updateUi();
        }
    }

    private void startServer() {
        CaptureService.error = "";
        startForegroundService(new Intent(this, CaptureService.class));
    }

    // ------------------------------ indirizzi IP ------------------------------

    /** Indirizzi IPv4 locali (Wi-Fi, hotspot, ...), quelli di rete privata per primi. */
    static List<String> localAddresses() {
        List<String> priv = new ArrayList<String>();
        List<String> other = new ArrayList<String>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        if (a.isSiteLocalAddress()) priv.add(a.getHostAddress());
                        else other.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        priv.addAll(other);
        return priv;
    }
}
