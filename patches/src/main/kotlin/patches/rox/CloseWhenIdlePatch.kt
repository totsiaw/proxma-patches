package patches.rox

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val IDLE_CLOSE = "Lapp/proxma/rox/IdleClose;"

@Suppress("unused")
val closeWhenIdlePatch = bytecodePatch(
    name = "Close when idle in background",
    description = "Closes ROX once it has been out of sight for three minutes and nothing is playing, " +
        "so its timers, ad and analytics code stop using the battery. Notifications still arrive. " +
        "Opening ROX after that is a fresh start.",
) {
    compatibleWith(COMPATIBILITY_ROX)
    extendWith("extensions/extension.mpe")

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
            "invoke-static {p0}, $IDLE_CLOSE->onActivityCreate(Landroid/app/Activity;)V",
        )
    }
}
