package app.proxma.simosa;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime half of the "Hide Shop, Social+ and Lifestyle tabs" patch: drops tabs from the main
 * screen's bottom bar by identifier, before the bar composable reads the list.
 *
 * <p>The bar is built from {@code List<TilesListItem>} and each item carries its own route
 * identifier, so filtering the list here is enough — no screen, route or navigation-graph entry is
 * removed. Anything that still routes to a hidden tab (a deep link, a dashboard tile, a
 * notification) keeps working; it just no longer holds a slot in the bar.
 *
 * <p>Identifiers are the app's own route strings, read from the default bottom-bar builder, so they
 * are locale-independent. Label to route, as built by the app (3.3.5 and 3.3.4.2 agree):
 * <ul>
 *   <li>{@code shop} — the Shop tab ({@code R.string.packages})
 *   <li>{@code myworld} — the Social+ tab ({@code R.string.my_world_title})
 *   <li>{@code explore} — the Lifestyle tab ({@code R.string.explore})
 * </ul>
 * Home ({@code dynamicdashboard}), Account ({@code myaccount}), the optional Faith tab
 * ({@code islam2020}) and any other route are never touched, and an unknown identifier is always
 * kept, so a future build that renames a route degrades to "nothing hidden" rather than to an
 * empty bar.
 *
 * <p>The identifier getter is reached reflectively on purpose: these models are obfuscated per
 * build, and a missing getter must mean "keep the tab", not a crash on the main screen.
 */
public final class HideTabs {

    /**
     * Route identifiers removed from the bottom bar.
     *
     * <p>Route, not label: the reporter's bar reads Home, Shop, Social+, Lifestyle, Account, and
     * the builder's own identifiers for those are {@code dynamicdashboard}, {@code shop},
     * {@code myworld}, {@code explore}, {@code myaccount}. Exactly the three middle ones are
     * hidden here. Do not infer these from position in the builder: {@code support} and
     * {@code islam2020} are also built, conditionally, and neither is one of the three tabs asked
     * for — {@code support} used to sit in this array and hid nothing on the reporter's build,
     * which is how Social+ was left visible (issue #31).
     */
    private static final String[] HIDDEN = {"shop", "myworld", "explore"};

    private static final String IDENTIFIER_GETTER = "getIdentifier";

    private HideTabs() {
    }

    /**
     * Returns {@code tabs} unchanged when it holds nothing hidden — the same instance, so Compose
     * sees no change and nothing recomposes. Otherwise returns a new list with the hidden tabs
     * removed, order preserved.
     */
    public static List<?> filter(List<?> tabs) {
        if (tabs == null || tabs.isEmpty()) {
            return tabs;
        }
        boolean hidesSomething = false;
        for (Object tab : tabs) {
            if (isHidden(tab)) {
                hidesSomething = true;
                break;
            }
        }
        if (!hidesSomething) {
            return tabs;
        }
        ArrayList<Object> kept = new ArrayList<>(tabs.size());
        for (Object tab : tabs) {
            if (!isHidden(tab)) {
                kept.add(tab);
            }
        }
        return kept;
    }

    private static boolean isHidden(Object tab) {
        String identifier = identifierOf(tab);
        if (identifier == null) {
            return false;
        }
        for (String hidden : HIDDEN) {
            if (hidden.equals(identifier)) {
                return true;
            }
        }
        return false;
    }

    private static String identifierOf(Object tab) {
        if (tab == null) {
            return null;
        }
        Class<?> type = tab.getClass();
        // Getter first: the model is a Kotlin data class, so `getIdentifier()` exists and is
        // public. Accessibility is forced unconditionally rather than via canAccess(): this module
        // is compiled at Java 8 source level for Android, where the Java 9 canAccess() does not
        // exist. The accessor is a public method on a class an obfuscator may not have marked
        // public, and reflection throws IllegalAccessException there — which would otherwise read
        // as "hide nothing".
        try {
            Method getter = type.getMethod(IDENTIFIER_GETTER);
            getter.setAccessible(true);
            Object value = getter.invoke(tab);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            // fall through to the field
        }
        try {
            Field field = type.getDeclaredField("identifier");
            field.setAccessible(true);
            Object value = field.get(tab);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
