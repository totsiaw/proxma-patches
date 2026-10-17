package app.proxma.olxsa;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.lang.reflect.Method;

/**
 * Runtime half of the OLX India dark-mode patch.
 *
 * <p>OLX India ships no app-authored night resources and no dark palette: the UI palette class
 * {@code com.olx.compose_hub.theme.a} builds every colour once, in {@code <clinit>}, from a light
 * literal, and 578 read sites then read those fields. The Kotlin patch rewrites the constants that
 * class materialises; each rewrite is gated on {@link #on}.
 *
 * <p>{@link #on} is read once in {@code <clinit>} through {@code ActivityThread.currentApplication()},
 * because the palette is materialised before any Activity exists. It defaults to dark, so a build
 * with no preference written behaves exactly like an un-gated one.
 *
 * <p>Alpha is preserved: a translucent light token yields a translucent dark token of the same
 * alpha, which is what makes the app's overlay/scrim tokens survive the rewrite.
 */
public final class DarkMode {

    public static boolean on;

    private static final String PREF = "olx_dark_mode";

    /** Light 24-bit RGB (alpha excluded) keys, in the same order as {@link #DARK}. */
    private static final int[] LIGHT = {0x00469b, 0x004896, 0x0060c8, 0x0078fa, 0x00a49f, 0x020812, 0x111111, 0x12b76a, 0x13726d, 0x1ba39c, 0x212121, 0x235aaa, 0x282d36, 0x3a77ff, 0x424242, 0x42b3ad, 0x464b52, 0x5a8cc8, 0x5b5e65, 0x727272, 0x847ce3, 0x8d9094, 0xa2d9d6, 0xb0d5fd, 0xbf2600, 0xc0c1c4, 0xca4b2b, 0xceddff, 0xd8dfe0, 0xd9dadb, 0xde1609, 0xe2e2e2, 0xe5e5e5, 0xe6c803, 0xe6f2ff, 0xe8e8e8, 0xe8f6f5, 0xebfee6, 0xeeeeee, 0xf0f0f1, 0xf2f4f5, 0xf5f5f6, 0xf5f8ff, 0xf5faff, 0xf6e4b3, 0xf6f6f6, 0xf6f8fc, 0xf9e9e6, 0xfcfcfc, 0xfff5b1, 0xfff8fc, 0xfffad9, 0xfffce6, 0xfffdef, 0xffffff};

    /** Dark 24-bit RGB counterparts. */
    private static final int[] DARK = {0x4a8ad4, 0x4f93e0, 0x4f93e0, 0x4d9bff, 0x2fc4be, 0xf2f4f6, 0xf2f4f6, 0x3ddc97, 0x2f9d96, 0x35c1b9, 0xe8eaed, 0x5c8fd8, 0xe8eaed, 0x6f9cff, 0xb4b9bf, 0x45c2bb, 0xd7dade, 0x8fb6e0, 0xc8ccd2, 0xa8aeb5, 0xa79ff0, 0x9aa0a6, 0x1c3a38, 0x24384d, 0xff7a5c, 0x8b9298, 0xff8a6b, 0x22344a, 0x343a41, 0x343a41, 0xff6b5e, 0x2c3036, 0x2e3238, 0x3a2f18, 0x1a2a3d, 0x2a2e33, 0x142a29, 0x15291f, 0x26292e, 0x22262b, 0x1c2024, 0x1a1d21, 0x1a2029, 0x1a2029, 0x3a2f18, 0x1a1d21, 0x1a1d21, 0x2e1d19, 0x16191d, 0x33291a, 0x1a1d21, 0x33291a, 0x2b2616, 0x2b2616, 0x121417};

    static {
        on = load();
    }

    private DarkMode() {}

    private static SharedPreferences prefs(Context context) {
        Context ctx = context.getApplicationContext();
        if (ctx == null) {
            if (context instanceof ContextWrapper) {
                ctx = ((ContextWrapper) context).getBaseContext();
            }
            if (ctx == null) {
                ctx = context;
            }
        }
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /**
     * Reads the preference at class-init time. There is no Context yet, so go through
     * ActivityThread.currentApplication(). Any failure returns the dark default.
     */
    private static boolean load() {
        boolean fallback = true; // dark
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method current = at.getMethod("currentApplication");
            Object app = current.invoke(null);
            if (app instanceof Context) {
                return prefs((Context) app).getBoolean(PREF, fallback);
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** Called from the app's Application.onCreate() by the patch's anchor. */
    public static void install(Context context) {
        try {
            on = prefs(context).getBoolean(PREF, on);
        } catch (Throwable ignored) {
        }
    }

    public static void set(Context context, boolean value) {
        on = value;
        prefs(context).edit().putBoolean(PREF, value).commit();
    }

    /**
     * Dark counterpart of a packed Compose colour ({@code argb &lt;&lt; 32}), or the input unchanged
     * when the rewrite is off or the value is one of the {@code 55} light keys not in the
     * palette. The incoming alpha is kept so translucent tokens stay translucent.
     */
    public static long dark(long light) {
        if (!on) {
            return light;
        }
        int argb = (int) (light >>> 32);
        int rgb = argb & 0xFFFFFF;
        for (int i = 0; i < LIGHT.length; i++) {
            if (LIGHT[i] == rgb) {
                int out = (argb & 0xFF000000) | DARK[i];
                return ((long) (out & 0xFFFFFFFFL)) << 32;
            }
        }
        return light;
    }
}
