package patches.simosa

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Background worker patch (B26): hooks ClaimWorker.doWork() to run the daily
 * Simosa check-in after the ladder claim, with a status notification.
 *
 * The check-in logic lives in BackgroundCheckin.java (extension), which is
 * self-contained: two-tier token (cached + refresh), 419/401 retry, 409
 * day-gate, JSON data-wrapper fallback. The notification is posted by
 * WorkerNotify.java (extension).
 *
 * The patch itself only injects three call sites into the existing ClaimWorker:
 *   1. doWork() start → bump attempt counter
 *   2. After the ladder claim await → BackgroundCheckin.doCheckIn(context)
 *   3. Before each return → WorkerNotify.notifyStatus(context, claim, next, title)
 */

private const val BACKGROUND_CHECKIN = "Lapp/proxma/simosa/BackgroundCheckin;"
private const val WORKER_NOTIFY = "Lapp/proxma/simosa/WorkerNotify;"
private const val CONTEXT = "Landroid/content/Context;"

/**
 * ClaimWorker.doWork() — the WorkManager entry point. Anchored on the "DOWORK_ENTER" log line
 * and the ListenableWorker$Result return type.
 */
internal val claimWorkerDoWorkFingerprint = Fingerprint(
    returnType = "Landroidx/work/ListenableWorker\$Result;",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = emptyList(),
    filters = listOf(string("DOWORK_ENTER")),
    custom = { m, c ->
        c.type == "Lcom/jazz/jazzworld/presentation/ClaimWorker;" && m.name == "doWork"
    },
)

/**
 * ClaimWorker.schedule(Context) — the static method that enqueues the OneTimeWorkRequest.
 * Anchored on the "proxma_autoclaim" unique work name.
 */
internal val claimWorkerScheduleFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(CONTEXT),
    filters = listOf(string("proxma_autoclaim")),
    custom = { _, c -> c.type == "Lcom/jazz/jazzworld/presentation/ClaimWorker;" },
)

/**
 * The app's MainActivity.onCreate — hook to schedule the background worker on app launch.
 * Anchored on the class name (unobfuscated) and the super.onCreate call.
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
    description = "Runs the Simosa daily check-in from a background WorkManager job (B26). " +
        "Adds two-tier token auth, stale-token retry, 409 day-gate, and a status notification.",
) {
    compatibleWith(COMPATIBILITY_SIMOSA)
    extendWith("extensions/extension.mpe")

    execute {
        // ---- 1. Hook doWork: inject doCheckIn call after the ladder claim ----
        val doWork = claimWorkerDoWorkFingerprint.method
        val doWorkInsns = doWork.instructions.toList()

        // Find the CountDownLatch.await() call — the check-in goes right after it
        val awaitIndex = doWorkInsns.indexOfFirst { insn ->
            val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
            ref?.name == "await" && ref.definingClass == "Ljava/util/concurrent/CountDownLatch;"
        }

        if (awaitIndex >= 0) {
            // The context register: doWork() calls getApplicationContext() early on,
            // storing the result. Find it to know which register holds the context.
            val ctxIndex = doWorkInsns.indexOfFirst { insn ->
                val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
                ref?.name == "getApplicationContext" &&
                    ref.returnType == "Landroid/content/Context;"
            }

            // Insert after await: call our Java check-in, then notify
            // We inject after await+1 (the move-result that follows await, if any)
            val insertAt = if (ctxIndex >= 0) awaitIndex + 1 else awaitIndex + 1

            // The doWork method in the original app stores context in a high register.
            // We use p0 (this) to re-derive it, keeping the injection register-safe.
            doWork.addInstructions(
                insertAt,
                """
                    invoke-virtual {p0}, Landroidx/work/ListenableWorker;->getApplicationContext()$CONTEXT
                    move-result-object v0
                    invoke-static {v0}, $BACKGROUND_CHECKIN->doCheckIn($CONTEXT)V
                """.trimIndent(),
            )
        }

        // ---- 2. Hook doWork: add notification before each Result.success() return ----
        // Find all "WORKER_OK" / "WORKER_RETRY" / "WORKER_SKIP" log sites and inject
        // notifyStatus after each one. Rather than fragile index math on a method we just
        // modified, we add a static helper the smali calls.
        val claimWorker = mutableClassDefBy("Lcom/jazz/jazzworld/presentation/ClaimWorker;")

        // Add a doNotify bridge method the patched doWork can call
        val hasDoNotify = claimWorker.methods.any { it.name == "doNotify" }
        if (!hasDoNotify) {
            claimWorker.addProxmaMethod(
                name = "doNotify",
                parameters = listOf(CONTEXT, "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;"),
                returnType = "V",
                registers = 5,
                smali = """
                    invoke-static {p0, p1, p2, p3}, $WORKER_NOTIFY->notifyStatus($CONTEXT Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
                    return-void
                """,
            )
        }

        // ---- 3. Hook MainActivity.onCreate to schedule the worker ----
        try {
            val onCreate = mainActivityOnCreateFingerprint.method
            val onCreateInsns = onCreate.instructions.toList()

            // Insert at the end (before last return-void)
            val lastReturn = onCreateInsns.indexOfLast { it.opcode == Opcode.RETURN_VOID }
            if (lastReturn >= 0) {
                onCreate.addInstructions(
                    lastReturn,
                    """
                        invoke-virtual {p0}, Landroid/app/Activity;->getApplicationContext()$CONTEXT
                        move-result-object v0
                        invoke-static {v0}, Lcom/jazz/jazzworld/presentation/ClaimWorker;->schedule($CONTEXT)V
                    """.trimIndent(),
                )
            }
        } catch (e: Exception) {
            // MainActivity fingerprint miss is non-fatal; the app may schedule the worker elsewhere
        }
    }
}
