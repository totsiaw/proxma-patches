package patches.simosa

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/*
 * Simosa daily check-in: claim the day's reward without the ad gate.
 *
 * The screen is a Compose screen over an obfuscated ViewModel (`Li4/v`). Opening the daily-reward
 * screen starts `v.G2()`, which runs one suspend lambda (obfuscated `Li4/v$n`, the only
 * `invokeSuspend(Ljava/lang/Object;)Ljava/lang/Object;` in the app that logs " _pendingCheckInAdShow ").
 * That lambda reads the check-in state out of three flags and publishes a "pending check-in ad" event
 * into the screen state flow:
 *
 *     isRestore == false && isLevelReached == false && canCheckInToday == true
 *         -> publish Li4/b(c = Li4/c.a, d = Li4/d.a)   // "pending check-in ad show"
 *     otherwise
 *         -> publish Li4/b(c = Li4/c.b, ...)           // restore / level-up paths (not touched here)
 *
 * The UI reacts to that event by showing the daily check-in ad, and only after the ad is dismissed
 * does it call the ViewModel's check-in (obfuscated `X1()`, the method that logs
 * "access_token missing, skip check-in", calls the daily-reward `performCheckIn` API and then
 * auto-claims the reward for the level). So today: open the screen -> ad -> claim.
 *
 * Fix: at the point where the daily-check-in event is published, call the check-in directly and jump
 * past the publish. The ad never fires; the reward is still granted (same server calls). Everything
 * else in that lambda (the restore and level-up events) is left alone, so the "missed days" restore
 * sheet and the day-10/20/30 milestone claim keep working exactly as before.
 *
 * Anchored on the log string plus the SGET_OBJECT of the day's check-in event enum constant, so the
 * only hard-coded obfuscated names are the lambda's field holding the ViewModel and the enum with the
 * event type — both of which are release-pinned by COMPATIBILITY_SIMOSA (3.3.4.2).
 */

/** `Li4/c` — the check-in event type enum; its `a` constant is the "daily check-in" event. */
private const val CHECK_IN_EVENT_ENUM = "Li4/c;"

internal val dailyCheckInPublishFingerprint = Fingerprint(
    returnType = "Ljava/lang/Object;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(string(" _pendingCheckInAdShow ")),
    custom = { m, _ -> m.name == "invokeSuspend" },
)

@Suppress("unused")
val autoCheckInPatch = bytecodePatch(
    name = "Auto check-in & claim daily reward",
    description = "Claims the daily check-in reward on its own: opening the daily-rewards screen no " +
        "longer waits for the check-in ad, the reward is granted right away. The missed-days " +
        "restore and the day-10/20/30 milestone claim are untouched.",
) {
    compatibleWith(COMPATIBILITY_SIMOSA)

    execute {
        val method = dailyCheckInPublishFingerprint.method
        val instructions = method.instructions.toList()

        // The day's check-in event is published exactly once: `sget-object <reg>, Li4/c;->a:Li4/c;`.
        val publishIndex = instructions.indexOfFirst { insn ->
            if (insn.opcode != Opcode.SGET_OBJECT) return@indexOfFirst false
            val reference = (insn as? ReferenceInstruction)?.reference as? FieldReference ?: return@indexOfFirst false
            reference.definingClass == CHECK_IN_EVENT_ENUM && reference.name == "a"
        }
        check(publishIndex >= 0) { "daily check-in publish site not found" }

        // The publish block ends with its own `goto` to the end of the state evaluation; reuse it as
        // the jump target so no new control flow is invented.
        val skipTo = instructions.withIndex()
            .first { (index, insn) -> index > publishIndex && insn.opcode == Opcode.GOTO }
            .value

        // At this point p0 is the lambda, whose field `e` is the ViewModel; p1 already holds the
        // state flow (not the ViewModel) and v1 is a dead boolean, so load the ViewModel into v1.
        method.addInstructionsWithLabels(
            publishIndex,
            """
                iget-object v1, p0, Li4/v${'$'}n;->e:Li4/v;
                invoke-virtual {v1}, Li4/v;->X1()V
                goto :proxma_auto_check_in
            """.trimIndent(),
            ExternalLabel("proxma_auto_check_in", skipTo),
        )
    }
}
