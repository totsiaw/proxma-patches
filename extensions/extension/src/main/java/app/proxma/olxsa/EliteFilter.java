package app.proxma.olxsa;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime half of the OLX India "remove Elite promotions" patch.
 *
 * <p>OLX India 20.07.001 renders the placements its own monetisation programme pushes into every
 * feed — the oversized ELITE seller cards with the inline Chat/Call buttons — from the same widget
 * list as the ordinary listings:
 *
 * <ul>
 *   <li>{@code com.olxgroup.panamera.app.buyers.common.adapters.f.getItemViewType(int)} switches an
 *       ad widget to {@code SearchExperienceWidget$Type.AD_ELITE_LIST_TILE} whenever the ad item's
 *       {@code getEliteSeller()} is true, ahead of the normal {@code AD} tile;</li>
 *   <li>the buyer matchmaking slot ({@code LISTING_ELITE_BUYER_RECOMMENDATION}) is a second,
 *       separate promotion widget inserted into the listing page.</li>
 * </ul>
 *
 * <p>This class drops both, at the two points where the adapter's list is handed over (the listing
 * adapter's {@code p(List)} = first page / refresh and {@code b(List)} = appended page). Filtering
 * there rather than in the render path is what keeps the adapter's own index bookkeeping
 * consistent: {@code h(int, widget)}, {@code n(type)} and the results fragments all read the same
 * filtered list, and {@code h} already bounds-checks its index, so a shorter list degrades to a
 * no-op rather than an {@code IndexOutOfBoundsException}.
 *
 * <p>Reflection is deliberate: this class is compiled by the repository's extension subproject,
 * which has no OLX classes on its classpath. Every lookup is cached, and every failure path
 * <strong>keeps</strong> the widget — a reflection failure must degrade to stock behaviour, never
 * to a crash and never to an empty feed.
 */
public final class EliteFilter {

    private static final String CLS_WIDGET =
            "com.olxgroup.panamera.domain.buyers.listings.entity.SearchExperienceWidget";
    private static final String CLS_AD_WIDGET =
            "com.olxgroup.panamera.domain.buyers.common.entity.AdWidget";
    private static final String CLS_AD_ITEM =
            "com.olxgroup.panamera.domain.buyers.common.entity.ad.AdItem";
    private static final String CLS_ENUM = "java.lang.Enum";

    /** The listing page's buyer-matchmaking promotion widget. */
    private static final String TYPE_ELITE_BUYER_RECOMMENDATION = "LISTING_ELITE_BUYER_RECOMMENDATION";

    private static Class<?> adWidgetClass;
    private static Method widgetTypeOf;
    private static Method adItemOf;
    private static Method eliteOf;
    private static Method isEliteOf;
    private static Method enumNameOf;
    private static boolean ready;

    static {
        try {
            Class<?> widget = Class.forName(CLS_WIDGET);
            adWidgetClass = Class.forName(CLS_AD_WIDGET);
            Class<?> adItem = Class.forName(CLS_AD_ITEM);
            widgetTypeOf = widget.getMethod("getWidgetType");
            adItemOf = adWidgetClass.getMethod("getAdItem");
            eliteOf = adItem.getMethod("getEliteSeller");
            isEliteOf = adItem.getMethod("isEliteSeller");
            enumNameOf = Class.forName(CLS_ENUM).getMethod("name");
            ready = true;
        } catch (Throwable ignored) {
            ready = false;
        }
    }

    private EliteFilter() {
    }

    /**
     * Returns the widgets to render: the input list with the promoted Elite entries removed. The
     * input list itself is returned unchanged when nothing was removed, or when anything at all
     * goes wrong.
     */
    public static List<Object> drop(List<Object> widgets) {
        if (!ready || widgets == null || widgets.isEmpty()) {
            return widgets;
        }
        try {
            int removed = 0;
            ArrayList<Object> kept = new ArrayList<Object>(widgets.size());
            for (int i = 0; i < widgets.size(); i++) {
                Object widget = widgets.get(i);
                if (isPromoted(widget)) {
                    removed++;
                } else {
                    kept.add(widget);
                }
            }
            if (removed == 0) {
                return widgets;
            }
            return kept;
        } catch (Throwable ignored) {
            return widgets;
        }
    }

    private static boolean isPromoted(Object widget) {
        if (widget == null) {
            return false;
        }
        try {
            if (adWidgetClass != null && adWidgetClass.isInstance(widget)) {
                Object ad = adItemOf == null ? null : adItemOf.invoke(widget);
                if (ad != null && (isTrue(eliteOf, ad) || isTrue(isEliteOf, ad))) {
                    return true;
                }
            }
            return TYPE_ELITE_BUYER_RECOMMENDATION.equals(widgetType(widget));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isTrue(Method method, Object receiver) {
        try {
            return method != null && Boolean.TRUE.equals(method.invoke(receiver));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The widget type enum's own name, e.g. {@code AD}; null when it cannot be read. */
    private static String widgetType(Object widget) {
        try {
            Object type = widgetTypeOf == null ? null : widgetTypeOf.invoke(widget);
            if (type == null) {
                return null;
            }
            Object name = enumNameOf == null ? null : enumNameOf.invoke(type);
            return name == null ? type.toString() : name.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
