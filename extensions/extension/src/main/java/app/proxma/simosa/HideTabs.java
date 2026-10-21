package app.proxma.simosa;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime half of the "Hide Shop and Lifestyle tabs" patch: drops tabs from the main screen's
 * bottom bar by identifier, before the bar composable reads the list.
 *
 * <p>The bar is built from {@code List<TilesListItem>} and each item carries its own route
 * identifier, so filtering the list here is enough — no screen, route or navigation-graph entry is
 * removed. Anything that still routes to a hidden tab (a deep link, a dashboard tile, a
 * notification) keeps working; it just no longer holds a slot in the bar.
 *
 * <p>Identifiers are the app's own route strings, read from the default bottom-bar builder, so they
 * are locale-independent:
 * <ul>
 *   <li>{@code shop} — the Shop tab ({@code R.string.packages}, route from {@code M7.b})
 *   <li>{@code explore} — the Lifestyle tab ({@code R.string.explore})
 * </ul>
 * Home ({@code dynamicdashboard}) and Account ({@code myaccount}) are never touched, and an unknown
 * identifier is always kept, so a future build that renames a route degrades to "nothing hidden"
 * rather than to an empty bar.
 *
 * <p>The identifier getter is reached reflectively on purpose: these models are obfuscated per
 * build, and a missing getter must mean "keep the tab", not a crash on the main screen.
 */
public final class HideTabs {

    /**
     * Route identifiers removed from the bottom bar.
     *
     * <p>The bar is built in this fixed order: {@code dynamicdashboard} (Home), {@code shop},
     * {@code explore}, {@code support}, {@code myaccount} (Account), then the optional
     * {@code myworld} / {@code islam2020} entries. The reporter's screen shows five tabs —
     * Home, Shop, Social+, Lifestyle, Account — i.e. exactly the three routes between Home and
     * Account. Those three are listed here, so the bar keeps the two he asked to keep. This holds
     * whichever of {@code explore} / {@code support} the app labels Social+ or Lifestyle, because
     * both are dropped.
     */
    private static final String[] HIDDEN = {"shop", "explore", "support"};

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
