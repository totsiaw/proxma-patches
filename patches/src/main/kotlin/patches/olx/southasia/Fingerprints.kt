package patches.olx.southasia

import app.morphe.patcher.Fingerprint

/**
 * The manifest-declared Application of OLX India 20.07.001.
 *
 * ```
 * $ aapt dump xmltree olx-southasia-20.07.001.apk AndroidManifest.xml
 * E: application
 *   A: android:name(0x01010003)="com.olxgroup.panamera.app.application.DeloreanApplication"
 * ```
 *
 * The `com.olx.pk` fingerprint (`Lcom/olx/pk/OlxApplication;`) does not match anything in this dex —
 * that namespace is absent from the build. Anchored here on the real, un-obfuscated class name and
 * `onCreate()V` (exactly one occurrence, verified in the decompiled smali):
 *
 * ```
 * .method public final onCreate()V
 *     .locals 0
 *     invoke-virtual {p0}, Lcom/olxgroup/panamera/app/application/DeloreanApplication;->k()V
 *     return-void
 * .end method
 * ```
 *
 * ### Register trap — read before wiring this up
 *
 * `onCreate()` is **`.locals 0`**: a single `invoke-virtual` on `p0` and no local registers at all.
 * The `com.olx.pk` patch injects an 8-instruction block that uses `v0`/`v1` (its own KDoc relies on
 * "a large method, so v0/v1 are free locals" — true there, false here). Dropping that block in
 * verbatim would produce an invalid method. Either raise `.locals` first, or inject one
 * register-free call and keep the logic in a helper:
 *
 * ```
 * invoke-static {p0}, L<ext>/OlxSouthAsiaDarkMode;->install(Landroid/content/Context;)V
 * ```
 *
 * ### Currently unreferenced, on purpose
 *
 * No patch consumes this anchor yet. The `com.olx.pk` dark-mode technique does **not** transfer:
 * this build ships no app-authored night resources (no `values-night` / `drawable-night`, and none
 * of its own palette colours has a `-night` variant), so a
 * `UiModeManager.setApplicationNightMode(MODE_NIGHT_YES)` flip is a no-op for the app's own UI and
 * a partial-darkening regression for the few library surfaces that do have night values. Dark mode
 * for this app has to be a colour-constant rewrite, which is its own card.
 *
 * The anchor is committed so that card starts from a verified hook instead of re-deriving one.
 * Full evidence: `/home/ubuntu/morphe-ai/olx-southasia/PORT_PLAN.md`, section 1.
 *
 * The `Build.VERSION.SDK_INT >= 31` guard the card asked for is **not** encoded here: it is only
 * required by `setApplicationNightMode` (API 31+ vs this app's `minSdk` 23), and that call is the
 * rejected route. A colour-constant rewrite touches no API-31 symbol. See PORT_PLAN.md §3.3.
 */
internal val southAsiaApplicationOnCreateFingerprint = Fingerprint(
    returnType = "V",
    parameters = listOf(),
    custom = { m, c ->
        c.type == "Lcom/olxgroup/panamera/app/application/DeloreanApplication;" && m.name == "onCreate"
    },
)
