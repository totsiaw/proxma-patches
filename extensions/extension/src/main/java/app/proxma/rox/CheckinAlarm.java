package app.proxma.rox;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * A job planned for shortly after midnight is held back by Doze while the phone lies unused, often
 * until the morning or later, so the background check-in ran late or not at all. ROX already holds
 * USE_EXACT_ALARM, so the same planned time is also set as an exact alarm that fires in Doze. When
 * it fires and today's check-in is still to do, it starts the unlock service (UnlockService), which
 * checks in at the first unlock. If Android refuses that, it asks for an expedited job instead,
 * which does the same check-in as the planned one (CheckinJobService) while the phone is unlocked.
 */
public final class CheckinAlarm extends BroadcastReceiver {

    private static final String TAG = "PROXMA_ROX";
    private static final int REQUEST_CODE = 0x52_4f_58;
    static final String KEY_ALARM_SET_AT = "bg_alarm_set_for";
    static final String KEY_ALARM_FIRED_AT = "bg_alarm_fired_at";
    static final String KEY_ALARM_KIND = "bg_alarm_kind";

    @Override public void onReceive(Context context, Intent intent) {
        try {
            Context app = context.getApplicationContext();
            if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                onBoot(app);
                return;
            }
            long now = System.currentTimeMillis();
            AutoCheckin.prefs(app).edit().putLong(KEY_ALARM_FIRED_AT, now).commit();
            Log.i(TAG, "ALARM fired at " + AutoCheckin.clock(now));
            if (AutoCheckin.doneToday(app) || AutoCheckin.signedOutToday(app)) {
                Log.i(TAG, "ALARM nothing to do today (done or signed out), planning the next run");
                CheckinJobService.planNext(app);
                return;
            }
            if (UnlockService.ranToday(app)) {
                Log.i(TAG, "ALARM today's first unlock was already used, planning the next run");
                CheckinJobService.planNext(app);
                return;
            }
            // An exact alarm may start a foreground service; it waits for the first unlock.
            if (!UnlockService.start(app, "wake-up alarm")) {
                // Refused (no exact alarm access, battery restricted): the job opens ROX only
                // while the phone is unlocked, otherwise it looks again later.
                Log.i(TAG, "ALARM unlock service refused, falling back to the job");
                CheckinJobService.runNow(app);
            }
        } catch (Throwable t) {
            Log.w(TAG, "ALARM_ERR " + t);
        }
    }

    /**
     * After a reboot: alarms don't survive one, so set the planned one again; and when today's
     * run is already due (the alarm fired today, or its time passed while the phone was off) and
     * today's check-in isn't done, start the unlock service. The boot broadcast comes after the
     * first unlock, so the service then checks in straight away.
     */
    private static void onBoot(Context app) {
        SharedPreferences prefs = AutoCheckin.prefs(app);
        long now = System.currentTimeMillis();
        long planned = prefs.getLong(CheckinJobService.KEY_PLANNED_AT, 0L);
        Log.i(TAG, "ALARM boot, planned run " + (planned > 0 ? AutoCheckin.clock(planned) : "none"));
        if (planned <= 0) return;
        if (planned > now) set(app, planned);
        long fired = prefs.getLong(KEY_ALARM_FIRED_AT, 0L);
        boolean firedToday = fired > 0 && AutoCheckin.today().equals(AutoCheckin.dayOf(fired));
        if (!firedToday && planned > now) {
            Log.i(TAG, "ALARM boot, today's run not due yet");
            return;
        }
        if (AutoCheckin.doneToday(app) || AutoCheckin.signedOutToday(app) || UnlockService.ranToday(app)) {
            Log.i(TAG, "ALARM boot, nothing to do today");
            return;
        }
        if (!UnlockService.start(app, "boot")) {
            Log.i(TAG, "ALARM boot, unlock service refused, falling back to the job");
            CheckinJobService.planNext(app);
        }
    }

    /** Sets (or moves) the wake-up alarm to the given wall-clock time. */
    static void set(Context context, long at) {
        try {
            AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarms == null) {
                Log.w(TAG, "ALARM no alarm manager");
                return;
            }
            PendingIntent pending = pendingIntent(context);
            String kind;
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
                kind = "exact";
            } else {
                // Without exact alarm access an inexact one still fires in Doze, a little later.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
                kind = "inexact";
            }
            AutoCheckin.prefs(context).edit()
                .putLong(KEY_ALARM_SET_AT, at)
                .putString(KEY_ALARM_KIND, kind)
                .apply();
            Log.i(TAG, "ALARM " + kind + " set for " + AutoCheckin.clock(at));
        } catch (Throwable t) {
            Log.w(TAG, "ALARM_SET_ERR " + t);
        }
    }

    /**
     * The time the wake-up alarm is set for, while it is still waiting to fire; else 0. Not fired
     * since it was set, and still registered (force-stop and reboots drop it).
     */
    static long pendingAt(Context context) {
        try {
            SharedPreferences prefs = AutoCheckin.prefs(context);
            long setFor = prefs.getLong(KEY_ALARM_SET_AT, 0L);
            if (setFor <= 0 || prefs.getLong(KEY_ALARM_FIRED_AT, 0L) >= setFor) return 0L;
            Intent intent = new Intent(context, CheckinAlarm.class).setPackage(context.getPackageName());
            PendingIntent existing = PendingIntent.getBroadcast(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            return existing != null ? setFor : 0L;
        } catch (Throwable t) {
            Log.w(TAG, "ALARM_PENDING_ERR " + t);
            return 0L;
        }
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, CheckinAlarm.class).setPackage(context.getPackageName());
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
