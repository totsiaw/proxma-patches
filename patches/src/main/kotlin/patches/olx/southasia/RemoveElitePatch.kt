package patches.olx.southasia

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags

/*
 * OLX India 20.07.001 — remove the promoted "Elite" placements from the listing feed.
 *
 * What the reporter sees (issue 24, 2026-10-17): a search result list in which most rows are
 * oversized ELITE seller cards carrying inline Chat and Call buttons, several of them from the same
 * dealer. Those are OLX's own paid promotions, not third-party ad SDK inventory, so the ad-removal
 * patch does not touch them.
 *
 * Where they come from, in this build:
 *
 *   - An ad widget whose ad item reports elite seller is rendered as
 *     SearchExperienceWidget$Type.AD_ELITE_LIST_TILE instead of the normal compact tile. That
 *     decision is made per item in com.olxgroup.panamera.app.buyers.common.adapters.f (the listing
 *     adapter) when the view type is computed: getWidgetType() == Type.AD, then getEliteSeller()
 *     on the AdItem picks the oversized tile.
 *   - The listing page also has its own promotion slot, the buyer-matchmaking widget
 *     (SearchExperienceWidget$Type.LISTING_ELITE_BUYER_RECOMMENDATION), inserted at a server-driven
 *     position.
 *
 * STRATEGY: remove the promoted entries from the list the adapter renders, at the two points where
 * that list is handed over, rather than changing how a promoted item is drawn. Drawing an elite ad
 * as an ordinary tile would leave the same ad in the feed with the same repeated-dealer flood; the
 * request is to remove them.
 *
 * The two injection sites are the listing adapter's own list setters:
 *
 *   p(Ljava/util/List;)V  — assign the whole list (first page, refresh, filter change)
 *   b(Ljava/util/List;)V  — append a page (addAll + notifyItemRangeInserted)
 *
 * Both are filtered through the same helper, so the adapter's list, the results fragments and the
 * adapters own index helpers stay consistent: the fragments read the same filtered list, h(int,
 * widget) already bounds-checks its index (it no-ops when the index exceeds size) and n(type)
 * removes by scanning, so a shorter list degrades to a no-op rather than an out-of-bounds crash.
 *
 * The filter itself lives in the extension dex (app.proxma.olxsa.EliteFilter) because it needs a
 * loop and an instanceof test, and is reflection-based because the extension subproject has no OLX
 * classes on its classpath. It fails open: any reflection failure keeps the widget, so the worst
 * case is stock behaviour, never a crash.
 */

/** com.olxgroup.panamera.app.buyers.common.adapters.f — the listing adapter. */
private const val LISTING_ADAPTER = "Lcom/olxgroup/panamera/app/buyers/common/adapters/f;"

/**
 * The adapter's whole-list setter, `p(Ljava/util/List;)V`.
 *
 * Verified against the decompiled 20.07.001 tree: exactly one method named `p` with this shape
 * exists in this class, and the name is `final` there, so the subclass used for the results screen
 * cannot shadow it. The body is `.locals 0`, so the parameter register `p1` is free to be
 * overwritten by the filter's result.
 */
internal val southAsiaListingAdapterSetListFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Ljava/util/List;"),
    custom = { m, c -> c.type == LISTING_ADAPTER && m.name == "p" },
)

/**
 * The adapter's page-append setter, `b(Ljava/util/List;)V`.
 *
 * Same shape and the same class; `.locals 3`, so `p1` is the fourth register. It calls
 * `addAll(p1)` and then `p1.size()` for its `notifyItemRangeInserted` count, so replacing the
 * argument up front keeps the reported count equal to the number of items actually added.
 */
internal val southAsiaListingAdapterAppendListFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Ljava/util/List;"),
    custom = { m, c -> c.type == LISTING_ADAPTER && m.name == "b" },
)

private const val FILTER_INVOCATION = """
    invoke-static {p1}, Lapp/proxma/olxsa/EliteFilter;->drop(Ljava/util/List;)Ljava/util/List;
    move-result-object p1
"""

@Suppress("unused")
val removeEliteSouthAsiaPatch = bytecodePatch(
    name = "Remove Elite promotions",
    description = "Removes the promoted Elite seller cards and the buyer-matchmaking " +
        "recommendation slot from OLX India's listing feed — OLX's own paid placements, which the " +
        "ad-removal patch does not cover.",
) {
    compatibleWith(COMPATIBILITY_OLX_SOUTHASIA)
    extendWith("extensions/extension.mpe")

    execute {
        // Each fingerprint is resolved here, and an unresolved one throws — the patch must fail
        // loudly rather than ship a feed that still shows the cards.
        val setList = southAsiaListingAdapterSetListFingerprint.method
        val appendList = southAsiaListingAdapterAppendListFingerprint.method

        setList.addInstructions(0, FILTER_INVOCATION.trimIndent())
        appendList.addInstructions(0, FILTER_INVOCATION.trimIndent())

        println(
            "OLX India remove Elite promotions: filtered the listing feed at " +
                "f.p(List) and f.b(List); promoted Elite seller cards and the " +
                "LISTING_ELITE_BUYER_RECOMMENDATION slot no longer reach the adapter",
        )
    }
}
