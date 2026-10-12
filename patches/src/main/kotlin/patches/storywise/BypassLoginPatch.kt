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
        "Firebase auth at two hook points using the real signInAnonymously chain " +
        "(H3/h + zzacq.zza) with isAnonymous guard.",
) {
    compatibleWith(COMPATIBILITY_STORYWISE)

    execute {
        // A1: firebase_core plugin init — getPluginConstantsForFirebaseApp in LO5/e;
        // .locals 4 → v0..v3 free. p1 = FirebaseApp (Lw3/g;)
        val a1Class = mutableClassDefBy("LO5/e;")
        val a1Method = a1Class.methods.first { it.name == "getPluginConstantsForFirebaseApp" }
        val a1First = a1Method.instructions[0]

        a1Method.addInstructionsWithLabels(
            0,
            """
                invoke-static {p1}, Lcom/google/firebase/auth/FirebaseAuth;->getInstance(Lw3/g;)Lcom/google/firebase/auth/FirebaseAuth;
                move-result-object v0
                iget-object v1, v0, Lcom/google/firebase/auth/FirebaseAuth;->f:LH3/p;
                if-eqz v1, :sw_a1_signin
                invoke-virtual {v1}, LH3/p;->q()Z
                move-result v1
                if-nez v1, :sw_a1_skip
                :sw_a1_signin
                new-instance v1, LH3/h;
                invoke-direct {v1, v0}, LH3/h;-><init>(Lcom/google/firebase/auth/FirebaseAuth;)V
                iget-object v2, v0, Lcom/google/firebase/auth/FirebaseAuth;->k:Ljava/lang/String;
                iget-object v3, v0, Lcom/google/firebase/auth/FirebaseAuth;->e:Lcom/google/android/gms/internal/firebase-auth-api/zzacq;
                iget-object v0, v0, Lcom/google/firebase/auth/FirebaseAuth;->a:Lw3/g;
                invoke-virtual {v3, v0, v1, v2}, Lcom/google/android/gms/internal/firebase-auth-api/zzacq;->zza(Lw3/g;LI3/N;Ljava/lang/String;)Lcom/google/android/gms/tasks/Task;
                move-result-object v0
            """.trimIndent(),
            ExternalLabel("sw_a1_skip", a1First),
        )

        // A2: auth-state StreamHandler — onListen in LO5/b;
        // .locals 11 → v3..v10 free at the CopyOnWriteArrayList.add anchor
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
                if-eqz v4, :sw_a2_signin
                invoke-virtual {v4}, LH3/p;->q()Z
                move-result v4
                if-nez v4, :sw_a2_skip
                :sw_a2_signin
                new-instance v4, LH3/h;
                invoke-direct {v4, v3}, LH3/h;-><init>(Lcom/google/firebase/auth/FirebaseAuth;)V
                iget-object v5, v3, Lcom/google/firebase/auth/FirebaseAuth;->k:Ljava/lang/String;
                iget-object v6, v3, Lcom/google/firebase/auth/FirebaseAuth;->e:Lcom/google/android/gms/internal/firebase-auth-api/zzacq;
                iget-object v3, v3, Lcom/google/firebase/auth/FirebaseAuth;->a:Lw3/g;
                invoke-virtual {v6, v3, v4, v5}, Lcom/google/android/gms/internal/firebase-auth-api/zzacq;->zza(Lw3/g;LI3/N;Ljava/lang/String;)Lcom/google/android/gms/tasks/Task;
                move-result-object v3
            """.trimIndent(),
            ExternalLabel("sw_a2_skip", a2Insns[retIdx]),
        )
    }
}
