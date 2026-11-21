package app.proxma.rox;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * ROX is a Flutter app: its check-in request is built and signed in Dart, out of reach of Java. So
 * instead of replaying the request, this presses the app's own Check-in button through Flutter's
 * semantics tree, the same tree a screen reader uses. The app sends its own request and shows its
 * own result. It runs while the app is open, and on ROX's own screen opened see-through by the
 * background check-in (WakeScreen), which then closes again.
 */
public final class AutoCheckin {

    private static final String TAG = "PROXMA_ROX";
    private static final String PREFS = "proxma_rox";
    private static final String KEY_DONE_DAY = "done_day";
    private static final String KEY_TRY_DAY = "try_day";
    private static final String KEY_TRIES = "tries";
    private static final String KEY_BG_TRY_DAY = "bg_try_day";
    private static final String KEY_BG_TRIES = "bg_tries";
    static final String KEY_BG_STATUS = "bg_status";
    static final String KEY_BG_STATUS_AT = "bg_status_at";
    private static final String KEY_BG_STATUS_SHOWN_AT = "bg_status_shown_at";
    static final String KEY_BG_SIGNED_OUT_DAY = "bg_signed_out_day";

    private static final String FLUTTER_VIEW = "io.flutter.embedding.android.FlutterView";
    private static final String STREAK_ROUTE = "/dailyStreaksScreen";
    private static final String HOME_ROUTE = "/dashBoardScreen";

    // Every session counts, whatever its outcome, so a screen the helper can't read costs at most
    // five short sessions a day instead of one on every app open.
    private static final int MAX_TRIES_PER_DAY = 5;
    // Background sessions have their own budget, so they never use up the ones for app opens.
    static final int MAX_BG_TRIES_PER_DAY = 3;
    private static final long BG_CLOSE_DELAY_MS = 4_000L;
    private static final long TICK_MS = 1500L;
    private static final long SESSION_TIMEOUT_MS = 120_000L;
    private static final long HOME_GRACE_MS = 15_000L;
    private static final long ENTRY_TILE_WAIT_MS = 6_000L;
    private static final long BUTTON_WAIT_MS = 20_000L;
    private static final long RESULT_SETTLE_MS = 6_000L;
    private static final long RESULT_WAIT_MS = 12_000L;

    private static final int ACTION_TAP = 1;
    private static final int ANDROID_ACTION_CLICK = 16;
    private static final long FLAG_HAS_ENABLED_STATE = 0x40L;
    private static final long FLAG_IS_ENABLED = 0x80L;
    private static final long FLAG_IS_HIDDEN = 0x2000L;

    private static final String[] HOME_LABELS = {"rox life", "rox ai", "profile"};
    private static final String[] LOGIN_LABELS = {
        "enter your mobile number", "please enter your mobile number", "please enter your phone number", "sign in required",
    };
    private static final String[] DONE_LABELS = {"congratulations", "you've earned"};
    private static final String[] ALREADY_LABELS = {
        "you can only check in once per day", "will be waiting for you tomorrow",
    };
    private static final String[] DISMISS_LABELS = {"got it", "continue", "ok", "okay", "done", "close"};

    private enum Phase { WAIT_HOME, WAIT_TILE, WAIT_BUTTON, WAIT_RESULT }

    private static volatile boolean sRegistered = false;
    private static Session sSession;
    private static WeakReference<Activity> sMain = new WeakReference<>(null);
    // ROX screens between onStart and onStop.
    private static int sMainVisible = 0;

    private AutoCheckin() {}

    public static void onActivityCreate(Activity activity) {
        try {
            if (activity == null) return;
            sMain = new WeakReference<>(activity);
            WakeScreen.onCreate(activity);
            if (sSession != null && sSession.host.background()) {
                Log.i(TAG, "ENTRY app opened, stopping background session");
                sSession.end("app opened during background session", false);
            }
            if (!sRegistered) {
                sRegistered = true;
                activity.getApplication().registerActivityLifecycleCallbacks(new Callbacks());
                Log.i(TAG, "ENTRY registered");
            }
            start(activity);
        } catch (Throwable t) {
            Log.w(TAG, "ENTRY_ERR " + t);
        }
    }

    private static void start(Activity activity) {
        if (sSession != null) {
            Log.i(TAG, "START skipped, session running");
            return;
        }
        if (WakeScreen.closing(activity)) {
            Log.i(TAG, "START skipped, the screen opened for the check-in is closing");
            return;
        }
        boolean woken = WakeScreen.woken(activity);
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!woken) showBackgroundStatus(activity, prefs);
        String today = today();
        if (today.equals(prefs.getString(KEY_DONE_DAY, ""))) {
            Log.i(TAG, "START skipped, already done " + today);
            if (woken) {
                recordBackground(activity, "Daily check-in done in the background");
                WakeScreen.close(activity, 0L);
            }
            return;
        }
        int tries = today.equals(prefs.getString(KEY_TRY_DAY, "")) ? prefs.getInt(KEY_TRIES, 0) : 0;
        if (tries >= MAX_TRIES_PER_DAY) {
            Log.i(TAG, "START skipped, tries used up " + tries);
            if (woken) {
                recordBackground(activity, "ROX's screen opened, but today's tries were used up");
                WakeScreen.close(activity, 0L);
            }
            return;
        }
        Log.i(TAG, "START session, earlier tries today " + tries);
        prefs.edit().putString(KEY_TRY_DAY, today).putInt(KEY_TRIES, tries + 1).apply();
        sSession = new Session(new ActivityHost(activity), null);
        sSession.run();
    }

    /** Called from CheckinJobService on the main thread. Returns true when a session started. */
    static boolean startBackground(Context context, Runnable onDone) {
        try {
            if (sSession != null) {
                Log.i(TAG, "BG skipped, session running");
                return false;
            }
            Context app = context.getApplicationContext();
            Activity main = sMain.get();
            if (main != null && !main.isDestroyed()) {
                // A ROX screen left in the background used to skip every background run for as
                // long as Android kept the process. Only a screen in view (someone is using ROX,
                // and its own check-in runs) or no way to open a fresh one still skips.
                if (sMainVisible > 0) {
                    Log.i(TAG, "BG skipped, app screen in view");
                    return false;
                }
                if (WakeScreen.route(app) == null) {
                    Log.i(TAG, "BG skipped, app screen exists and no way to open a fresh one");
                    return false;
                }
                Log.i(TAG, "BG app screen exists but is out of view, opening a fresh one");
            }
            SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String today = today();
            if (today.equals(prefs.getString(KEY_DONE_DAY, ""))) {
                Log.i(TAG, "BG skipped, already done " + today);
                return false;
            }
            int tries = today.equals(prefs.getString(KEY_BG_TRY_DAY, "")) ? prefs.getInt(KEY_BG_TRIES, 0) : 0;
            if (tries >= MAX_BG_TRIES_PER_DAY) {
                Log.i(TAG, "BG skipped, background tries used up " + tries);
                return false;
            }
            if (WakeScreen.lockedOrDark(app)) {
                // Nothing checks in over the lock screen or with the display off any more; the
                // check-in happens at the first unlock instead (UnlockService). Uses no try.
                Log.i(TAG, "BG phone locked or screen off, not opening ROX");
                recordBackground(app, "phone was locked; no check-in over the lock screen, "
                    + "it runs when you unlock the phone");
                return false;
            }
            prefs.edit().putString(KEY_BG_TRY_DAY, today).putInt(KEY_BG_TRIES, tries + 1).commit();
            // ROX's own screen is where the check-in is known to work, so open it when Android
            // allows that.
            String route = WakeScreen.open(app);
            if (route != null) {
                Log.i(TAG, "BG opened ROX's screen (" + route + "), earlier background tries today " + tries);
                // Replaced by the screen's own result as soon as it opens.
                recordBackground(app, "asked Android to open ROX's screen (" + route + ") but it didn't open");
                if (onDone != null) {
                    // Keep the job until the start check has had its chance.
                    new Handler(Looper.getMainLooper()).postDelayed(onDone, WakeScreen.BACKUP_NOTICE_MS + 15_000L);
                }
                return true;
            }
            // No off-screen run any more: the hidden engine never got past a blank screen on the
            // reporter's phone. Without a way to open ROX's own screen, say so and ask for the setting.
            Log.i(TAG, "BG no way to open ROX's screen (Appear on top is off), not checking in");
            recordBackground(app, "Appear on top is off for ROX, so it couldn't open itself to check in");
            Notices.needsYou(app, "Turn on Appear on top for ROX so it can check in by itself.");
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "BG_START_ERR " + t);
            // Nothing started, so no session can report it. Without this the next app open would
            // say the job never ran and blame the battery setting.
            reportStartFailure(context.getApplicationContext(), t);
            return false;
        }
    }

    private static void reportStartFailure(Context context, Throwable error) {
        try {
            Throwable cause = error;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            String detail = cause.getClass().getSimpleName();
            String message = cause.getMessage();
            if (message != null && !message.isEmpty()) {
                detail += ": " + (message.length() > 80 ? message.substring(0, 80) : message);
            }
            String text = "couldn't start the background check-in (" + detail + ")";
            Log.w(TAG, "BG start failure recorded, " + text);
            recordBackground(context, text);
            if (backgroundTriesToday(context) >= MAX_BG_TRIES_PER_DAY) {
                Notices.needsYou(context, "Background try: " + text + ".");
            }
        } catch (Throwable t) {
            Log.w(TAG, "BG_REPORT_ERR " + t);
        }
    }

    /** Called when the system stops the background job early. */
    static void stopBackground(String why) {
        if (sSession != null && sSession.host.background()) sSession.end(why, false);
    }

    static boolean backgroundRunning() {
        return sSession != null && sSession.host.background();
    }

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean doneToday(Context context) {
        return today().equals(prefs(context).getString(KEY_DONE_DAY, ""));
    }

    static int backgroundTriesToday(Context context) {
        SharedPreferences prefs = prefs(context);
        return today().equals(prefs.getString(KEY_BG_TRY_DAY, "")) ? prefs.getInt(KEY_BG_TRIES, 0) : 0;
    }

    static boolean signedOutToday(Context context) {
        return today().equals(prefs(context).getString(KEY_BG_SIGNED_OUT_DAY, ""));
    }

    static void recordBackground(Context context, String status) {
        prefs(context).edit()
            .putString(KEY_BG_STATUS, status)
            .putLong(KEY_BG_STATUS_AT, System.currentTimeMillis())
            .commit();
        Log.i(TAG, "BG status " + status);
    }

    static String clock(long at) {
        SimpleDateFormat format = new SimpleDateFormat("d MMM HH:mm", Locale.US);
        return format.format(new Date(at));
    }

    /**
     * On each app open, say once what the last background run did, or that it hasn't run although
     * its time has long passed. Without a cable this is the only way to see the background side.
     */
    private static void showBackgroundStatus(Activity activity, SharedPreferences prefs) {
        try {
            if (!CheckinJobService.declared(activity)) return;
            WakeScreen.askIfNeeded(activity);
            long now = System.currentTimeMillis();
            long at = prefs.getLong(KEY_BG_STATUS_AT, 0L);
            long shown = prefs.getLong(KEY_BG_STATUS_SHOWN_AT, 0L);
            long planned = prefs.getLong(CheckinJobService.KEY_PLANNED_AT, 0L);
            String text = null;
            if (at > shown) {
                text = "background check-in " + clock(at) + ": " + prefs.getString(KEY_BG_STATUS, "?");
            } else if (planned > 0 && planned > at && planned > shown && now - planned > 2L * 60L * 60L * 1000L) {
                long fired = prefs.getLong(CheckinAlarm.KEY_ALARM_FIRED_AT, 0L);
                String alarm = fired >= planned - 60_000L
                    ? "the wake-up alarm fired " + clock(fired) + " but the check-in didn't start"
                    : "the wake-up alarm (" + prefs.getString(CheckinAlarm.KEY_ALARM_KIND, "not set") + ") didn't fire";
                text = "background check-in has not run yet, it was due " + clock(planned) + "; " + alarm
                    + ". Check that ROX's battery setting is not Restricted or Deep sleeping.";
            }
            if (text == null) {
                Log.i(TAG, "STATUS nothing new");
                return;
            }
            prefs.edit().putLong(KEY_BG_STATUS_SHOWN_AT, now).apply();
            Log.i(TAG, "STATUS shown " + text);
            Toast.makeText(activity.getApplicationContext(), "ROX patch: " + text, Toast.LENGTH_LONG).show();
            // A success already has its own notification; anything else is kept readable in full.
            if (!text.endsWith(": Daily check-in done in the background")) {
                String full = text.substring(0, 1).toUpperCase(Locale.US) + text.substring(1);
                Notices.lastTry(activity, full.endsWith(".") ? full : full + ".");
            }
        } catch (Throwable t) {
            Log.w(TAG, "STATUS_ERR " + t);
        }
    }

    static String today() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Karachi"));
        return format.format(new Date());
    }

    private static boolean isMain(Activity activity) {
        return activity != null && activity.getClass().getName().equals("com.jazz.rox.MainActivity");
    }

    private static final class Callbacks implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(Activity activity) {
            try {
                if (isMain(activity)) {
                    WakeScreen.onShown(activity, "resumed");
                    start(activity);
                }
            } catch (Throwable t) {
                Log.w(TAG, "RESUME_ERR " + t);
            }
        }

        @Override public void onActivityStarted(Activity activity) {
            try {
                if (!isMain(activity)) return;
                sMainVisible++;
                // Before the resume, so a brought-back screen is taken (made see-through) in time.
                WakeScreen.onShown(activity, "started");
            } catch (Throwable t) {
                Log.w(TAG, "STARTED_ERR " + t);
            }
        }

        @Override public void onActivityPaused(Activity activity) {
            // A see-through check-in screen that goes away early becomes a normal screen again.
            WakeScreen.reveal(activity);
            if (sSession != null && sSession.host.owns(activity)) sSession.end("paused", false);
        }

        @Override public void onActivityStopped(Activity activity) {
            if (isMain(activity) && sMainVisible > 0) sMainVisible--;
            if (sSession != null && sSession.host.owns(activity)) sSession.end("stopped", false);
        }

        @Override public void onActivityDestroyed(Activity activity) {
            if (sSession != null && sSession.host.owns(activity)) sSession.end("destroyed", false);
            if (sMain.get() == activity) sMain = new WeakReference<>(null);
        }

        @Override public void onActivityCreated(Activity activity, Bundle state) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    }

    private static final class Node {
        final Object raw;
        final int id;
        final String label;
        final int actions;
        final long flags;

        Node(Object raw, int id, String label, int actions, long flags) {
            this.raw = raw;
            this.id = id;
            this.label = label;
            this.actions = actions;
            this.flags = flags;
        }

        boolean disabled() {
            return (flags & FLAG_HAS_ENABLED_STATE) != 0 && (flags & FLAG_IS_ENABLED) == 0;
        }
    }

    private static final class Session implements Runnable {
        final Host host;
        final Runnable onDone;
        final Handler handler = new Handler(Looper.getMainLooper());
        final long startedAt = SystemClock.uptimeMillis();
        Phase phase = Phase.WAIT_HOME;
        long phaseAt = startedAt;
        boolean routedToStreak = false;
        boolean tileTapped = false;
        boolean semanticsOn = false;
        boolean retapped = false;
        boolean finished = false;
        boolean claimed = false;
        boolean already = false;
        boolean sawLogin = false;
        String seen = "nothing yet";
        // The first few texts on the last screen read, so a miss says which screen it was stuck on.
        String shown = "";

        // ROX's screen, opened by the background run: it reports like the background and closes itself.
        final boolean woken;

        Session(Host host, Runnable onDone) {
            this.host = host;
            this.onDone = onDone;
            this.woken = host instanceof ActivityHost && WakeScreen.woken(((ActivityHost) host).activity);
            if (woken) Log.i(TAG, "START session on the screen the background run opened");
        }

        @Override public void run() {
            try {
                tick();
            } catch (Throwable t) {
                Log.w(TAG, "TICK_ERR " + t);
                end("Couldn't finish the daily check-in", true);
            }
        }

        void tick() {
            if (finished) return;
            long now = SystemClock.uptimeMillis();
            if (now - startedAt > SESSION_TIMEOUT_MS) {
                Log.w(TAG, "TIMEOUT phase " + phase);
                if (phase == Phase.WAIT_HOME) {
                    if (sawLogin) {
                        end("not signed in", false);
                    } else {
                        end("home screen didn't appear (" + seen + ")", false);
                    }
                    return;
                }
                end("Daily check-in timed out, will retry next time", true);
                return;
            }
            handler.postDelayed(this, TICK_MS);

            if (!host.ready()) {
                seen = "app screen not ready";
                Log.i(TAG, "TICK no FlutterView yet");
                return;
            }
            if (!enableSemantics()) {
                seen = "screen reader view not ready";
                return;
            }

            List<Node> nodes = readTree();
            if (nodes.isEmpty()) {
                seen = "blank screen";
                Log.i(TAG, "TICK semantics tree empty");
                return;
            }
            seen = nodes.size() + " items on screen";
            shown = summarize(nodes);

            switch (phase) {
                case WAIT_HOME: waitHome(nodes, now); break;
                case WAIT_TILE: waitTile(nodes, now); break;
                case WAIT_BUTTON: waitButton(nodes, now); break;
                case WAIT_RESULT: waitResult(nodes, now); break;
            }
        }

        void waitHome(List<Node> nodes, long now) {
            Node button = findCheckinButton(nodes);
            if (button != null) {
                pressButton(nodes, button, now);
                return;
            }
            if (anyContains(nodes, LOGIN_LABELS)) {
                sawLogin = true;
                if (host.background() || woken) {
                    Log.i(TAG, "HOME login screen in background, giving up");
                    end("not signed in", false);
                    return;
                }
                Log.i(TAG, "HOME login screen showing, waiting");
                return;
            }
            boolean home = anyEquals(nodes, HOME_LABELS);
            if (!home && now - startedAt < HOME_GRACE_MS) {
                Log.i(TAG, "HOME not recognised yet, nodes " + nodes.size());
                return;
            }
            Log.i(TAG, "HOME ready, marker " + home);
            Node tile = findStreakTile(nodes);
            if (tile != null && tap(tile)) {
                tileTapped = true;
                Log.i(TAG, "TILE tapped");
                setPhase(Phase.WAIT_TILE, now);
                return;
            }
            openStreakRoute(now);
        }

        void waitTile(List<Node> nodes, long now) {
            Node button = findCheckinButton(nodes);
            if (button != null) {
                pressButton(nodes, button, now);
                return;
            }
            if (now - phaseAt >= ENTRY_TILE_WAIT_MS) {
                Log.i(TAG, "TILE opened no check-in button, using route");
                tileTapped = false;
                openStreakRoute(now);
            }
        }

        void waitButton(List<Node> nodes, long now) {
            Node button = findCheckinButton(nodes);
            if (button != null) {
                pressButton(nodes, button, now);
                return;
            }
            if (anyContains(nodes, ALREADY_LABELS) && now - phaseAt >= ENTRY_TILE_WAIT_MS) {
                Log.i(TAG, "BUTTON absent, screen says already checked in");
                already = true;
                markDone();
                leaveStreakScreen();
                end("Already checked in today", true);
                return;
            }
            if (now - phaseAt >= BUTTON_WAIT_MS) {
                Log.w(TAG, "BUTTON not found on streak screen, nodes " + nodes.size());
                leaveStreakScreen();
                end("Couldn't find the Check-in button (" + seen + ")", true);
            }
        }

        void waitResult(List<Node> nodes, long now) {
            if (anyContains(nodes, DONE_LABELS)) {
                Log.i(TAG, "RESULT success text showing");
                finishSuccess(nodes);
                return;
            }
            Node button = findCheckinButton(nodes);
            if (button == null && now - phaseAt >= RESULT_SETTLE_MS) {
                Log.i(TAG, "RESULT button gone after tap");
                finishSuccess(nodes);
                return;
            }
            if (now - phaseAt < RESULT_WAIT_MS) return;
            if (button != null && !retapped) {
                retapped = true;
                Log.w(TAG, "RESULT button still there, tapping once more");
                tap(button);
                setPhase(Phase.WAIT_RESULT, now);
                return;
            }
            Log.w(TAG, "RESULT button stayed after retry");
            leaveStreakScreen();
            end("Check-in didn't go through, will retry next time", true);
        }

        void pressButton(List<Node> nodes, Node button, long now) {
            if (button.disabled()) {
                Log.i(TAG, "BUTTON disabled, treating as already checked in");
                already = true;
                markDone();
                leaveStreakScreen();
                end("Already checked in today", true);
                return;
            }
            if (tap(button)) {
                Log.i(TAG, "BUTTON tapped, label " + button.label);
                setPhase(Phase.WAIT_RESULT, now);
                return;
            }
            Log.w(TAG, "BUTTON could not be tapped");
            end("Couldn't press the Check-in button", true);
        }

        void finishSuccess(List<Node> nodes) {
            markDone();
            claimed = true;
            Node dismiss = findDismiss(nodes);
            if (dismiss != null) {
                Log.i(TAG, "RESULT dismissing, label " + dismiss.label);
                tap(dismiss);
            } else {
                Log.i(TAG, "RESULT nothing to dismiss");
            }
            final Session self = this;
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        self.leaveStreakScreen();
                    } catch (Throwable t) {
                        Log.w(TAG, "LEAVE_ERR " + t);
                    }
                }
            }, 1200L);
            end(host.background() || woken ? "Daily check-in done in the background" : "Daily check-in done", true);
        }

        void openStreakRoute(long now) {
            routedToStreak = true;
            pushRoute(STREAK_ROUTE);
            Log.i(TAG, "ROUTE pushed " + STREAK_ROUTE);
            setPhase(Phase.WAIT_BUTTON, now);
        }

        void leaveStreakScreen() {
            if (host.background()) {
                Log.i(TAG, "LEAVE skipped, background engine is closed afterwards");
                return;
            }
            if (routedToStreak) {
                pushRoute(HOME_ROUTE);
                Log.i(TAG, "LEAVE pushed " + HOME_ROUTE);
            } else if (tileTapped) {
                popRoute();
                Log.i(TAG, "LEAVE popped route");
            } else {
                Log.i(TAG, "LEAVE stayed, button was already on screen");
            }
        }

        void setPhase(Phase next, long now) {
            phase = next;
            phaseAt = now;
        }

        void markDone() {
            host.context().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DONE_DAY, today()).commit();
            // The background job has nothing left to do today; move it to tomorrow.
            if (!host.background()) CheckinJobService.planNext(host.context());
        }

        void end(String message, boolean toast) {
            if (finished) return;
            finished = true;
            handler.removeCallbacks(this);
            if (sSession == this) sSession = null;
            Log.i(TAG, "END " + message + (host.background() ? " (background)" : "")
                + (shown.isEmpty() ? "" : ", screen showed " + shown));
            restoreSemantics();
            // In the background the result goes to a notification and to the status shown on the
            // next app open instead of a toast.
            if (host.background()) {
                toast = false;
                reportBackground(message);
            } else if (woken) {
                toast = false;
                reportWoken(message);
            } else if (claimed || already) {
                Notices.clear(host.context());
            }
            if (toast && message != null) {
                try {
                    Toast.makeText(host.context().getApplicationContext(), "ROX patch: " + message, Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    Log.w(TAG, "TOAST_ERR " + t);
                }
            }
            if (host.background()) {
                // Give the app a moment to finish the request it just sent before its engine goes.
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        host.close();
                        if (onDone != null) onDone.run();
                    }
                }, claimed ? BG_CLOSE_DELAY_MS : 0L);
            }
        }

        void reportBackground(String message) {
            Context context = host.context();
            String text = message == null ? "stopped" : message;
            if (text.equals("not signed in")) {
                prefs(context).edit().putString(KEY_BG_SIGNED_OUT_DAY, today()).commit();
            }
            if (!claimed && !already) {
                // How far the off-screen app got, led first so even a collapsed notification shows
                // the crux (frames drawn, app code running, the call it is stuck on) in a screenshot.
                String diag = host.diag();
                if (diag != null && !diag.isEmpty()) text = diag + " — " + text;
                if (!shown.isEmpty()) text = text + "; screen showed " + shown;
            }
            recordBackground(context, text);
            if (claimed) {
                Notices.done(context, "Checked in for you in the background at "
                    + clock(System.currentTimeMillis()) + ".");
                return;
            }
            if (already) {
                Notices.clear(context);
                return;
            }
            // Retries stay quiet; only the last miss of the day asks for a tap.
            if (backgroundTriesToday(context) >= MAX_BG_TRIES_PER_DAY || signedOutToday(context)) {
                Notices.needsYou(context, "Background try: " + text + ".");
            }
        }

        /**
         * The result of a check-in on the screen the background run opened. It goes where a
         * background result goes, and the screen then closes, unless it only went away (paused,
         * stopped) and comes back on its own, which starts a fresh session.
         */
        void reportWoken(String message) {
            Activity activity = ((ActivityHost) host).activity;
            Context context = activity.getApplicationContext();
            String text = message == null ? "stopped" : message;
            if (text.equals("paused") || text.equals("stopped") || text.equals("destroyed")) {
                recordBackground(context, "ROX's screen opened but went away before the check-in finished ("
                    + text + ")" + (shown.isEmpty() ? "" : "; screen showed " + shown));
                return;
            }
            if (text.equals("not signed in")) {
                prefs(context).edit().putString(KEY_BG_SIGNED_OUT_DAY, today()).commit();
            }
            if (!claimed && !already) {
                text = text + " (on ROX's screen, opened for it)";
                if (!shown.isEmpty()) text = text + "; screen showed " + shown;
            }
            recordBackground(context, text);
            if (claimed) {
                Notices.done(context, "Checked in for you at " + clock(System.currentTimeMillis())
                    + (WakeScreen.hidden(activity) ? " in the background."
                        : "; ROX opened for a moment to do it."));
            } else if (already) {
                Notices.clear(context);
            } else if (backgroundTriesToday(context) >= MAX_BG_TRIES_PER_DAY || signedOutToday(context)) {
                Notices.needsYou(context, "Background try: " + text + ".");
            }
            // Give the app a moment to finish the request it just sent before the screen goes.
            WakeScreen.close(activity, claimed ? BG_CLOSE_DELAY_MS : 1_500L);
        }

        boolean enableSemantics() {
            if (semanticsOn) return true;
            try {
                setSemantics(true);
                semanticsOn = true;
                Log.i(TAG, "SEMANTICS enabled");
                return true;
            } catch (Throwable t) {
                Log.i(TAG, "SEMANTICS not ready " + t);
                return false;
            }
        }

        void restoreSemantics() {
            if (!semanticsOn) return;
            try {
                AccessibilityManager manager =
                    (AccessibilityManager) host.context().getSystemService(Context.ACCESSIBILITY_SERVICE);
                setSemantics(manager != null && manager.isEnabled());
                Log.i(TAG, "SEMANTICS restored");
            } catch (Throwable t) {
                Log.w(TAG, "SEMANTICS_RESTORE_ERR " + t);
            }
        }

        void setSemantics(boolean on) throws Exception {
            Object renderer = call(engine(), "getRenderer");
            renderer.getClass().getMethod("setSemanticsEnabled", boolean.class).invoke(renderer, on);
        }

        void pushRoute(String route) {
            try {
                Object channel = call(engine(), "getNavigationChannel");
                channel.getClass().getMethod("pushRouteInformation", String.class).invoke(channel, route);
            } catch (Throwable t) {
                Log.w(TAG, "PUSH_ROUTE_ERR " + route + " " + t);
            }
        }

        void popRoute() {
            try {
                call(call(engine(), "getNavigationChannel"), "popRoute");
            } catch (Throwable t) {
                Log.w(TAG, "POP_ROUTE_ERR " + t);
            }
        }

        Object engine() throws Exception {
            return host.engine();
        }

        AccessibilityNodeProvider bridge() throws Exception {
            return host.bridge();
        }

        @SuppressWarnings("unchecked")
        List<Node> readTree() {
            List<Node> nodes = new ArrayList<>();
            try {
                Object bridge = bridge();
                if (bridge == null) return nodes;
                Map<Integer, Object> tree = (Map<Integer, Object>) field(bridge, "flutterSemanticsTree");
                for (Map.Entry<Integer, Object> entry : tree.entrySet()) {
                    Object raw = entry.getValue();
                    long flags = (Long) field(raw, "flags");
                    if ((flags & FLAG_IS_HIDDEN) != 0) continue;
                    nodes.add(new Node(raw, entry.getKey(), labelOf(raw), (Integer) field(raw, "actions"), flags));
                }
            } catch (Throwable t) {
                Log.w(TAG, "READ_TREE_ERR " + t);
            }
            return nodes;
        }

        boolean tap(Node node) {
            try {
                Object raw = node.raw;
                while (raw != null) {
                    if (((Integer) field(raw, "actions") & ACTION_TAP) != 0) {
                        return bridge().performAction((Integer) field(raw, "id"), ANDROID_ACTION_CLICK, null);
                    }
                    raw = field(raw, "parent");
                }
                Log.i(TAG, "TAP no tap action on node or parents, sending a touch");
                return touch(node);
            } catch (Throwable t) {
                Log.w(TAG, "TAP_ERR " + t);
                return false;
            }
        }

        boolean touch(Node node) throws Exception {
            View view = host.touchView();
            if (view == null) {
                Log.w(TAG, "TOUCH no view to touch in the background");
                return false;
            }
            Rect rect = (Rect) field(node.raw, "globalRect");
            if (rect == null || rect.isEmpty()) {
                Log.w(TAG, "TOUCH no bounds for node");
                return false;
            }
            long down = SystemClock.uptimeMillis();
            float x = rect.exactCenterX();
            float y = rect.exactCenterY();
            MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent release = MotionEvent.obtain(down, down + 60L, MotionEvent.ACTION_UP, x, y, 0);
            view.dispatchTouchEvent(press);
            view.dispatchTouchEvent(release);
            press.recycle();
            release.recycle();
            return true;
        }
    }

    private interface Host {
        Context context();
        boolean ready();
        Object engine() throws Exception;
        AccessibilityNodeProvider bridge() throws Exception;
        View touchView();
        boolean background();
        boolean owns(Activity activity);
        void close();
        /** A short account of how far the off-screen app got; empty for the app's own screen. */
        String diag();
    }

    /** The app's own screen: its FlutterView and its engine. */
    private static final class ActivityHost implements Host {
        final Activity activity;
        View view;

        ActivityHost(Activity activity) {
            this.activity = activity;
        }

        @Override public Context context() {
            return activity;
        }

        @Override public boolean ready() {
            View found = findFlutterView(activity.getWindow().getDecorView());
            if (found == null) return false;
            view = found;
            return true;
        }

        @Override public Object engine() throws Exception {
            Object engine = activity.getClass().getMethod("getFlutterEngine").invoke(activity);
            if (engine == null) throw new IllegalStateException("no engine");
            return engine;
        }

        @Override public AccessibilityNodeProvider bridge() throws Exception {
            return (AccessibilityNodeProvider) field(view, "accessibilityBridge");
        }

        @Override public View touchView() {
            return view;
        }

        @Override public boolean background() {
            return false;
        }

        @Override public boolean owns(Activity other) {
            return activity == other;
        }

        @Override public void close() {}

        @Override public String diag() {
            return "";
        }
    }

    private static Node findCheckinButton(List<Node> nodes) {
        Node enabled = null;
        Node disabled = null;
        for (Node node : nodes) {
            for (String line : node.label.split("\n")) {
                String text = normalize(line);
                if (text.equals("check-in") || text.equals("check in") || text.equals("checkin")
                    || text.equals("check-in now") || text.equals("check in now")) {
                    if (node.disabled()) {
                        disabled = node;
                    } else if ((node.actions & ACTION_TAP) != 0) {
                        return node;
                    } else {
                        enabled = node;
                    }
                }
            }
        }
        return enabled != null ? enabled : disabled;
    }

    private static Node findStreakTile(List<Node> nodes) {
        for (Node node : nodes) {
            if (normalize(node.label).contains("daily streak") && (node.actions & ACTION_TAP) != 0) return node;
        }
        return null;
    }

    private static Node findDismiss(List<Node> nodes) {
        for (Node node : nodes) {
            String text = normalize(node.label);
            for (String label : DISMISS_LABELS) {
                if (text.equals(label) && (node.actions & ACTION_TAP) != 0) return node;
            }
        }
        return null;
    }

    private static boolean anyContains(List<Node> nodes, String[] needles) {
        for (Node node : nodes) {
            String text = normalize(node.label);
            for (String needle : needles) {
                if (text.contains(needle)) return true;
            }
        }
        return false;
    }

    private static boolean anyEquals(List<Node> nodes, String[] wanted) {
        for (Node node : nodes) {
            for (String line : node.label.split("\n")) {
                String text = normalize(line);
                for (String word : wanted) {
                    if (text.equals(word)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Up to four distinct texts from the screen, in tree order, each cut to 30 characters. Runs of
     * five or more digits (a phone number, a balance) are masked, since the reporter may share a
     * screenshot of the message.
     */
    private static String summarize(List<Node> nodes) {
        List<Node> ordered = new ArrayList<>(nodes);
        java.util.Collections.sort(ordered, new java.util.Comparator<Node>() {
            @Override public int compare(Node a, Node b) {
                return a.id < b.id ? -1 : (a.id == b.id ? 0 : 1);
            }
        });
        StringBuilder out = new StringBuilder();
        java.util.Set<String> used = new java.util.HashSet<>();
        int count = 0;
        for (Node node : ordered) {
            String text = node.label.replace('\n', ' ').replaceAll("\\s+", " ").trim();
            if (text.isEmpty()) continue;
            text = text.replaceAll("\\d{5,}", "#####");
            if (text.length() > 30) text = text.substring(0, 30) + "...";
            if (!used.add(text)) continue;
            if (out.length() > 0) out.append(" | ");
            out.append('"').append(text).append('"');
            if (++count >= 4) break;
        }
        return out.toString();
    }

    private static String normalize(String text) {
        return text.trim().replace('’', '\'').toLowerCase(Locale.US);
    }

    private static String labelOf(Object raw) throws Exception {
        Object label = field(raw, "label");
        return label == null ? "" : (String) label;
    }

    private static View findFlutterView(View root) {
        if (root == null) return null;
        if (root.getClass().getName().equals(FLUTTER_VIEW)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findFlutterView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Object call(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        return m.invoke(target);
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
