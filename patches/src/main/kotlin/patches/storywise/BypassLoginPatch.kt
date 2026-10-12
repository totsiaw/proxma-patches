package patches.storywise

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val bypassLoginPatch = bytecodePatch(
    name = "Bypass login",
    description = "Auto sign-in on launch to bypass the login wall. Triggers anonymous " +
        "Firebase auth at two hook points (firebase_core init + auth-state listener).",
) {
    compatibleWith(COMPATIBILITY_STORYWISE)

    execute {
        // A1: firebase_core plugin init — getPluginConstantsForFirebaseApp in LO5/e;
        val a1Class = mutableClassDefBy("LO5/e;")
        val a1Method = a1Class.methods.first { it.name == "getPluginConstantsForFirebaseApp" }
        val a1First = a1Method.instructions[0]

        a1Method.addInstructionsWithLabels(
            0,
            """
                invoke-static {p1}, Lcom/google/firebase/auth/FirebaseAuth;->getInstance(Lw3/g;)Lcom/google/firebase/auth/FirebaseAuth;
                move-result-object v0
                iget-object v1, v0, Lcom/google/firebase/auth/FirebaseAuth;->f:LH3/p;
                if-nez v1, :sw_a1_skip
                invoke-virtual {v0}, Lcom/google/firebase/auth/FirebaseAuth;->c()Lcom/google/android/gms/tasks/Task;
                move-result-object v2
            """.trimIndent(),
            ExternalLabel("sw_a1_skip", a1First),
        )

        // A2: auth-state StreamHandler — onListen in LO5/b;
        val a2Class = mutableClassDefBy("LO5/b;")
        val a2Method = a2Class.methods.first {
            it.name == "onListen" && it.parameterTypes.size == 2
        }
        val a2Insns = a2Method.instructions

        val addIdx = a2Insns.indexOfLast { instr ->
            val ref = (instr as? ReferenceInstruction)?.reference as? MethodReference
            ref != null &&
                ref.definingClass == "Ljava/util/concurrent/CopyOnWriteArrayList;" &&
                ref.name == "add"
        }
        check(addIdx >= 0) { "storywise: CopyOnWriteArrayList.add anchor not found in onListen" }

        val retIdx = (addIdx + 1 until a2Insns.size).first {
            a2Insns[it].opcode == Opcode.RETURN_VOID
        }

        a2Method.addInstructionsWithLabels(
            retIdx,
            """
                iget-object v3, p0, LO5/b;->b:Ljava/lang/Object;
                check-cast v3, Lcom/google/firebase/auth/FirebaseAuth;
                iget-object v4, v3, Lcom/google/firebase/auth/FirebaseAuth;->f:LH3/p;
                if-nez v4, :sw_a2_skip
                invoke-virtual {v3}, Lcom/google/firebase/auth/FirebaseAuth;->c()Lcom/google/android/gms/tasks/Task;
                move-result-object v5
            """.trimIndent(),
            ExternalLabel("sw_a2_skip", a2Insns[retIdx]),
        )
    }
}
