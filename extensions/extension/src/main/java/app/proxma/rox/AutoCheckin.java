package app.proxma.rox;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.ContentResolver;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
 * own result. It runs while the app is open, and from a background job (CheckinJobService) that
 * starts the app's Flutter code off-screen, with no window, and presses the same button there.
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

    private AutoCheckin() {}

    public static void onActivityCreate(Activity activity) {
        try {
            if (activity == null) return;
            sMain = new WeakReference<>(activity);
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
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        showBackgroundStatus(activity, prefs);
        String today = today();
        if (today.equals(prefs.getString(KEY_DONE_DAY, ""))) {
            Log.i(TAG, "START skipped, already done " + today);
            return;
        }
        int tries = today.equals(prefs.getString(KEY_TRY_DAY, "")) ? prefs.getInt(KEY_TRIES, 0) : 0;
        if (tries >= MAX_TRIES_PER_DAY) {
            Log.i(TAG, "START skipped, tries used up " + tries);
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
            Activity main = sMain.get();
            if (main != null && !main.isDestroyed()) {
                Log.i(TAG, "BG skipped, app screen exists");
                return false;
            }
            Context app = context.getApplicationContext();
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
            prefs.edit().putString(KEY_BG_TRY_DAY, today).putInt(KEY_BG_TRIES, tries + 1).commit();
            Log.i(TAG, "BG start session, earlier background tries today " + tries);
            sSession = new Session(new HeadlessHost(app), onDone);
            sSession.run();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "BG_START_ERR " + t);
            if (sSession != null && sSession.host.background()) {
                sSession.end("background start failed", false);
            } else {
                // The off-screen app never started, so no session can report it. Without this the
                // next app open would say the job never ran and blame the battery setting.
                reportStartFailure(context.getApplicationContext(), t);
            }
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
            String text = "couldn't start ROX off-screen (" + detail + ")";
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

    private static String today() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Karachi"));
        return format.format(new Date());
    }

    private static final class Callbacks implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(Activity activity) {
            try {
                if (activity.getClass().getName().equals("com.jazz.rox.MainActivity")) start(activity);
            } catch (Throwable t) {
                Log.w(TAG, "RESUME_ERR " + t);
            }
        }

        @Override public void onActivityPaused(Activity activity) {
            if (sSession != null && sSession.host.owns(activity)) sSession.end("paused", false);
        }

        @Override public void onActivityStopped(Activity activity) {
            if (sSession != null && sSession.host.owns(activity)) sSession.end("stopped", false);
        }

        @Override public void onActivityDestroyed(Activity activity) {
            if (sSession != null && sSession.host.owns(activity)) sSession.end("destroyed", false);
            if (sMain.get() == activity) sMain = new WeakReference<>(null);
        }

        @Override public void onActivityCreated(Activity activity, Bundle state) {}
        @Override public void onActivityStarted(Activity activity) {}
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

        Session(Host host, Runnable onDone) {
            this.host = host;
            this.onDone = onDone;
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
                if (host.background()) {
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
            end(host.background() ? "Daily check-in done in the background" : "Daily check-in done", true);
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
                if (!shown.isEmpty()) text = text + "; screen showed " + shown;
                // How far the off-screen app got, so a miss can be traced from a screenshot.
                String diag = host.diag();
                if (diag != null && !diag.isEmpty()) text = text + " [" + diag + "]";
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

    /**
     * A second Flutter engine running the app's own Dart code with no window: it renders into an
     * ImageReader that is drained and thrown away, gets a phone-sized viewport and the resumed
     * lifecycle, and gets the two native channels MainActivity adds. The semantics tree comes from
     * an AccessibilityBridge on a detached view, the same class FlutterView uses for it.
     *
     * Without an Activity nothing answers the engine's own "flutter/platform" channel: its handler
     * comes from PlatformPlugin, which only an Activity creates, and with no handler the channel
     * drops each call without replying. ROX's startup awaits SystemChrome.setPreferredOrientations,
     * so its first screen never came and the semantics tree stayed empty ("blank screen"). A
     * stand-in handler now answers those calls the way a hidden screen would.
     */
    private static final class HeadlessHost implements Host {
        final Context context;
        final Handler handler = new Handler(Looper.getMainLooper());
        Object engine;
        Object renderer;
        Object platformViews;
        Object platformViews2;
        AccessibilityNodeProvider bridge;
        ImageReader reader;
        boolean closed = false;
        int frames = 0;
        boolean platformAnswered = false;
        MessageSpy spy;

        HeadlessHost(Context context) throws Exception {
            this.context = context;
            try {
                build();
            } catch (Exception e) {
                close();
                throw e;
            }
        }

        private void build() throws Exception {
            Class<?> engineClass = Class.forName("io.flutter.embedding.engine.FlutterEngine");
            engine = engineClass.getConstructor(Context.class, String[].class)
                .newInstance(context, new String[] {"--disable-impeller"});
            Log.i(TAG, "BG engine created");
            Object executor = call(engine, "getDartExecutor");
            addChannels(call(executor, "getBinaryMessenger"));

            renderer = call(engine, "getRenderer");
            Class<?> executorClass = Class.forName("io.flutter.embedding.engine.dart.DartExecutor");
            try {
                Object controller = call(engine, "getPlatformViewsController");
                controller.getClass().getMethod("attach", Context.class,
                        Class.forName("io.flutter.view.TextureRegistry"), executorClass)
                    .invoke(controller, context, renderer, executor);
                platformViews = controller;
            } catch (Throwable t) {
                Log.w(TAG, "BG platform views attach failed " + t);
            }
            try {
                Object controller = call(engine, "getPlatformViewsController2");
                controller.getClass().getMethod("attach", Context.class, executorClass)
                    .invoke(controller, context, executor);
                platformViews2 = controller;
            } catch (Throwable t) {
                Log.w(TAG, "BG platform views 2 attach failed " + t);
            }

            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            float density = metrics.density > 0 ? metrics.density : 2.75f;
            int fullWidth = Math.max(metrics.widthPixels, 720);
            int fullHeight = Math.max(metrics.heightPixels, 1280);
            // Half the pixels, same layout: the logical size the Dart code sees does not change.
            int width = fullWidth / 2;
            int height = fullHeight / 2;
            float ratio = density / 2f;
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override public void onImageAvailable(ImageReader source) {
                    try {
                        Image image = source.acquireLatestImage();
                        if (image != null) {
                            image.close();
                            frames++;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, handler);
            renderer.getClass().getMethod("startRenderingToSurface", Surface.class, boolean.class)
                .invoke(renderer, reader.getSurface(), false);
            renderer.getClass().getMethod("surfaceChanged", int.class, int.class).invoke(renderer, width, height);
            Class<?> metricsClass = Class.forName("io.flutter.embedding.engine.renderer.FlutterRenderer$ViewportMetrics");
            Object viewport = metricsClass.getConstructor().newInstance();
            metricsClass.getField("devicePixelRatio").setFloat(viewport, ratio);
            metricsClass.getField("width").setInt(viewport, width);
            metricsClass.getField("height").setInt(viewport, height);
            renderer.getClass().getMethod("setViewportMetrics", metricsClass).invoke(renderer, viewport);
            try {
                engineClass.getMethod("updateDisplayMetrics", float.class, float.class, float.class)
                    .invoke(engine, (float) width, (float) height, ratio);
            } catch (Throwable t) {
                Log.w(TAG, "BG display metrics failed " + t);
            }
            Log.i(TAG, "BG viewport " + width + "x" + height + " ratio " + ratio);

            // The bridge only reaches its view's parent when a screen reader is on; give it one.
            FrameLayout parent = new FrameLayout(context);
            View root = new View(context);
            parent.addView(root);
            bridge = (AccessibilityNodeProvider) Class.forName("io.flutter.view.AccessibilityBridge")
                .getConstructor(View.class,
                    Class.forName("io.flutter.embedding.engine.systemchannels.AccessibilityChannel"),
                    AccessibilityManager.class, ContentResolver.class,
                    Class.forName("io.flutter.plugin.platform.PlatformViewsAccessibilityDelegate"))
                .newInstance(root, call(engine, "getAccessibilityChannel"),
                    context.getSystemService(Context.ACCESSIBILITY_SERVICE), context.getContentResolver(),
                    call(engine, "getPlatformViewsControllerDelegator"));

            answerPlatformChannel();
            watchMessages();

            // On from the start, so the very first frame the app draws already carries the tree.
            // The bridge above has just turned it off because no screen reader is running.
            try {
                renderer.getClass().getMethod("setSemanticsEnabled", boolean.class).invoke(renderer, true);
                Log.i(TAG, "BG semantics on before the app starts");
            } catch (Throwable t) {
                Log.w(TAG, "BG early semantics failed " + t);
            }

            Object lifecycle = call(engine, "getLifecycleChannel");
            call(lifecycle, "appIsResumed");
            call(lifecycle, "aWindowIsFocused");

            Class<?> entrypointClass = Class.forName("io.flutter.embedding.engine.dart.DartExecutor$DartEntrypoint");
            Object entrypoint = entrypointClass.getMethod("createDefault").invoke(null);
            executorClass.getMethod("executeDartEntrypoint", entrypointClass).invoke(executor, entrypoint);
            Log.i(TAG, "BG dart entrypoint started");
        }

        private void addChannels(Object messenger) throws Exception {
            Class<?> messengerClass = Class.forName("io.flutter.plugin.common.BinaryMessenger");
            Class<?> channelClass = Class.forName("io.flutter.plugin.common.MethodChannel");
            Class<?> handlerClass = Class.forName("io.flutter.plugin.common.MethodChannel$MethodCallHandler");
            Method setHandler = channelClass.getMethod("setMethodCallHandler", handlerClass);
            for (String name : new String[] {"native/keys", "native/settings"}) {
                Object channel = channelClass.getConstructor(messengerClass, String.class).newInstance(messenger, name);
                Object handler = Proxy.newProxyInstance(handlerClass.getClassLoader(), new Class<?>[] {handlerClass},
                    new ChannelHandler(name));
                setHandler.invoke(channel, handler);
            }
            Log.i(TAG, "BG native channels added");
        }

        /** Gives "flutter/platform" a handler, as PlatformPlugin does for the app's own screen. */
        private void answerPlatformChannel() {
            try {
                Object channel = call(engine, "getPlatformChannel");
                Class<?> handlerClass = Class.forName(
                    "io.flutter.embedding.engine.systemchannels.PlatformChannel$PlatformMessageHandler");
                Object stub = Proxy.newProxyInstance(handlerClass.getClassLoader(), new Class<?>[] {handlerClass},
                    new PlatformStub());
                channel.getClass().getMethod("setPlatformMessageHandler", handlerClass).invoke(channel, stub);
                platformAnswered = true;
                Log.i(TAG, "BG platform channel answered");
            } catch (Throwable t) {
                Log.w(TAG, "BG platform channel stand-in failed " + t);
            }
        }

        /** Notes which channels the app's Dart code calls, for the report when a run misses. */
        private void watchMessages() {
            try {
                Object jni = field(engine, "flutterJNI");
                Object original = field(jni, "platformMessageHandler");
                if (original == null) {
                    Log.w(TAG, "BG no message handler to watch");
                    return;
                }
                Class<?> handlerClass = Class.forName("io.flutter.embedding.engine.dart.PlatformMessageHandler");
                spy = new MessageSpy(original);
                Object proxy = Proxy.newProxyInstance(handlerClass.getClassLoader(), new Class<?>[] {handlerClass}, spy);
                jni.getClass().getMethod("setPlatformMessageHandler", handlerClass).invoke(jni, proxy);
                Log.i(TAG, "BG watching app messages");
            } catch (Throwable t) {
                spy = null;
                Log.w(TAG, "BG message watch failed " + t);
            }
        }

        @Override public String diag() {
            StringBuilder out = new StringBuilder();
            boolean running = false;
            try {
                if (engine != null) {
                    running = (Boolean) call(call(engine, "getDartExecutor"), "isExecutingDart");
                }
            } catch (Throwable ignored) {
            }
            out.append("app code ").append(running ? "running" : "not running");
            out.append(", frames ").append(frames);
            out.append(", system calls ").append(platformAnswered ? "answered" : "unanswered");
            if (spy != null) {
                out.append(", app messages ").append(spy.count);
                String last = spy.last();
                if (!last.isEmpty()) out.append(", last: ").append(last);
            }
            return out.toString();
        }

        @Override public Context context() {
            return context;
        }

        @Override public boolean ready() {
            return !closed && engine != null && bridge != null;
        }

        @Override public Object engine() throws Exception {
            if (closed || engine == null) throw new IllegalStateException("no engine");
            return engine;
        }

        @Override public AccessibilityNodeProvider bridge() {
            return closed ? null : bridge;
        }

        @Override public View touchView() {
            return null;
        }

        @Override public boolean background() {
            return true;
        }

        @Override public boolean owns(Activity activity) {
            return false;
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            try {
                if (bridge != null) bridge.getClass().getMethod("release").invoke(bridge);
            } catch (Throwable t) {
                Log.w(TAG, "BG bridge release failed " + t);
            }
            try {
                if (renderer != null) call(renderer, "stopRenderingToSurface");
            } catch (Throwable t) {
                Log.w(TAG, "BG stop rendering failed " + t);
            }
            try {
                if (platformViews != null) call(platformViews, "detach");
                if (platformViews2 != null) call(platformViews2, "detach");
            } catch (Throwable t) {
                Log.w(TAG, "BG platform views detach failed " + t);
            }
            try {
                if (engine != null) {
                    call(engine, "destroy");
                    Log.i(TAG, "BG engine destroyed");
                }
            } catch (Throwable t) {
                Log.w(TAG, "BG engine destroy failed " + t);
            }
            try {
                if (reader != null) reader.close();
            } catch (Throwable ignored) {
            }
            engine = null;
            bridge = null;
        }
    }

    /**
     * Stands in for PlatformPlugin off-screen. The channel replies success once a handler method
     * returns, so each call only has to return quietly; nothing here touches a window.
     */
    private static final class PlatformStub implements InvocationHandler {
        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return args != null && proxy == args[0];
            if (name.equals("toString")) return "proxma-platform";
            Log.i(TAG, "BG platform call " + name);
            Class<?> type = method.getReturnType();
            if (type == boolean.class) return Boolean.FALSE;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0f;
            return null;
        }
    }

    /**
     * Wraps the engine's DartMessenger: every message the Dart side sends goes on to it unchanged,
     * and the last few channel names (with the method for method channels) are kept for the report.
     */
    private static final class MessageSpy implements InvocationHandler {
        final Object original;
        final String[] recent = new String[5];
        int next = 0;
        int count = 0;

        MessageSpy(Object original) {
            this.original = original;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return args != null && proxy == args[0];
            if (name.equals("toString")) return "proxma-spy";
            if (name.equals("handleMessageFromDart") && args != null && args.length >= 2) {
                try {
                    note((String) args[0], (java.nio.ByteBuffer) args[1]);
                } catch (Throwable ignored) {
                }
            }
            try {
                return method.invoke(original, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }

        private void note(String channel, java.nio.ByteBuffer message) {
            count++;
            if (channel == null) return;
            String entry = channel.replace("dev.flutter.pigeon.", "").replace("plugins.flutter.io/", "");
            String called = methodOf(message);
            if (called != null) entry = entry + "." + called;
            if (!handled(channel)) entry = entry + " (no handler)";
            if (entry.length() > 48) entry = entry.substring(entry.length() - 48);
            int prev = (next + recent.length - 1) % recent.length;
            if (entry.equals(recent[prev])) return;
            recent[next] = entry;
            next = (next + 1) % recent.length;
        }

        private boolean handled(String channel) {
            try {
                Object handlers = field(original, "messageHandlers");
                return !(handlers instanceof Map) || ((Map<?, ?>) handlers).containsKey(channel);
            } catch (Throwable t) {
                return true;
            }
        }

        String last() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < recent.length; i++) {
                String entry = recent[(next + i) % recent.length];
                if (entry == null) continue;
                if (out.length() > 0) out.append(" | ");
                out.append(entry);
            }
            return out.toString();
        }

        /** The method name of a standard or JSON method call, read without moving the buffer. */
        private static String methodOf(java.nio.ByteBuffer message) {
            if (message == null) return null;
            int start = message.position();
            int end = message.limit();
            if (end - start < 2) return null;
            int type = message.get(start) & 0xff;
            if (type == 7) {
                int at = start + 1;
                int size = message.get(at) & 0xff;
                at++;
                if (size == 254) {
                    if (end - at < 2) return null;
                    size = (message.get(at) & 0xff) | ((message.get(at + 1) & 0xff) << 8);
                    at += 2;
                } else if (size == 255) {
                    return null;
                }
                if (size <= 0 || size > 80 || at + size > end) return null;
                byte[] bytes = new byte[size];
                for (int i = 0; i < size; i++) bytes[i] = message.get(at + i);
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
            if (type == '{') {
                int length = Math.min(end - start, 160);
                byte[] bytes = new byte[length];
                for (int i = 0; i < length; i++) bytes[i] = message.get(start + i);
                String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                int key = text.indexOf("\"method\":\"");
                if (key < 0) return null;
                int from = key + 10;
                int to = text.indexOf('"', from);
                return to > from ? text.substring(from, to) : null;
            }
            return null;
        }
    }

    /** Stands in for MainActivity's handlers on the two channels it adds in configureFlutterEngine. */
    private static final class ChannelHandler implements InvocationHandler {
        final String channel;

        ChannelHandler(String channel) {
            this.channel = channel;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String called = method.getName();
            if (called.equals("hashCode")) return System.identityHashCode(proxy);
            if (called.equals("equals")) return args != null && proxy == args[0];
            if (called.equals("toString")) return "proxma-" + channel;
            if (!called.equals("onMethodCall") || args == null || args.length != 2) return null;
            Object result = args[1];
            Class<?> resultClass = Class.forName("io.flutter.plugin.common.MethodChannel$Result");
            String name = null;
            try {
                name = (String) field(args[0], "method");
                if (channel.equals("native/keys") && "getSecretKey".equals(name)) {
                    Class<?> provider = Class.forName("com.jazz.rox.NativeKeyProvider");
                    Object instance = provider.getField("INSTANCE").get(null);
                    Object key = provider.getMethod("getSecretKey").invoke(instance);
                    resultClass.getMethod("success", Object.class).invoke(result, key);
                    Log.i(TAG, "BG channel answered " + channel + " " + name);
                    return null;
                }
                if (channel.equals("native/settings") && "openAppSettings".equals(name)) {
                    resultClass.getMethod("success", Object.class).invoke(result, Boolean.FALSE);
                    Log.i(TAG, "BG channel declined " + channel + " " + name);
                    return null;
                }
            } catch (Throwable t) {
                Log.w(TAG, "BG channel error " + channel + " " + name + " " + t);
            }
            resultClass.getMethod("notImplemented").invoke(result);
            Log.i(TAG, "BG channel not implemented " + channel + " " + name);
            return null;
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
