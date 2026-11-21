package app.proxma.rox;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.lang.ref.WeakReference;

/**
 * The check-in always works once ROX's own screen is open. So the background run opens ROX's real
 * screen while the phone is unlocked and in use, makes it see-through so the user keeps seeing and
 * using what they had open, runs the normal check-in (AutoCheckin) and closes it again.
 *
 * Android only lets an app open a screen from the background in a few cases. The one used here is
 * "Appear on top" (SYSTEM_ALERT_WINDOW), which the user grants once in Settings; without it ROX asks
 * for it, on an app open, at most every three days. Since Android 15 "Appear on top" only counts
 * while the app has an overlay window on screen, so a 1-pixel window is put up just before the
 * start and taken down once the screen opens. Nothing is ever shown over the lock screen: a screen
 * that comes up while the phone is locked or the display is off is closed straight away.
 *
 * Only a screen whose intent carries the EXTRA marker (set by launchIntent, never by a notice or
 * the launcher) is treated as the check-in screen; any other ROX screen is the user's.
 */
final class WakeScreen {

    private static final String TAG = "PROXMA_ROX";
    static final String EXTRA = "proxma_rox_wake";
    private static final int ASK_ID = 0x524f5805;
    private static final int REQUEST_ASK = 0x524f5807;
    static final String KEY_ASKED_AT = "wake_asked_at";
    static final String KEY_OPEN_ASKED_AT = "wake_open_asked_at";
    static final String KEY_OPENED_AT = "wake_opened_at";
    // Any ROX screen created, marked or not: tells "Android blocked the start" apart from
    // "a screen was made but not as the check-in screen".
    static final String KEY_ANY_CREATED_AT = "wake_any_created_at";
    private static final String ROUTE_OVERLAY = "appear on top";
    // How long after asking the start is checked, and the result recorded if the screen didn't open.
    static final long BACKUP_NOTICE_MS = 20_000L;
    private static final long ASK_EVERY_MS = 3L * 24L * 60L * 60L * 1000L;
    // Longest the check-in screen stays open: just past the session's own 120 s timeout.
    private static final long MAX_OPEN_MS = 125_000L;

    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> sWoken = new WeakReference<>(null);
    private static WeakReference<Activity> sClosing = new WeakReference<>(null);
    // The check-in screen made see-through, so the phone's user keeps seeing and using what they had.
    private static WeakReference<Activity> sHidden = new WeakReference<>(null);
    // An existing ROX screen that got the background run's marked intent through onNewIntent and
    // has not come up since; taken as the check-in screen at its next start or resume.
    private static WeakReference<Activity> sMarkedNewIntent = new WeakReference<>(null);

    private WakeScreen() {}

    /** The way ROX's screen can be opened from the background right now, or null for none. */
    static String route(Context context) {
        if (canAppearOnTop(context)) return ROUTE_OVERLAY;
        return null;
    }

    private static boolean canAppearOnTop(Context context) {
        try {
            return Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(context);
        } catch (Throwable t) {
            Log.w(TAG, "WAKE overlay check failed " + t);
            return false;
        }
    }

    /**
     * From the background run, with the phone unlocked: opens ROX's screen for the check-in.
     * Returns the route used, or null when Android allows none or the phone is locked or dark, in
     * which case nothing was done.
     */
    static String open(Context context) {
        final Context app = context.getApplicationContext();
        final Intent intent = launchIntent(app);
        if (intent == null) {
            Log.w(TAG, "WAKE no launch intent");
            return null;
        }
        final long at = System.currentTimeMillis();
        sPhoneAtStart = phoneState(app);
        if (lockedOrDark(app)) {
            Log.i(TAG, "WAKE phone locked or screen off (" + sPhoneAtStart + "), not opening ROX");
            return null;
        }
        if (!canAppearOnTop(app)) {
            Log.i(TAG, "WAKE no way to open the screen from the background");
            return null;
        }
        // Since Android 15 "Appear on top" only lets an app open a screen from the background
        // while one of its overlay windows is on screen; without one Android drops the start
        // silently (r28 on the reporter's phone). So put up a 1-pixel window first.
        startOnTop(app, intent, at);
        AutoCheckin.prefs(app).edit().putLong(KEY_OPEN_ASKED_AT, at).commit();
        // Android can drop a background start without saying so: check and say why.
        sHandler.postDelayed(new Runnable() {
            @Override public void run() {
                afterOpen(app, at);
            }
        }, BACKUP_NOTICE_MS);
        return ROUTE_OVERLAY;
    }

    static boolean lockedOrDark(Context app) {
        try {
            KeyguardManager keyguard = (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
            PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            return (keyguard != null && keyguard.isKeyguardLocked()) || (power != null && !power.isInteractive());
        } catch (Throwable t) {
            Log.w(TAG, "WAKE lock check failed " + t);
            return false;
        }
    }

    private static String sPhoneAtStart = "phone state unknown";
    private static View sHelper;
    private static WindowManager sHelperWm;
    private static String sHelperState = "not used";
    private static int sStarts = 0;

    /** Puts up the helper window, then starts ROX's screen once it is on screen, and once more later. */
    private static void startOnTop(final Context app, final Intent intent, final long at) {
        sStarts = 0;
        boolean shown = showHelper(app);
        long first = shown ? 600L : 0L;
        final Runnable start = new Runnable() {
            @Override public void run() {
                if (openedSince(app, at)) return;
                sStarts++;
                if (sHelper != null) {
                    sHelperState = sHelper.isAttachedToWindow()
                        ? (sHelper.getWindowVisibility() == View.VISIBLE ? "on screen" : "added but hidden by Android")
                        : "added but never attached";
                }
                try {
                    app.startActivity(intent);
                    Log.i(TAG, "WAKE screen start " + sStarts + " asked (appear on top, helper window "
                        + sHelperState + ")");
                } catch (Throwable t) {
                    Log.w(TAG, "WAKE direct start " + sStarts + " failed " + t);
                }
            }
        };
        sHandler.postDelayed(start, first);
        sHandler.postDelayed(start, first + 4_000L);
    }

    private static boolean showHelper(Context app) {
        removeHelper();
        try {
            Context context = app;
            if (Build.VERSION.SDK_INT >= 30) {
                DisplayManager displays = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
                Display display = displays != null ? displays.getDisplay(Display.DEFAULT_DISPLAY) : null;
                if (display != null) {
                    context = app.createDisplayContext(display)
                        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
                }
            }
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                sHelperState = "no window manager";
                Log.w(TAG, "WAKE helper window: no window manager");
                return false;
            }
            View view = new View(context);
            view.setBackgroundColor(0x01000000);
            @SuppressWarnings("deprecation")
            int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
            @SuppressWarnings("deprecation")
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(1, 1, type, flags,
                PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.setTitle("ROX check-in helper");
            wm.addView(view, params);
            sHelper = view;
            sHelperWm = wm;
            sHelperState = "added";
            Log.i(TAG, "WAKE helper window added");
            return true;
        } catch (Throwable t) {
            sHelperState = "not allowed (" + t.getClass().getSimpleName() + ")";
            Log.w(TAG, "WAKE helper window failed " + t);
            return false;
        }
    }

    private static void removeHelper() {
        View view = sHelper;
        WindowManager wm = sHelperWm;
        sHelper = null;
        sHelperWm = null;
        if (view == null || wm == null) return;
        try {
            wm.removeViewImmediate(view);
            Log.i(TAG, "WAKE helper window removed");
        } catch (Throwable t) {
            Log.w(TAG, "WAKE helper window remove failed " + t);
        }
    }

    /** A while after asking: done if the screen opened, else record why it didn't. */
    private static void afterOpen(Context app, long at) {
        removeHelper();
        if (openedSince(app, at)) {
            Log.i(TAG, "WAKE screen opened, nothing to back up");
            return;
        }
        String phone = "at start " + sPhoneAtStart + ", now " + phoneState(app);
        long created = AutoCheckin.prefs(app).getLong(KEY_ANY_CREATED_AT, 0L);
        String made = created >= at
            ? "a ROX screen was made but not as the check-in screen"
            : "no ROX screen was made";
        String text = "asked Android " + sStarts + "x to open ROX's screen (appear on top, helper window "
            + sHelperState + ") but it didn't open; " + made + "; " + phone;
        Log.w(TAG, "WAKE screen didn't open: " + text);
        // The request is over: a ROX screen that comes up later is the user's, not this one.
        SharedPreferences prefs = AutoCheckin.prefs(app);
        if (prefs.getLong(KEY_OPEN_ASKED_AT, 0L) == at) prefs.edit().remove(KEY_OPEN_ASKED_AT).commit();
        AutoCheckin.recordBackground(app, text);
        Notices.needsYou(app, "ROX couldn't open itself in the background.");
    }

    private static String phoneState(Context app) {
        try {
            KeyguardManager keyguard = (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
            PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            String locked = keyguard == null ? "lock unknown" : keyguard.isKeyguardLocked() ? "phone locked" : "phone unlocked";
            String screen = power == null ? "screen unknown" : power.isInteractive() ? "screen on" : "screen off";
            return locked + ", " + screen;
        } catch (Throwable t) {
            return "phone state unknown";
        }
    }

    static boolean openedSince(Context context, long at) {
        return AutoCheckin.prefs(context).getLong(KEY_OPENED_AT, 0L) >= at;
    }

    private static Intent launchIntent(Context app) {
        Intent intent = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        if (intent == null) return null;
        // MainActivity is singleTop with an empty task affinity. With NEW_TASK alone, a ROX task
        // left in Recents is just brought back and its screen rebuilt from the original launcher
        // intent, so this marker was lost: the screen stayed behind the lock screen and counted
        // as "didn't open" (r28/r29). CLEAR_TASK always builds a fresh screen from this intent.
        // NO_ANIMATION: with the phone in use the screen is see-through (hide), so no slide-in either.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
            | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra(EXTRA, true);
        return intent;
    }

    /**
     * True when the intent is the background run's own request (launchIntent). Only such a screen
     * is ever made see-through or closed by the patch: one the user opens (launcher, Recents, the
     * "Tap to open ROX" notice) never carries the marker and stays as it is. A task brought back
     * from Recents replays its old root intent, so a launch from history never counts.
     */
    private static boolean marked(Intent intent) {
        return intent != null && intent.getBooleanExtra(EXTRA, false)
            && (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0;
    }

    /** From MainActivity.onCreate: takes a screen opened by the background run as the check-in screen. */
    static boolean onCreate(Activity activity) {
        try {
            Intent intent = activity.getIntent();
            SharedPreferences prefs = AutoCheckin.prefs(activity);
            long now = System.currentTimeMillis();
            prefs.edit().putLong(KEY_ANY_CREATED_AT, now).apply();
            if (!marked(intent)) {
                // r39 also took an unmarked screen made within 45 s of a request; that hid and
                // closed screens the user opened. No marker, no take: the screen is the user's.
                long asked = prefs.getLong(KEY_OPEN_ASKED_AT, 0L);
                if (asked > 0 && prefs.getLong(KEY_OPENED_AT, 0L) < asked) {
                    Log.i(TAG, "WAKE screen created without the marker while a background open is pending, "
                        + "leaving it to the user");
                }
                return false;
            }
            take(activity, prefs, now, "marked");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "WAKE_CREATE_ERR " + t);
            return false;
        }
    }

    /**
     * From MainActivity.onNewIntent: Android can hand the background run's request to a ROX screen
     * that already exists instead of making a new one (launchIntent's CLEAR_TASK normally prevents
     * that). Then onCreate never runs and the marker only reaches here; remember the screen so its
     * next start or resume takes it.
     */
    static void onNewIntent(Activity activity, Intent intent) {
        try {
            if (activity == null) return;
            if (!marked(intent)) {
                if (sMarkedNewIntent.get() == activity) sMarkedNewIntent = new WeakReference<>(null);
                return;
            }
            sMarkedNewIntent = new WeakReference<>(activity);
            Log.i(TAG, "WAKE marked intent delivered to an existing screen");
        } catch (Throwable t) {
            Log.w(TAG, "WAKE_NEW_INTENT_ERR " + t);
        }
    }

    /**
     * From the screen's start and resume: takes an existing screen as the check-in screen only when
     * the background run's marked intent reached it through onNewIntent. A screen that merely
     * comes up soon after a request (r30 to r39 took those) is the user's and is left alone.
     */
    static boolean onShown(Activity activity, String when) {
        try {
            if (activity == null || woken(activity) || closing(activity)) return woken(activity);
            if (sMarkedNewIntent.get() != activity) return false;
            sMarkedNewIntent = new WeakReference<>(null);
            SharedPreferences prefs = AutoCheckin.prefs(activity);
            long now = System.currentTimeMillis();
            prefs.edit().putLong(KEY_ANY_CREATED_AT, now).apply();
            Log.i(TAG, "WAKE existing screen brought back with the marker (" + when + "), taking it");
            take(activity, prefs, now, "brought back with the marker, " + when);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "WAKE_SHOWN_ERR " + t);
            return false;
        }
    }

    /**
     * Makes the screen the check-in screen: helper window gone, see-through while the phone is in
     * use. A screen that comes up while the phone is locked or the display is off is closed again
     * at once (nothing checks in over the lock screen any more).
     */
    private static void take(Activity activity, SharedPreferences prefs, long now, String how) {
        try {
            sWoken = new WeakReference<>(activity);
            removeHelper();
            if (lockedOrDark(activity)) {
                Log.i(TAG, "WAKE locked at take, closing (" + how + ", " + phoneState(activity) + ")");
                AutoCheckin.recordBackground(activity, "ROX's screen came up while the phone was locked or "
                    + "the screen was off; closed it again without checking in");
                // Sets the closing mark right away, so AutoCheckin starts no session on it.
                close(activity, 0L);
            } else {
                // r32: with the phone in use ROX came up over what the user was doing and they had
                // to go back by hand. Make it see-through and let touches pass to the app behind.
                boolean hidden = hide(activity);
                Log.i(TAG, "WAKE screen opened by the background run (" + how + "), phone in use, "
                    + (hidden ? "made see-through" : "couldn't make it see-through, shown as is"));
                capOpen(activity);
            }
        } catch (Throwable t) {
            Log.w(TAG, "WAKE_TAKE_ERR " + t);
        } finally {
            prefs.edit().putLong(KEY_OPENED_AT, now).commit();
        }
    }

    /**
     * Backstop: the check-in session closes the screen as soon as its result is known (after 4 s
     * on a success, so the request can finish, 1.5 s otherwise) and times out after 120 s. Should
     * no session ever start on it, the screen is closed here at the latest.
     */
    private static void capOpen(final Activity activity) {
        sHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!woken(activity) || closing(activity)) return;
                Log.w(TAG, "WAKE check-in screen still open after " + (MAX_OPEN_MS / 1000L) + "s, closing it");
                close(activity, 0L);
            }
        }, MAX_OPEN_MS);
    }

    static boolean woken(Activity activity) {
        return activity != null && sWoken.get() == activity;
    }

    /** True while a screen the background run opened is about to close; it starts no new session. */
    static boolean closing(Activity activity) {
        return activity != null && sClosing.get() == activity;
    }

    /** Closes a screen the background run opened, after the given delay. */
    static void close(final Activity activity, long delayMs) {
        if (!woken(activity) || closing(activity)) return;
        sClosing = new WeakReference<>(activity);
        sHandler.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (sWoken.get() == activity) sWoken = new WeakReference<>(null);
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    boolean wasHidden = sHidden.get() == activity;
                    activity.finishAndRemoveTask();
                    if (wasHidden) {
                        sHidden = new WeakReference<>(null);
                        noAnimation(activity);
                    }
                    Log.i(TAG, "WAKE screen closed" + (wasHidden ? " (it was see-through)" : ""));
                } catch (Throwable t) {
                    Log.w(TAG, "WAKE_CLOSE_ERR " + t);
                } finally {
                    // The try opened at the unlock is over, however it went.
                    UnlockService.screenClosed();
                }
            }
        }, delayMs);
    }

    /**
     * Makes the check-in screen fully transparent and lets touches go to the app behind it. Flutter
     * keeps drawing and its semantics tree keeps working, because the screen is still resumed and
     * on screen; window alpha does not change that. The alpha must be exactly 0: since Android 12 a
     * touch that passes through another app's window is dropped unless that window is
     * FLAG_NOT_TOUCHABLE with alpha 0 (r37 used 0.01, which swallowed the user's taps for the whole
     * session). Only when Android agrees to make the screen translucent; otherwise it stays as in r32.
     */
    private static boolean hide(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT < 30) return false;
            if (!activity.setTranslucent(true)) {
                Log.i(TAG, "WAKE see-through refused by Android");
                return false;
            }
            Window window = activity.getWindow();
            window.setBackgroundDrawable(new ColorDrawable(0));
            WindowManager.LayoutParams params = window.getAttributes();
            params.alpha = 0f;
            params.dimAmount = 0f;
            window.setAttributes(params);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            sHidden = new WeakReference<>(activity);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "WAKE_HIDE_ERR " + t);
            return false;
        }
    }

    static boolean hidden(Activity activity) {
        return activity != null && sHidden.get() == activity;
    }

    /**
     * A see-through check-in screen that goes away before it is done (the user switched app, a
     * call came in) is closed, so ROX is never left invisible. It is never made visible or
     * touchable again: its alpha stays 0 until it is gone. Normally the session's end has already
     * closed it by the time this runs (the pause ends the session first).
     */
    static void reveal(Activity activity) {
        if (!hidden(activity)) return;
        // Closing after the check-in also pauses it: nothing more to do.
        if (activity.isFinishing() || closing(activity)) return;
        Log.i(TAG, "WAKE see-through screen went away early with no session closing it, closing it");
        if (woken(activity)) {
            close(activity, 0L);
            return;
        }
        sHidden = new WeakReference<>(null);
        try {
            activity.finishAndRemoveTask();
            noAnimation(activity);
        } catch (Throwable t) {
            Log.w(TAG, "WAKE_REVEAL_ERR " + t);
        }
    }

    @SuppressWarnings("deprecation")
    private static void noAnimation(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
            }
            activity.overridePendingTransition(0, 0);
        } catch (Throwable t) {
            Log.w(TAG, "WAKE_NOANIM_ERR " + t);
        }
    }

    /**
     * On an app open: when Android allows no way to open the screen from the background, ask (at
     * most every three days) for "Appear on top", which is what lets the check-in run unattended.
     */
    static void askIfNeeded(Context context) {
        try {
            Context app = context.getApplicationContext();
            String askKey = KEY_ASKED_AT;
            if (route(app) != null) {
                NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager != null) manager.cancel(ASK_ID);
                return;
            }
            SharedPreferences prefs = AutoCheckin.prefs(app);
            long now = System.currentTimeMillis();
            if (now - prefs.getLong(askKey, 0L) < ASK_EVERY_MS) return;
            NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null || !manager.areNotificationsEnabled()) return;
            Intent settings;
            if (Build.VERSION.SDK_INT >= 23) {
                settings = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + app.getPackageName()));
            } else {
                settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + app.getPackageName()));
            }
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (manager.getNotificationChannel("proxma_rox_checkin") == null) {
                NotificationChannel channel = new NotificationChannel("proxma_rox_checkin", "Daily check-in (patch)",
                    NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Result of the patched background daily check-in");
                manager.createNotificationChannel(channel);
            }
            String text = "To check in when you unlock your phone, ROX needs to open itself for a moment. "
                + "Tap here and turn on \"Appear on top\" (\"Display over other apps\") for ROX.";
            Notification notice = new Notification.Builder(app, "proxma_rox_checkin")
                .setSmallIcon(app.getApplicationInfo().icon != 0
                    ? app.getApplicationInfo().icon : android.R.drawable.ic_popup_reminder)
                .setContentTitle("ROX patch: one setting needed")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(PendingIntent.getActivity(app, REQUEST_ASK, settings,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true)
                .build();
            manager.notify(ASK_ID, notice);
            prefs.edit().putLong(askKey, now).apply();
            Log.i(TAG, "WAKE asked for appear on top");
        } catch (Throwable t) {
            Log.w(TAG, "WAKE_ASK_ERR " + t);
        }
    }
}
