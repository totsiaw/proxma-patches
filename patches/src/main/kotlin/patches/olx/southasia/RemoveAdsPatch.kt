package patches.olx.southasia

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/*
 * OLX India 20.07.001 — ad removal.
 *
 * Nothing from `patches/olx/RemoveAdsPatch.kt` transfers: its anchors (`Lu57;->a()`, `Lh5b;->c(List)`,
 * `Ly84;-><init>`, `Ldf6;-><init>(Lif8;)`, `Lbe;->e`, `Liqe;->y()/w()`) are OdexGuard-style names
 * from the 18.8.0 build of a different codebase. This is re-authored against 20.07.001.
 *
 * STRATEGY: starve the SDK at a single point — but at the SDK's own enable predicate, not by
 * corrupting the Slot/AdvertisingConfig data classes. Both are "one seam, every provider cut at
 * once"; the predicate is strictly better (evidence below).
 *
 * The seam is `Lcom/naspers/advertising/baxterandroid/f;->d()Z`, which ships as:
 *
 *     .method public static d()Z
 *         .locals 1
 *         sget-boolean v0, Lcom/naspers/advertising/baxterandroid/f;->a:Z
 *         if-eqz v0, :cond_0
 *         sget-boolean v0, Lcom/naspers/advertising/baxterandroid/f;->b:Z
 *         if-eqz v0, :cond_0
 *         const/4 v0, 0x1
 *         return v0
 *         :cond_0
 *         const/4 v0, 0x0
 *         return v0
 *     .end method
 *
 * i.e. "Baxter is initialized (a) AND enabled (b == final true)". `a` is written true in exactly one
 * place — `baxterandroid/a.smali:331`, after the AdvertisingConfig fetch succeeds. So `d()` is the
 * SDK's own master "are ads live" query.
 *
 * Forcing it false is **not** an invented state: it is the state the SDK is in on every cold start
 * before the config resolves, so all 19 call sites already have an authored, exercised false branch
 * that does NOT load an ad:
 *
 *   - `domain/manager/x;->g` (the feed-entry load, 6 app call sites across
 *     `buyers/common/components/g`, `buyers/home/fragments/SearchExperienceFragment` (x2),
 *     `buyers/listings/viewHolders/j`, `buyers/listings/fragments/ABResultFragment`,
 *     `buyers/adDetails/activities/ImageGalleryNewActivity`) — jumps to `:cond_3`,
 *     "Baxter not Initialized", fires the caller's failure callback and returns **before** any
 *     provider is reached. No download, no ad-network request.
 *   - `domain/manager/x;->h` — the same shape for the full-page/native path.
 *   - `domain/manager/x;->e()` → returns null (interstitial display settings); `x;->i` likewise.
 *   - `ui/BaxterAdView.onMeasure/onLayout` — skip the ad child measurement / visibility-guard branch
 *     (the code that reads `d()`), so the view resolves its own size in the no-child path instead.
 *     Whether that makes the feed row collapse to zero height depends on the height the feed item
 *     layout gives `BaxterAdView`, which is **not** verified here — see AD_REMOVAL.md.
 *   - `data/providers/countDownTimer/d`, `f;->c()/e()` (adpv swipe / event interstitial),
 *     `app/common/viewModels/interstitial/f`, `ImageGalleryNewActivity`,
 *     `adpv/viewmodel/actionHandlers/u0`, `adpv/components/swipead/b`.
 *
 * Why this beats the literal "starve the Slot" construction:
 *   - `Slot` is a Kotlin data class over 8 provider slot-setting types with 8 accessors; emptying or
 *     nulling it hands each provider a null it may dereference → NPE risk, and the app's
 *     "empty slot vs missing slot" handling has to be re-derived per provider.
 *   - `AdvertisingConfig;->getEnabled()` is read at **exactly one** site
 *     (`x.smali:2469`, inside `x->g`). Forcing it false covers one path of one entry point — it does
 *     not reach interstitials, the full-page path, the swipe path or the view measurement.
 *   - `d()` covers all of them at once, and mutates no data: the config object stays populated and
 *     non-null, so the 11 direct `f;->d:LAdvertisingConfig;` readers keep working.
 *
 * Rejected: "drop the ad items in the feed and neuter BaxterAdView". That removes the box but leaves
 * the ad request firing — a privacy and traffic cost, not just a cosmetic one. This patch stops the
 * request instead, and the box collapses as a consequence.
 *
 * Residual (stated, not hidden): `x->g` emits the local tracking events `ad_requested` /
 * `ad_failed` before and on the disabled path. Those are local analytics callbacks — no ad-network
 * call is made — but they do still fire. See AD_REMOVAL.md "What is not verified".
 */

/**
 * `baxterandroid/f;->d()Z` — the SDK master enable predicate.
 *
 * Anchored on the class + method name (the names are obfuscated but stable within the 20.07.001
 * build, which `COMPATIBILITY_OLX_SOUTHASIA` pins). Uniqueness, verified against the decompiled
 * tree:
 *
 *   $ grep -rn "^\.class public abstract Lcom/naspers/advertising/baxterandroid/f;" --include=*.smali .
 *   smali/com/naspers/advertising/baxterandroid/f.smali:1:.class public abstract Lcom/naspers/advertising/baxterandroid/f;
 *   $ grep -rn "^\.method public static d()Z" --include=*.smali . | grep baxterandroid
 *   ./smali/com/naspers/advertising/baxterandroid/f.smali:1167:.method public static d()Z
 *
 * Exactly one class declaration and exactly one `static d()Z` in the whole SDK namespace. The
 * unrelated `f.smali` files in other sub-packages under `baxterandroid` are different classes and
 * are not reachable by this fingerprint. See AD_REMOVAL.md for the full command list.
 */
internal val southAsiaBaxterEnablePredicateFingerprint = Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    custom = { m, c ->
        c.type == "Lcom/naspers/advertising/baxterandroid/f;" && m.name == "d"
    },
)

@Suppress("unused")
val removeAdsSouthAsiaPatch = bytecodePatch(
    name = "Remove ads",
    description = "Removes ads from OLX India by disabling the in-house Baxter ad SDK at its own " +
        "enable predicate (com.naspers.advertising.baxterandroid.f.d()). Every ad path — native " +
        "feed, full-page, interstitial, swipe, adpv and the ad view itself — already checks this " +
        "predicate, so disabling it stops the ad requests rather than just hiding the boxes.",
) {
    compatibleWith(COMPATIBILITY_OLX_SOUTHASIA)

    execute {
        // `d()` is `.locals 1`, so v0 exists and is free — the returning register is safe to use.
        southAsiaBaxterEnablePredicateFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """.trimIndent(),
        )
        println(
            "OLX India remove ads: disabled the Baxter enable predicate " +
                "(baxterandroid.f.d()Z -> false); all 19 gated ad paths now take their " +
                "authored \"not initialized\" branch",
        )
    }
}
