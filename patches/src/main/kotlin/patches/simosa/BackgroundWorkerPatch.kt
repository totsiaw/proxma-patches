package patches.simosa

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

/*
 * Background daily check-in.
 *
 * Simosa 3.3.5 ships androidx.work 2.8+ and initialises it through androidx.startup, but it has no
 * worker subclass of its own, so there is nothing to hook: an earlier build of this feature pointed
 * at `com.jazz.jazzworld.presentation.ClaimWorker`, a class that only ever existed in manually
 * patched APKs, and the fingerprint missed on every stock install.
 *
 * So the worker is ours: `DailyCheckinWorker` lives in the extension dex, extends
 * androidx.work.Worker, and is enqueued as unique periodic work (`proxma_simosa_checkin`,
 * ExistingPeriodicWorkPolicy.KEEP, every 24 h, network required). Its doWork() runs
 * BackgroundCheckin.doCheckIn() and then WorkerNotify.notifyStatus().
 *
 * This patch only has to make the app call DailyCheckinWorker.schedule(context) once —
 * MainActivity.onCreate. One instruction, no registers touched beyond p0, no try/catch: if the
 * anchor is missing the patch fails loudly instead of shipping a build where nothing runs.
 */

private const val MAIN_ACTIVITY = "Lcom/jazz/jazzworld/presentation/ui/main/MainActivity;"
private const val DAILY_CHECKIN_WORKER = "Lapp/proxma/simosa/DailyCheckinWorker;"
private const val CONTEXT = "Landroid/content/Context;"
private const val BUNDLE = "Landroid/os/Bundle;"

/**
 * MainActivity.onCreate(Bundle) — `protected`, class name unobfuscated, in classes8. This is the
 * only anchor this patch needs.
 */
internal val mainActivityOnCreateFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PROTECTED),
    parameters = listOf(BUNDLE),
    custom = { method, classDef ->
        classDef.type == MAIN_ACTIVITY && method.name == "onCreate"
    },
)

private const val SYSTEM_FOREGROUND_SERVICE = "androidx.work.impl.foreground.SystemForegroundService"

/*
 * DailyCheckinWorker runs as a dataSync foreground service (WorkManager's setForegroundAsync), since
 * ColorOS kills a plain background job mid-run. Simosa declares WorkManager's SystemForegroundService
 * without a foregroundServiceType, and at targetSdk 34+ startForeground with a type the manifest does
 * not declare is refused, so declare dataSync on it (merged with any type already set). Simosa
 * already holds FOREGROUND_SERVICE and FOREGROUND_SERVICE_DATA_SYNC; they are added only if missing.
 */
private val foregroundServiceManifestPatch = resourcePatch {
    finalize {
        document("AndroidManifest.xml").use { doc ->
            val manifest = doc.documentElement
            val permissions = doc.getElementsByTagName("uses-permission")
            listOf(
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            ).forEach { permission ->
                if ((0 until permissions.length).none {
                        (permissions.item(it) as Element).getAttribute("android:name") == permission
                    }
                ) {
                    manifest.insertBefore(
                        doc.createElement("uses-permission").apply { setAttribute("android:name", permission) },
                        manifest.firstChild,
                    )
                }
            }

            val services = doc.getElementsByTagName("service")
            val service = (0 until services.length).map { services.item(it) as Element }
                .firstOrNull { it.getAttribute("android:name") == SYSTEM_FOREGROUND_SERVICE }
                ?: doc.createElement("service").apply {
                    setAttribute("android:name", SYSTEM_FOREGROUND_SERVICE)
                    setAttribute("android:exported", "false")
                    doc.getElementsByTagName("application").item(0).appendChild(this)
                }
            val types = service.getAttribute("android:foregroundServiceType")
                .split('|').filter { it.isNotBlank() }.toMutableSet()
            types += "dataSync"
            service.setAttribute("android:foregroundServiceType", types.joinToString("|"))
        }
    }
}

@Suppress("unused")
val backgroundWorkerPatch = bytecodePatch(
    name = "Background daily check-in worker",
    description = "Runs the Simosa daily check-in from a 24-hour WorkManager job that this patch " +
        "adds to the app, with two-tier token auth, stale-token retry, a per-account day latch " +
        "and a status notification.",
) {
    compatibleWith(COMPATIBILITY_SIMOSA)
    extendWith("extensions/extension.mpe")
    dependsOn(foregroundServiceManifestPatch)

    execute {
        val onCreate = mainActivityOnCreateFingerprint.method
        val instructions = onCreate.instructions.toList()

        // Insert right after the super call, before the splash screen is installed: the worker
        // enqueue is idempotent (KEEP) and cheap, and it must happen even if onCreate throws later.
        val superCall = instructions.indexOfFirst { instruction ->
            if (instruction.opcode != Opcode.INVOKE_SUPER) {
                return@indexOfFirst false
            }
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                ?: return@indexOfFirst false
            reference.name == "onCreate" &&
                reference.parameterTypes.size == 1 &&
                reference.parameterTypes.first().toString() == BUNDLE
        }
        if (superCall < 0) {
            throw PatchException(
                "MainActivity.onCreate has no invoke-super onCreate(Bundle) to anchor the worker on",
            )
        }

        // p0 is the activity itself, and an Activity is a Context, so the call needs no register
        // of its own and cannot clobber the method's live values.
        onCreate.addInstructions(
            superCall + 1,
            "invoke-static {p0}, $DAILY_CHECKIN_WORKER->schedule($CONTEXT)V",
        )
    }
}
