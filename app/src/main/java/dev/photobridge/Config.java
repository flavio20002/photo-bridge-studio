package dev.photobridge;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Locale;

/** Impostazioni persistenti: porta, PIN di accesso, opzione "schermo sempre acceso". */
final class Config {
    static final int PORT = 8765;

    private static final String PREFS = "photobridge";

    private Config() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** PIN a 6 cifre, generato al primo avvio. Va inserito anche nel plugin Obsidian. */
    static synchronized String pin(Context c) {
        SharedPreferences p = prefs(c);
        String pin = p.getString("pin", null);
        if (pin == null) {
            pin = newPin(c);
        }
        return pin;
    }

    static synchronized String newPin(Context c) {
        String pin = String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1000000));
        prefs(c).edit().putString("pin", pin).apply();
        return pin;
    }

    static boolean keepScreenOn(Context c) {
        return prefs(c).getBoolean("keep_screen_on", true);
    }

    static void setKeepScreenOn(Context c, boolean value) {
        prefs(c).edit().putBoolean("keep_screen_on", value).apply();
    }
}
