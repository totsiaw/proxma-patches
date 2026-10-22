package patches.simosa

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/*
 * Background worker patch: hooks MainActivity.onCreate to schedule the daily
 * auto-claim + check-in WorkManager job.
 *
 * All runtime logic lives in extension Java classes:
 *   - ClaimWorker.java   — Worker subclass (schedule, doWork, ladder + checkin)
 *   - BackgroundCheckin.java — HTTP check-in with two-tier token auth
 *   - WorkerNotify.java  — notification + day-tracking
 *   - AutoClaim.java     — per-day latch, jittered auto-claim
 *
 * The patch itself only injects one call: schedule the worker on app launch.
 */

private const val CLAIM_WORKER = "Lapp/proxma/simosa/ClaimWorker;"
private const val CONTEXT = "Landroid/content/Context;"

/**
 * The app's MainActivity.onCreate — hook to schedule the background worker on
 * first launch. Anchored on the unobfuscated class name.
 */
internal val mainActivityOnCreateFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PROTECTED),
    parameters = listOf("Landroid/os/Bundle;"),
    custom = { m, c ->
        c.type == "Lcom/jazz/jazzworld/presentation/ui/main/MainActivity;" &&
            m.name == "onCreate"
    },
)

@Suppress("unused")
val backgroundWorkerPatch = bytecodePatch(
    name = "Background daily check-in worker",
    description = "Schedules a WorkManager job that auto-claims the daily reward ladder " +
        "and runs the Simosa daily check-in, with a status notification.",
) {
    compatibleWith(COMPATIBILITY_SIMOSA)
    extendWith("extensions/extension.mpe")

    execute {
        val onCreate = mainActivityOnCreateFingerprint.method
        val insns = onCreate.instructions.toList()

        val lastReturn = insns.indexOfLast { it.opcode == Opcode.RETURN_VOID }
        if (lastReturn >= 0) {
            onCreate.addInstructions(
                lastReturn,
                """
                    invoke-virtual {p0}, Landroid/app/Activity;->getApplicationContext()$CONTEXT
                    move-result-object v0
                    invoke-static {v0}, $CLAIM_WORKER->schedule($CONTEXT)V
                """.trimIndent(),
            )
        }
    }
}
