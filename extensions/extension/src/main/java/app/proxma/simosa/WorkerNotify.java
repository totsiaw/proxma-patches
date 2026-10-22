package app.proxma.simosa;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Notification and day-tracking for the background worker (patcher version).
 * Replaces the smali {@code morphe/Notify} class used in the manual build.
 *
 * <p>Channel: {@code proxma_auto} at IMPORTANCE_HIGH (heads-up banner).
 */
public final class WorkerNotify {

    private static final String TAG = "PROXMA_CLAIM";
    private static final String PREFS_NAME = "proxma_claim";
    private static final String CHANNEL_ID = "proxma_auto";
    private static final String CHANNEL_NAME = "Auto Status";
    private static final int NOTIF_ID = 1003;

    private WorkerNotify() {}

    /** Post a BigTextStyle notification showing the worker result. */
    public static void notifyStatus(Context context, String claimStatus, String nextAction, String title) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                if (context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
                    return;
                }
            }

            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Background worker status");
                nm.createNotificationChannel(ch);
            }

            int icon = context.getApplicationInfo().icon;
            if (icon == 0) return;

            String session = BackgroundCheckin.getSession();
            String checkin = BackgroundCheckin.getCheckin();
            String body = "Session: " + session
                + "\nClaim: " + claimStatus
                + "\nCheckin: " + checkin
                + "\nNext: " + nextAction;

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= 26) {
                builder = new Notification.Builder(context, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(context);
            }

            builder.setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true);

            if (Build.VERSION.SDK_INT >= 26) {
                builder.setTimeoutAfter(72 * 60 * 60 * 1000L);
            }

            nm.notify(NOTIF_ID, builder.build());
        } catch (Throwable t) {
            Log.w(TAG, "notifyStatus failed: " + t);
        }
    }

    /** Day key as YYYYDDD in Asia/Karachi timezone. */
    public static int dayKey() {
        return BackgroundCheckin.dayKey();
    }

    public static boolean isClaimedToday(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return prefs.getInt("claimed_day", 0) == dayKey();
    }

    public static void markClaimed(Context context) {
        context.getSharedPreferences(PREFS_NAME, 0)
            .edit().putInt("claimed_day", dayKey()).apply();
    }

    public static int bumpAttempt(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        int day = dayKey();
        int attDay = prefs.getInt("att_day", 0);
        int n;
        if (attDay != day) {
            n = 1;
            prefs.edit().putInt("att_day", day).putInt("att_n", 1).apply();
        } else {
            n = prefs.getInt("att_n", 0) + 1;
            prefs.edit().putInt("att_n", n).apply();
        }
        return n;
    }

    public static void log(String msg) {
        Log.i(TAG, msg);
    }
}
