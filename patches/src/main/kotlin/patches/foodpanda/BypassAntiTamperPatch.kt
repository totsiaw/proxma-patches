package patches.foodpanda

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/*
 * foodpanda anti-tamper / integrity-block bypass.
 *
 * DeliveryHero's `app-security` module ("MAS" = Mobile App Security) runs every foreground activity
 * through a single gate before home/login. That gate reads ONE Boolean returned by the MAS phone /
 * integrity verifier `w9u.a(Continuation)` (obfuscated `Lw9u;->a(Ltx9;)Ljava/lang/Object;`):
 *   TRUE  = device/app trusted -> proceed,
 *   FALSE = launch `com.deliveryhero.app.security.ui.BlockedActivity`
 *           ("This device or application is not supported. Please re-install…").
 * A re-signed / sideloaded build fails MAS's app-id + installer-source + root integrity checks, so
 * the verifier returns FALSE and the block screen kills the app. Forcing that single choke point to
 * TRUE flips the whole gate to "trusted".
 *
 * The verifier is a Kotlin `suspend fun` returning a BOXED result (`Ljava/lang/Object;`), so we
 * return `Boolean.TRUE` (return-object), not a raw `Z` — identical boxing situation to the unlock
 * patch's isSubscribed targets. Returning a resolved Boolean at method entry is a valid synchronous
 * completion for a suspend fn: it is NOT COROUTINE_SUSPENDED, and the caller checks
 * `if-ne result, COROUTINE_SUSPENDED` before unboxing, so it proceeds straight to
 * `booleanValue()` -> true -> no block. No coroutine state-machine concern (we never suspend).
 *
 * Fingerprint is R8-name-independent: it anchors on the non-obfuscated
 * `com.deliveryhero.app.security.MasEvaluationException` that this method news up, plus the const
 * strings "MAS eval" / "MAS evaluation failed." — never on the minified holder class `w9u`.
 *
 * DEFENSE IN DEPTH (two independent layers, each keyed on a stable non-minified first-party type):
 *   1. masGate (`w9u.a`)  -> return Boolean.TRUE  — treat the device/app as trusted.
 *   2. blockScreenGuard (the orchestrator `dy1.a(Activity, Continuation)`, the ONLY consumer of the
 *      gate and the thing that launches BlockedActivity when the gate is FALSE) -> return Unit at
 *      entry, so the BlockedActivity launcher is never even constructed.
 * Layer 2 is anchored on the unique, non-obfuscated `com.deliveryhero.app.security.ui.BlockedActivity`
 * type the orchestrator guards on, so it survives even a build/R8 variant where the gate's
 * log-string anchors ("MAS eval" / "MAS evaluation failed.") drift and layer 1 fails to resolve.
 * Verified (26.38.1): the orchestrator is the sole caller of the gate and the sole producer of the
 * `startActivity(BlockedActivity)` launch — these are the only path to the block screen (no native,
 * no second gate), so either layer alone fully prevents the block. Both are best-effort; the patch
 * only errors if NEITHER anchor resolves (the app-security shape genuinely changed).
 *
 * This is an independent, user-facing patch; it does not touch the pandapro unlock patch.
 */
@Suppress("unused")
val bypassAntiTamperPatch = bytecodePatch(
    name = "Bypass anti-tamper (foodpanda)",
    description = "Bypasses foodpanda's DeliveryHero MAS integrity block — forces the master " +
        "security gate (`w9u.a`) to report the device/app as trusted AND short-circuits the " +
        "enforcement orchestrator that launches the block screen, so re-signed / sideloaded " +
        "builds no longer hit the \"This device or application is not supported\" BlockedActivity.",
) {
    compatibleWith(COMPATIBILITY_FOODPANDA)

    execute {
        var applied = false

        // Layer 1 — master MAS gate: suspend fn returning a boxed Boolean -> return Boolean.TRUE
        // (trusted). Returning a resolved Boolean at entry is a valid synchronous suspend completion
        // (not COROUTINE_SUSPENDED), so the orchestrator unboxes it straight to true -> no block.
        masGateFingerprint.matchOrNull()?.let { match ->
            match.method.addInstructions(
                0,
                """
                    sget-object v0, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                    return-object v0
                """.trimIndent(),
            )
            applied = true
        }

        // Layer 2 — enforcement orchestrator / launch site: suspend fn returning a boxed Unit on its
        // trusted path. Returning `Unit` at entry is a valid synchronous suspend completion, so the
        // lifecycle caller proceeds exactly as for a trusted device, while the gate is never queried
        // and the BlockedActivity launcher is never constructed. Independent of layer 1's resolution.
        blockScreenGuardFingerprint.matchOrNull()?.let { match ->
            match.method.addInstructions(
                0,
                """
                    sget-object v0, Lkotlin/Unit;->a:Lkotlin/Unit;
                    return-object v0
                """.trimIndent(),
            )
            applied = true
        }

        if (!applied) {
            error(
                "foodpanda: neither the MAS gate (MasEvaluationException / \"MAS eval\") nor the " +
                    "block-screen orchestrator (instance-of BlockedActivity) resolved — the " +
                    "DeliveryHero app-security shape changed; re-derive the anchors.",
            )
        }
    }
}
