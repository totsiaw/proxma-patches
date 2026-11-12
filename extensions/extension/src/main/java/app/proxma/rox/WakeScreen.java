package app.proxma.rox;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;

import java.lang.ref.WeakReference;

/**
 * The off-screen check-in never got past a blank screen on the reporter's phone, while the same
 * check-in always works once ROX's own screen is open. So the background run now opens ROX's real
 * screen instead: shown over the lock screen with the display turned on, exactly as an alarm clock
 * does, it runs the normal check-in (AutoCheckin) and closes itself again, without unlocking the
 * phone.
 *
 * Android only lets an app open a screen from the background in a few cases. Two are used here:
 * "Appear on top" (SYSTEM_ALERT_WINDOW), which the user grants once in Settings, and a full-screen
 * notification (USE_FULL_SCREEN_INTENT), which opens the screen straight away while the phone is
 * locked or asleep and otherwise shows as a heads-up notice to tap. When neither is allowed the old
 * off-screen run is used and ROX asks once, on an app open, for "Appear on top".
 */
final class WakeScreen {

    private static final String TAG = "PROXMA_ROX";
    static final String EXTRA = "proxma_rox_wake";
    private static final String CHANNEL = "proxma_rox_wake";
    private static final int NOTICE_ID = 0x524f5804;
    private static final int ASK_ID = 0x524f5805;
    private static final int REQUEST_OPEN = 0x524f5806;
    private static final int REQUEST_ASK = 0x524f5807;
    static final String KEY_ASKED_AT = "wake_asked_at";
    static final String KEY_OPEN_ASKED_AT = "wake_open_asked_at";
    static final String KEY_OPENED_AT = "wake_opened_at";
    private static final String ROUTE_OVERLAY = "appear on top";
    private static final String ROUTE_NOTICE = "full-screen notice";
    // If a direct start was blocked after all, the full-screen notice follows this much later.
    static final long BACKUP_NOTICE_MS = 20_000L;
    private static final long ASK_EVERY_MS = 3L * 24L * 60L * 60L * 1000L;

    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> sWoken = new WeakReference<>(null);
    private static WeakReference<Activity> sClosing = new WeakReference<>(null);

    private WakeScreen() {}

    /** The way ROX's screen can be opened from the background right now, or null for none. */
    static String route(Context context) {
        if (canAppearOnTop(context)) return ROUTE_OVERLAY;
        if (canFullScreen(context)) return ROUTE_NOTICE;
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

    private static boolean canFullScreen(Context context) {
        try {
            if (context.checkSelfPermission("android.permission.USE_FULL_SCREEN_INTENT")
                != PackageManager.PERMISSION_GRANTED) return false;
            if (Build.VERSION.SDK_INT < 34) return true;
            NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            return manager != null && manager.areNotificationsEnabled() && manager.canUseFullScreenIntent();
        } catch (Throwable t) {
            Log.w(TAG, "WAKE full-screen check failed " + t);
            return false;
        }
    }

    /**
     * From the background run: opens ROX's screen for the check-in. Returns the route used, or
     * null when Android allows none, in which case nothing was done.
     */
    static String open(Context context) {
        final Context app = context.getApplicationContext();
        final Intent intent = launchIntent(app);
        if (intent == null) {
            Log.w(TAG, "WAKE no launch intent");
            return null;
        }
        final long at = System.currentTimeMillis();
        String route = null;
        if (canAppearOnTop(app)) {
            try {
                app.startActivity(intent);
                route = ROUTE_OVERLAY;
                Log.i(TAG, "WAKE screen started directly (appear on top)");
            } catch (Throwable t) {
                Log.w(TAG, "WAKE direct start failed " + t);
            }
        }
        if (canFullScreen(app)) {
            if (route == null) {
                postFullScreen(app, intent);
                route = ROUTE_NOTICE;
            } else {
                // Android can drop a background start without saying so; the notice backs it up.
                sHandler.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (openedSince(app, at)) return;
                        Log.w(TAG, "WAKE screen didn't open after the direct start, posting the notice");
                        postFullScreen(app, intent);
                    }
                }, BACKUP_NOTICE_MS);
            }
        }
        if (route != null) {
            AutoCheckin.prefs(app).edit().putLong(KEY_OPEN_ASKED_AT, at).commit();
        } else {
            Log.i(TAG, "WAKE no way to open the screen from the background");
        }
        return route;
    }

    static boolean openedSince(Context context, long at) {
        return AutoCheckin.prefs(context).getLong(KEY_OPENED_AT, 0L) >= at;
    }

    private static Intent launchIntent(Context app) {
        Intent intent = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        if (intent == null) return null;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(EXTRA, true);
        return intent;
    }

    private static void postFullScreen(Context app, Intent intent) {
        try {
            NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            if (manager.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL, "Daily check-in wake-up (patch)",
                    NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Opens ROX briefly so the patched daily check-in can run");
                channel.setSound(null, null);
                channel.enableVibration(false);
                manager.createNotificationChannel(channel);
            }
            PendingIntent open = PendingIntent.getActivity(app, REQUEST_OPEN, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notice = new Notification.Builder(app, CHANNEL)
                .setSmallIcon(app.getApplicationInfo().icon != 0
                    ? app.getApplicationInfo().icon : android.R.drawable.ic_popup_reminder)
                .setContentTitle("ROX daily check-in")
                .setContentText("Opening ROX for a moment to check in. Tap if it didn't open.")
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(open)
                .setFullScreenIntent(open, true)
                .setAutoCancel(true)
                .setTimeoutAfter(30L * 60L * 1000L)
                .build();
            manager.notify(NOTICE_ID, notice);
            Log.i(TAG, "WAKE full-screen notice posted");
        } catch (Throwable t) {
            Log.w(TAG, "WAKE notice failed " + t);
        }
    }

    /** From MainActivity.onCreate: a screen opened by the background run shows over the lock screen. */
    static boolean onCreate(Activity activity) {
        try {
            Intent intent = activity.getIntent();
            if (intent == null || !intent.getBooleanExtra(EXTRA, false)) return false;
            sWoken = new WeakReference<>(activity);
            if (Build.VERSION.SDK_INT >= 27) {
                activity.setShowWhenLocked(true);
                activity.setTurnScreenOn(true);
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            }
            cancelNotice(activity);
            AutoCheckin.prefs(activity).edit().putLong(KEY_OPENED_AT, System.currentTimeMillis()).commit();
            Log.i(TAG, "WAKE screen opened by the background run, shown over the lock screen");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "WAKE_CREATE_ERR " + t);
            return false;
        }
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
                    activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    if (Build.VERSION.SDK_INT >= 27) {
                        activity.setTurnScreenOn(false);
                        activity.setShowWhenLocked(false);
                    }
                    activity.finishAndRemoveTask();
                    Log.i(TAG, "WAKE screen closed");
                } catch (Throwable t) {
                    Log.w(TAG, "WAKE_CLOSE_ERR " + t);
                }
            }
        }, delayMs);
    }

    private static void cancelNotice(Context context) {
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.cancel(NOTICE_ID);
        } catch (Throwable t) {
            Log.w(TAG, "WAKE_CANCEL_ERR " + t);
        }
    }

    /**
     * On an app open: when Android allows no way to open the screen from the background, ask (at
     * most every three days) for "Appear on top", which is what lets the check-in run unattended.
     */
    static void askIfNeeded(Context context) {
        try {
            Context app = context.getApplicationContext();
            if (route(app) != null) {
                NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager != null) manager.cancel(ASK_ID);
                return;
            }
            SharedPreferences prefs = AutoCheckin.prefs(app);
            long now = System.currentTimeMillis();
            if (now - prefs.getLong(KEY_ASKED_AT, 0L) < ASK_EVERY_MS) return;
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
            String text = "To check in while you're away, ROX needs to open itself for a moment. "
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
            prefs.edit().putLong(KEY_ASKED_AT, now).apply();
            Log.i(TAG, "WAKE asked for appear on top");
        } catch (Throwable t) {
            Log.w(TAG, "WAKE_ASK_ERR " + t);
        }
    }
}
