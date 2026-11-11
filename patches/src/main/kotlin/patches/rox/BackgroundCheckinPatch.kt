package patches.rox

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

private const val JOB_SERVICE_CLASS = "Lapp/proxma/rox/CheckinJobService;"
private const val JOB_SERVICE = "app.proxma.rox.CheckinJobService"

private val checkinJobServiceManifestPatch = resourcePatch {
    finalize {
        document("AndroidManifest.xml").use { doc ->
            val application = doc.getElementsByTagName("application").item(0) as Element
            val services = doc.getElementsByTagName("service")
            if ((0 until services.length).none {
                    (services.item(it) as Element).getAttribute("android:name") == JOB_SERVICE
                }
            ) {
                application.appendChild(
                    doc.createElement("service").apply {
                        setAttribute("android:name", JOB_SERVICE)
                        setAttribute("android:permission", "android.permission.BIND_JOB_SERVICE")
                        setAttribute("android:exported", "false")
                    },
                )
            }
        }
    }
}

@Suppress("unused")
val backgroundCheckinPatch = bytecodePatch(
    name = "Auto check-in in the background",
    description = "Does the daily streak check-in without you opening ROX. Shortly after midnight " +
        "(Pakistan time) ROX is woken up once in the background; if today's check-in isn't done yet, the " +
        "app's own screens are started off-screen and the Check-in button is pressed there, then everything " +
        "is closed again. It retries up to three times a day. A notification says when it worked, or asks " +
        "you to tap it if it couldn't; the next time you open ROX a short message also says what the last " +
        "background try did. You must be signed in, and ROX must have been opened once after installing. " +
        "Force-stopping ROX stops it until the next time you open the app.",
) {
    compatibleWith(COMPATIBILITY_ROX)
    extendWith("extensions/extension.mpe")
    dependsOn(autoCheckinPatch, checkinJobServiceManifestPatch)

    execute {
        val onCreate = roxMainActivityOnCreateFingerprint.method
        val superCall = onCreate.instructions.toList().indexOfFirst { instruction ->
            if (instruction.opcode != Opcode.INVOKE_SUPER) {
                return@indexOfFirst false
            }
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                ?: return@indexOfFirst false
            reference.name == "onCreate" &&
                reference.parameterTypes.size == 1 &&
                reference.parameterTypes.first().toString() == "Landroid/os/Bundle;"
        }
        if (superCall < 0) {
            throw PatchException("MainActivity.onCreate has no invoke-super onCreate(Bundle) to anchor on")
        }

        onCreate.addInstructions(
            superCall + 1,
            "invoke-static {p0}, $JOB_SERVICE_CLASS->onActivityCreate(Landroid/app/Activity;)V",
        )
    }
}
