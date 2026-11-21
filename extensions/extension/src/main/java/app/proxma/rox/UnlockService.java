package app.proxma.rox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Does the daily check-in at the first unlock of the day. Nothing checks in while the phone is
 * locked or the screen is off any more, and an unlock broadcast only reaches an app that is
 * running and not cached: Android 14+ holds it back from a background app, and the idle close
 * ends ROX a few minutes after each run. So the wake-up alarm shortly after midnight (Pakistan
 * time) starts this small foreground service, which an exact alarm may do, and it waits for the
 * unlock with a silent notice up.
 *
 * On the first unlock of the day, if today's check-in isn't done, it opens ROX's own screen
 * see-through through Appear on top (WakeScreen), where AutoCheckin presses Check-in and closes the
 * screen again; the user taps nothing. One try per day: the day is marked before the screen opens,
 * so a second unlock, or a try that didn't work, never opens ROX again that day. The service stops
 * when that session ends, 90 seconds after the unlock at the latest, or at the first screen-on,
 * unlock or timeout after midnight (a delayed handler would stall in deep sleep); the next alarm
 * starts it again for the new day.
 */
public final class UnlockService extends Service {

    private static final String TAG = "PROXMA_ROX";
    static final String KEY_UNLOCK_RUN_DAY = "unlock_run_day";
    private static final String EXTRA_WHY = "why";
    private static final String CHANNEL = "proxma_rox_unlock";
    private static final int NOTICE_ID = 0x524f5808;
    // RECEIVER_NOT_EXPORTED: only the system's unlock broadcast reaches the receiver.
    private static final int RECEIVER_NOT_EXPORTED = 4;
    // Right after the unlock the launcher is still animating in; let it settle first.
    private static final long SETTLE_MS = 2_000L;
    private static final long OPEN_POLL_MS = 1_000L;
    private static final long OPEN_WAIT_MS = 20_000L;
    private static final long SAFETY_MS = 90_000L;

    // The running service, or null. Main thread only.
    private static UnlockService sRunning;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver receiver;
    private int lastStartId = 0;
    // Between taking an unlock for today's check-in and the end of that try.
    private boolean attempting = false;
    private boolean stopping = false;
    private long askedAt = 0L;
    private boolean opened = false;
    // The ROX day (Pakistan time) this service was started for.
    private String serviceDay = "";

    /** From the wake-up alarm or the boot broadcast. False when Android refuses the start. */
    static boolean start(Context context, String why) {
        try {
            Context app = context.getApplicationContext();
            Intent intent = new Intent(app, UnlockService.class).putExtra(EXTRA_WHY, why);
            app.startForegroundService(intent);
            Log.i(TAG, "UNLOCK service start asked (" + why + ")");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "UNLOCK start refused " + t);
            return false;
        }
    }

    /** True while the service runs in this process: it, not the job, handles today. */
    static boolean active() {
        return sRunning != null;
    }

    /** True when today's first unlock was already taken for the check-in. */
    static boolean ranToday(Context context) {
        return AutoCheckin.today().equals(AutoCheckin.prefs(context).getString(KEY_UNLOCK_RUN_DAY, ""));
    }

    /** From AutoCheckin when today is marked done: a service still waiting has nothing left to do. */
    static void checkinDone() {
        UnlockService service = sRunning;
        if (service != null && !service.attempting) service.finish("already done today, stopping");
    }

    /** From WakeScreen once the check-in screen has been closed: the try is over. */
    static void screenClosed() {
        UnlockService service = sRunning;
        if (service != null && service.attempting) {
            service.finish("session finished (done today: " + AutoCheckin.doneToday(service) + "), stopping");
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        sRunning = this;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        final Context app = getApplicationContext();
        String why = intent != null ? intent.getStringExtra(EXTRA_WHY) : null;
        if (why == null) why = "restarted by Android";
        String refused = goForeground(app);
        if (refused != null) {
            // Fall back to the job, which opens ROX only while the phone is unlocked. Planned
            // first: JobScheduler keeps it even if Android ends ROX's process below.
            sRunning = null;
            stopping = true;
            if (intent != null) {
                CheckinJobService.runNow(app);
                // Started with startForegroundService (the alarm or the boot broadcast): stopping
                // a service that never called startForeground makes Android crash ROX at once
                // ("did not then call startForeground"). Leave it started; it does nothing, and
                // Android ends it when its foreground time limit runs out.
                Log.w(TAG, "UNLOCK couldn't go foreground (" + refused + "), job planned instead; "
                    + "not stopping the service, which would crash ROX");
            } else {
                // A restart by Android after it ended the process: no startForeground is owed,
                // so stopping is safe.
                CheckinJobService.planNext(app);
                Log.w(TAG, "UNLOCK couldn't go foreground on a restart (" + refused + "), job planned instead, stopping");
                stopSelf(startId);
            }
            try {
                AutoCheckin.recordBackground(app, "the check-in at unlock couldn't start (" + refused
                    + "); the background job takes over and opens ROX only while the phone is unlocked");
            } catch (Throwable t) {
                Log.w(TAG, "UNLOCK_RECORD_ERR " + t);
            }
            return START_NOT_STICKY;
        }
        if (attempting) {
            Log.i(TAG, "UNLOCK service start (" + why + ") while today's try is under way, nothing to do");
            return START_STICKY;
        }
        stopping = false;
        sRunning = this;
        handler.removeCallbacksAndMessages(null);
        if (AutoCheckin.doneToday(app)) {
            finish("already done today, stopping");
            return START_NOT_STICKY;
        }
        if (AutoCheckin.signedOutToday(app)) {
            finish("signed out today, stopping");
            return START_NOT_STICKY;
        }
        if (ranToday(app)) {
            finish("already ran today, stopping");
            return START_NOT_STICKY;
        }
        serviceDay = AutoCheckin.today();
        register(app);
        long dayEnd = untilMidnight();
        // This service handles today now: move the planned job and the alarm to tomorrow.
        CheckinJobService.planNext(app);
        if (!WakeScreen.lockedOrDark(app)) {
            // Started while the phone is in use (alarm with the phone unlocked, or the boot
            // broadcast, which comes after the first unlock): the user is already here.
            Log.i(TAG, "UNLOCK service started (" + why + "), phone already unlocked, opening in "
                + SETTLE_MS + " ms");
            present(app);
        } else {
            Log.i(TAG, "UNLOCK service started (" + why + "), waiting for the first unlock of "
                + AutoCheckin.today() + ", day ends in " + (dayEnd / 60_000L) + " min");
        }
        return START_STICKY;
    }

    private void register(final Context app) {
        if (receiver != null) return;
        try {
            BroadcastReceiver unlock = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    try {
                        if (stopping) return;
                        boolean screenOn = intent != null && Intent.ACTION_SCREEN_ON.equals(intent.getAction());
                        if (dayRolled(screenOn ? "screen on" : "unlock")) return;
                        if (screenOn) return;
                        if (attempting || ranToday(app)) {
                            Log.i(TAG, "UNLOCK already ran today, ignoring");
                            return;
                        }
                        Log.i(TAG, "UNLOCK user present, opening in " + SETTLE_MS + " ms");
                        present(app);
                    } catch (Throwable t) {
                        Log.w(TAG, "UNLOCK_PRESENT_ERR " + t);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
            // Screen-on only to notice that the day has ended while the phone slept.
            filter.addAction(Intent.ACTION_SCREEN_ON);
            app.registerReceiver(unlock, filter, RECEIVER_NOT_EXPORTED);
            receiver = unlock;
        } catch (Throwable t) {
            Log.w(TAG, "UNLOCK_REG_ERR " + t);
        }
    }

    /** Takes this unlock for today's check-in: marks the day first, then opens ROX after a moment. */
    private void present(final Context app) {
        if (attempting || ranToday(app)) {
            Log.i(TAG, "UNLOCK already ran today, ignoring");
            return;
        }
        final String day = AutoCheckin.today();
        // Written before anything opens, so this is the day's only try whatever happens next.
        AutoCheckin.prefs(app).edit().putString(KEY_UNLOCK_RUN_DAY, day).commit();
        attempting = true;
        opened = false;
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (dayRolled("timeout")) return;
                finish("timed out " + (SAFETY_MS / 1000L) + "s after the unlock, stopping");
            }
        }, SAFETY_MS);
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                open(app, day);
            }
        }, SETTLE_MS);
    }

    private void open(final Context app, String day) {
        try {
            if (stopping) return;
            if (AutoCheckin.doneToday(app)) {
                finish("already done today, stopping");
                return;
            }
            if (WakeScreen.lockedOrDark(app)) {
                // Locked again within the settle time: nothing opened, so give the day back.
                SharedPreferences prefs = AutoCheckin.prefs(app);
                if (day.equals(prefs.getString(KEY_UNLOCK_RUN_DAY, ""))) {
                    prefs.edit().remove(KEY_UNLOCK_RUN_DAY).commit();
                }
                attempting = false;
                handler.removeCallbacksAndMessages(null);
                Log.i(TAG, "UNLOCK phone locked again before opening, waiting for the next unlock");
                return;
            }
            if (!Settings.canDrawOverlays(app)) {
                Log.i(TAG, "UNLOCK appear-on-top off, asking for it");
                AutoCheckin.recordBackground(app, "you unlocked the phone, but Appear on top is off for ROX, "
                    + "so it couldn't open itself to check in");
                Notices.needsYou(app, "Turn on Appear on top for ROX.");
                finish("not checking in today without Appear on top, stopping");
                return;
            }
            askedAt = System.currentTimeMillis();
            if (!AutoCheckin.startBackground(app, null)) {
                finish("check-in not started (reason in the BG line above), stopping");
                return;
            }
            watchOpen(app, SystemClock.uptimeMillis() + OPEN_WAIT_MS);
        } catch (Throwable t) {
            Log.w(TAG, "UNLOCK_OPEN_ERR " + t);
            finish("open failed, stopping");
        }
    }

    /** Looks every second whether ROX's screen opened; after 20 s without it, says so and stops. */
    private void watchOpen(final Context app, final long until) {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (stopping || !attempting) return;
                if (WakeScreen.openedSince(app, askedAt)) {
                    if (!opened) {
                        opened = true;
                        Log.i(TAG, "UNLOCK screen opened");
                    }
                    // From here the session's end (screenClosed) or the safety timeout stops it.
                    return;
                }
                if (SystemClock.uptimeMillis() >= until) {
                    Log.w(TAG, "UNLOCK screen didn't open in " + (OPEN_WAIT_MS / 1000L) + "s");
                    Notices.needsYou(app, "ROX's screen didn't open after you unlocked the phone.");
                    finish("stopping after the screen didn't open");
                    return;
                }
                handler.postDelayed(this, OPEN_POLL_MS);
            }
        }, OPEN_POLL_MS);
    }

    /**
     * True, and the service stopped, when the ROX day has changed since it started. Checked on each
     * screen-on, unlock and timeout: a handler set for midnight stalls while the phone is in deep
     * sleep. Today's alarm, already set for 00:01, starts the service again for the new day.
     */
    private boolean dayRolled(String event) {
        String today = AutoCheckin.today();
        if (serviceDay.isEmpty() || serviceDay.equals(today)) return false;
        finish("day ended (" + serviceDay + ", now " + today + ", noticed at " + event + "), stopping");
        return true;
    }

    private void finish(String why) {
        if (stopping) return;
        stopping = true;
        attempting = false;
        Log.i(TAG, "UNLOCK " + why);
        handler.removeCallbacksAndMessages(null);
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable t) {
            Log.w(TAG, "UNLOCK_STOPFG_ERR " + t);
        }
        // Only the latest start: an alarm that started it again meanwhile keeps it running.
        stopSelf(lastStartId);
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (receiver != null) {
            try {
                getApplicationContext().unregisterReceiver(receiver);
            } catch (Throwable t) {
                Log.w(TAG, "UNLOCK_UNREG_ERR " + t);
            }
            receiver = null;
        }
        if (sRunning == this) sRunning = null;
        Context app = getApplicationContext();
        try {
            // Normally the alarm for the next day start is already set; if not, plan it now.
            long alarmAt = AutoCheckin.prefs(app).getLong(CheckinAlarm.KEY_ALARM_SET_AT, 0L);
            if (alarmAt <= System.currentTimeMillis()) CheckinJobService.planNext(app);
        } catch (Throwable t) {
            Log.w(TAG, "UNLOCK_PLAN_ERR " + t);
        }
        Log.i(TAG, "UNLOCK service stopped");
        super.onDestroy();
    }

    /** Milliseconds until the next midnight, Pakistan time (the ROX day boundary). */
    static long untilMidnight() {
        Calendar next = Calendar.getInstance(TimeZone.getTimeZone("Asia/Karachi"));
        next.add(Calendar.DAY_OF_YEAR, 1);
        next.set(Calendar.HOUR_OF_DAY, 0);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        return Math.max(1_000L, next.getTimeInMillis() - System.currentTimeMillis());
    }

    /**
     * Puts the service in the foreground; null when that worked, else why not. Tries the
     * specialUse type, then the manifest's type, then a plain notice, since a service started
     * with startForegroundService that never gets here is ended by Android with a crash.
     */
    private String goForeground(Context app) {
        StringBuilder why = new StringBuilder();
        Notification notice = null;
        try {
            notice = notice(app);
        } catch (Throwable t) {
            why.append("notice: ").append(t.getClass().getSimpleName()).append("; ");
            Log.w(TAG, "UNLOCK_NOTICE_ERR " + t);
        }
        if (notice != null && Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(NOTICE_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                return null;
            } catch (Throwable t) {
                why.append("special use: ").append(t.getClass().getSimpleName()).append("; ");
                Log.w(TAG, "UNLOCK_FG_SPECIAL_ERR " + t);
            }
        }
        if (notice != null) {
            try {
                startForeground(NOTICE_ID, notice);
                return null;
            } catch (Throwable t) {
                why.append("default: ").append(t.getClass().getSimpleName()).append("; ");
                Log.w(TAG, "UNLOCK_FG_ERR " + t);
            }
        }
        try {
            NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Check-in at unlock (patch)",
                    NotificationManager.IMPORTANCE_LOW));
            }
            startForeground(NOTICE_ID, new Notification.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("ROX daily check-in")
                .build());
            Log.i(TAG, "UNLOCK foreground with the plain notice");
            return null;
        } catch (Throwable t) {
            why.append("plain: ").append(t.getClass().getSimpleName());
            Log.w(TAG, "UNLOCK_FG_PLAIN_ERR " + t);
        }
        return why.toString();
    }

    private static Notification notice(Context app) {
        NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && manager.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Check-in at unlock (patch)",
                NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shown while ROX waits for you to unlock your phone to do the daily check-in");
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        return new Notification.Builder(app, CHANNEL)
            .setSmallIcon(Notices.smallIcon(app))
            .setContentTitle("ROX daily check-in")
            .setContentText("ROX will check in when you unlock your phone")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build();
    }
}
