package app.proxma.rox;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

/**
 * ROX keeps working after it leaves the screen: its Dart timers, the ad and analytics SDKs and the
 * web views it loaded stay alive for as long as Android keeps the process, which with battery set to
 * Unrestricted can be hours. Blocking the foreground service was not enough on its own, so once no
 * ROX screen has been visible for a few minutes and nothing is playing, this closes the app's process.
 * Push notifications still arrive, because the system starts the app again to deliver them. Opening
 * ROX afterwards is a normal fresh start.
 */
public final class IdleClose {

    private static final String TAG = "PROXMA_ROX";
    private static final long IDLE_MS = 180_000L;
    private static final long AUDIO_RECHECK_MS = 60_000L;

    private static volatile boolean sRegistered = false;
    private static int sStarted = 0;
    private static Context sContext;
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private static final Runnable CHECK = new Runnable() {
        @Override public void run() {
            try {
                check();
            } catch (Throwable t) {
                Log.w(TAG, "IDLE_ERR " + t);
            }
        }
    };

    private IdleClose() {}

    public static void onActivityCreate(Activity activity) {
        try {
            if (activity == null || sRegistered) return;
            sRegistered = true;
            sContext = activity.getApplicationContext();
            // onActivityStarted for this activity has not run yet, so the counter starts at zero.
            activity.getApplication().registerActivityLifecycleCallbacks(new Callbacks());
            Log.i(TAG, "IDLE registered");
        } catch (Throwable t) {
            Log.w(TAG, "IDLE_ENTRY_ERR " + t);
        }
    }

    private static void check() {
        if (sStarted > 0) {
            Log.i(TAG, "IDLE check skipped, a screen is visible again");
            return;
        }
        if (AutoCheckin.backgroundRunning()) {
            Log.i(TAG, "IDLE background check-in running, checking again later");
            sHandler.postDelayed(CHECK, AUDIO_RECHECK_MS);
            return;
        }
        AudioManager audio = (AudioManager) sContext.getSystemService(Context.AUDIO_SERVICE);
        if (audio != null && audio.isMusicActive()) {
            Log.i(TAG, "IDLE audio playing, checking again later");
            sHandler.postDelayed(CHECK, AUDIO_RECHECK_MS);
            return;
        }
        Log.i(TAG, "IDLE closing app after time in background");
        Process.killProcess(Process.myPid());
    }

    private static final class Callbacks implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityStarted(Activity activity) {
            sStarted++;
            sHandler.removeCallbacks(CHECK);
        }

        @Override public void onActivityStopped(Activity activity) {
            if (sStarted > 0) sStarted--;
            if (sStarted == 0) {
                sHandler.removeCallbacks(CHECK);
                sHandler.postDelayed(CHECK, IDLE_MS);
                Log.i(TAG, "IDLE app in background, close check scheduled");
            }
        }

        @Override public void onActivityCreated(Activity activity, Bundle state) {}
        @Override public void onActivityResumed(Activity activity) {}
        @Override public void onActivityPaused(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        @Override public void onActivityDestroyed(Activity activity) {}
    }
}
