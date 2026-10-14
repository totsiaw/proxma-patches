package app.proxma.fpdark;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import java.lang.reflect.Method;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;

/**
 * Runtime half of the foodpanda dark-mode patch.
 *
 * The app builds a light and a dark Bento palette, but R8 merged the two constructors into
 * byte-identical literals, so selecting the dark instance changes nothing on screen. The Kotlin
 * patch therefore rewrites the colour getters, and gates each rewrite on {@link #on}.
 *
 * {@link #on} is loaded once in <clinit> via reflection on ActivityThread, because the first
 * palette getter runs before any Activity exists. It defaults to dark, so an install with no
 * preference written behaves exactly like the un-gated build.
 */
public final class DarkMode {

    public static boolean on;

    private static final String PREF = "fp_dark_mode";

    private DarkMode() {}

    static {
        on = load();
    }

    private static SharedPreferences prefs(Context context) {
        Context ctx = context.getApplicationContext();
        if (ctx == null) {
            if (context instanceof ContextWrapper) {
                ctx = ((ContextWrapper) context).getBaseContext();
            }
            if (ctx == null) ctx = context;
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

    public static void set(Context context, boolean value) {
        on = value;
        prefs(context).edit().putBoolean(PREF, value).commit();
    }

    public static void restart(Context context) {
        try {
            Intent launch = context.getPackageManager()
                    .getLaunchIntentForPackage(context.getPackageName());
            if (launch != null && launch.getComponent() != null) {
                Intent restart = Intent.makeRestartActivityTask(launch.getComponent());
                context.startActivity(restart);
            }
        } catch (Throwable ignored) {
        }
        Runtime.getRuntime().exit(0);
    }

    /** Dark counterpart of the light rating-card gradient start stop. */
    public static long ratingStart(long light) {
        return on ? 0xFF2B1820L : light;
    }

    /** Dark counterpart of the light rating-card gradient end stop. */
    public static long ratingEnd(long light) {
        return on ? 0xFF52253AL : light;
    }

    /**
     * Appends a "Dark mode" card to an existing settings row, mirroring that row's CardView
     * styling so the injected row is visually indistinguishable from the app's own.
     */
    public static void attach(View row) {
        try {
            if (!(row instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) row;
            if (parent.getChildCount() == 0) return;

            View template = parent.getChildAt(0);
            if (!(template instanceof ViewGroup)) return;
            ViewGroup templateGroup = (ViewGroup) template;
            if (templateGroup.getChildCount() == 0) return;

            View clone = templateGroup.getChildAt(0);
            Context context = row.getContext();

            androidx.cardview.widget.CardView card = new androidx.cardview.widget.CardView(context);
            card.setRadius(((androidx.cardview.widget.CardView) clone).getRadius());
            card.setCardElevation(((androidx.cardview.widget.CardView) clone).getCardElevation());
            card.setMaxCardElevation(((androidx.cardview.widget.CardView) clone).getMaxCardElevation());
            card.setUseCompatPadding(((androidx.cardview.widget.CardView) clone).getUseCompatPadding());
            card.setPreventCornerOverlap(((androidx.cardview.widget.CardView) clone).getPreventCornerOverlap());
            card.setCardBackgroundColor(((androidx.cardview.widget.CardView) clone).getCardBackgroundColor());

            ViewGroup.LayoutParams lp = clone.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams) {
                card.setLayoutParams(new LinearLayout.LayoutParams((LinearLayout.LayoutParams) lp));
            }

            LinearLayout inner = new LinearLayout(context);
            inner.setOrientation(LinearLayout.VERTICAL);

            if (templateGroup.getChildCount() > 0) {
                View innerTemplate = templateGroup.getChildAt(0);
                inner.setPadding(
                        innerTemplate.getPaddingLeft(),
                        innerTemplate.getPaddingTop(),
                        innerTemplate.getPaddingRight(),
                        innerTemplate.getPaddingBottom());
            }

            com.deliveryhero.pretty.core.CoreCheckBox check = new com.deliveryhero.pretty.core.CoreCheckBox(context, null);
            check.setText("Dark mode");
            check.setChecked(on);
            check.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    set(context, isChecked);
                    restart(context);
                }
            });
            inner.addView(check, new LinearLayout.LayoutParams(-1, -2));

            card.addView(inner);
            parent.addView(card, 1);
        } catch (Throwable ignored) {
        }
    }
}
