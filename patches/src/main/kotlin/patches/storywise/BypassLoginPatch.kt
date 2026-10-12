package patches.storywise

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

@Suppress("unused")
val bypassLoginPatch = bytecodePatch(
    name = "Bypass login",
    description = "Restores a signed-in user from an embedded assets/sw_cred.json " +
        "(pre-formatted Firebase credential file) into SharedPreferences before " +
        "FirebaseAuth initialises, so the constructor picks up the stored user. " +
        "Falls back to anonymous sign-in (H3/h + zzacq.zza) if the asset is missing. " +
        "Also short-circuits the Google sign-in provider flow (O5/p pswitch_5) to " +
        "reuse the current user.",
) {
    compatibleWith(COMPATIBILITY_STORYWISE)

    execute {
        // =====================================================================
        // A1: firebase_core plugin init — getPluginConstantsForFirebaseApp (LO5/e;)
        //   .locals 4 → v0..v3 free.  p1 = FirebaseApp (Lw3/g;)
        //
        //   Strategy: BEFORE calling FirebaseAuth.getInstance(), write the
        //   embedded credential JSON into the auth SharedPreferences so the
        //   constructor's restore path picks it up.  On second launch the data
        //   is already persisted, so the asset read is skipped.
        //
        //   sw_cred.json format (user fills real values):
        //   {
        //     "firebaseUser": "<full FIREBASE_USER SharedPref JSON>",
        //     "tokenResponse": "<GET_TOKEN_RESPONSE JSON>",
        //     "userId": "<Firebase UID>"
        //   }
        // =====================================================================
        val a1Class = mutableClassDefBy("LO5/e;")
        val a1Method = a1Class.methods.first { it.name == "getPluginConstantsForFirebaseApp" }
        val a1First = a1Method.instructions[0]

        a1Method.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p1, Lw3/g;->a:Landroid/content/Context;
                iget-object v1, p1, Lw3/g;->b:Ljava/lang/String;
                const-string v2, "com.google.firebase.auth.api.Store."
                invoke-virtual {v2, v1}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v1
                const/4 v2, 0x0
                invoke-virtual {v0, v1, v2}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
                move-result-object v1
                const-string v2, "com.google.firebase.auth.FIREBASE_USER"
                const/4 v3, 0x0
                invoke-interface {v1, v2, v3}, Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v2
                if-nez v2, :sw_cred_done
                invoke-virtual {v0}, Landroid/content/Context;->getAssets()Landroid/content/res/AssetManager;
                move-result-object v2
                const-string v3, "sw_cred.json"
                invoke-virtual {v2, v3}, Landroid/content/res/AssetManager;->open(Ljava/lang/String;)Ljava/io/InputStream;
                move-result-object v2
                invoke-virtual {v2}, Ljava/io/InputStream;->available()I
                move-result v3
                new-array v0, v3, [B
                invoke-virtual {v2, v0}, Ljava/io/InputStream;->read([B)I
                invoke-virtual {v2}, Ljava/io/InputStream;->close()V
                new-instance v2, Ljava/lang/String;
                const-string v3, "UTF-8"
                invoke-direct {v2, v0, v3}, Ljava/lang/String;-><init>([BLjava/lang/String;)V
                new-instance v0, Lorg/json/JSONObject;
                invoke-direct {v0, v2}, Lorg/json/JSONObject;-><init>(Ljava/lang/String;)V
                const-string v2, "firebaseUser"
                invoke-virtual {v0, v2}, Lorg/json/JSONObject;->getString(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v2
                invoke-interface {v1}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences${'$'}Editor;
                move-result-object v3
                const-string v1, "com.google.firebase.auth.FIREBASE_USER"
                invoke-interface {v3, v1, v2}, Landroid/content/SharedPreferences${'$'}Editor;->putString(Ljava/lang/String;Ljava/lang/String;)Landroid/content/SharedPreferences${'$'}Editor;
                const-string v1, "userId"
                invoke-virtual {v0, v1}, Lorg/json/JSONObject;->getString(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v1
                const-string v2, "com.google.firebase.auth.GET_TOKEN_RESPONSE."
                invoke-virtual {v2, v1}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v1
                const-string v2, "tokenResponse"
                invoke-virtual {v0, v2}, Lorg/json/JSONObject;->getString(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                invoke-interface {v3, v1, v0}, Landroid/content/SharedPreferences${'$'}Editor;->putString(Ljava/lang/String;Ljava/lang/String;)Landroid/content/SharedPreferences${'$'}Editor;
                invoke-interface {v3}, Landroid/content/SharedPreferences${'$'}Editor;->apply()V
                goto :sw_cred_done
                move-exception v0
                :sw_cred_done
                invoke-static {p1}, Lcom/google/firebase/auth/FirebaseAuth;->getInstance(Lw3/g;)Lcom/google/firebase/auth/FirebaseAuth;
                move-result-object v0
                iget-object v1, v0, Lcom/google/firebase/auth/FirebaseAuth;->f:LH3/p;
                if-nez v1, :sw_a1_skip
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

        // Add try/catch around the asset-reading section (indices 13..46 in injected block).
        // The catch handler is the move-exception instruction right before :sw_cred_done.
        // try_start = index 13 (invoke-virtual Context.getAssets)
        // try_end   = index 47 (move-exception — first instruction NOT covered)
        // handler   = index 47 (move-exception — consumes the exception)
        val a1Impl = a1Method.implementation!!
        a1Impl.addCatch(
            "Ljava/lang/Exception;",
            a1Impl.newLabelForIndex(13),
            a1Impl.newLabelForIndex(47),
            a1Impl.newLabelForIndex(47),
        )

        // =====================================================================
        // A2: auth-state StreamHandler — onListen in LO5/b;
        //   .locals 11 → v3..v10 free at the CopyOnWriteArrayList.add anchor
        //
        //   Simplified: skip entirely if auth.f is non-null (embedded or anon).
        // =====================================================================
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

        // =====================================================================
        // A3: signInWithProvider (Google button) in O5/p.i()
        //   .locals 13 → v0..v12 free.
        //   Anchor: check-cast p1, LO5/M; (unique in method)
        //   After auth resolution: v0 = FirebaseAuth, v2 = O5/q reply wrapper
        //
        //   Simplified guard: if auth.f is non-null, short-circuit with the
        //   existing user wrapped as I3/X → Tasks.forResult.  No isAnonymous
        //   check — any authenticated user is reused.
        // =====================================================================
        val a3Class = mutableClassDefBy("LO5/p;")
        val a3Method = a3Class.methods.first {
            it.name == "i" && it.parameterTypes.size == 2
        }
        val a3Insns = a3Method.instructions

        val mCastIdx = a3Insns.indexOfFirst { instr ->
            instr.opcode == Opcode.CHECK_CAST &&
                ((instr as? ReferenceInstruction)?.reference as? TypeReference)?.type == "LO5/M;"
        }
        check(mCastIdx >= 0) { "storywise: check-cast LO5/M; anchor not found in O5/p.i" }

        val authInvokeIdx = (mCastIdx until a3Insns.size).first { idx ->
            val instr = a3Insns[idx]
            val ref = (instr as? ReferenceInstruction)?.reference as? MethodReference
            ref != null && ref.definingClass == "LO5/e;" && ref.name == "b" &&
                ref.returnType == "Lcom/google/firebase/auth/FirebaseAuth;"
        }
        val moveResultIdx = authInvokeIdx + 1
        check(a3Insns[moveResultIdx].opcode == Opcode.MOVE_RESULT_OBJECT) {
            "storywise: expected move-result-object after O5/e.b in signInWithProvider"
        }

        a3Method.addInstructionsWithLabels(
            moveResultIdx + 1,
            """
                iget-object v3, v0, Lcom/google/firebase/auth/FirebaseAuth;->f:LH3/p;
                if-eqz v3, :sw_a3_signin
                check-cast v3, LI3/f;
                const/4 v4, 0x0
                iput-boolean v4, v3, LI3/f;->B:Z
                new-instance v4, LI3/X;
                invoke-direct {v4, v3}, LI3/X;-><init>(LI3/f;)V
                invoke-static {v4}, Lcom/google/android/gms/tasks/Tasks;->forResult(Ljava/lang/Object;)Lcom/google/android/gms/tasks/Task;
                move-result-object p1
                goto :sw_a3_wire
                :sw_a3_signin
                new-instance v3, LH3/h;
                invoke-direct {v3, v0}, LH3/h;-><init>(Lcom/google/firebase/auth/FirebaseAuth;)V
                iget-object v4, v0, Lcom/google/firebase/auth/FirebaseAuth;->k:Ljava/lang/String;
                iget-object v5, v0, Lcom/google/firebase/auth/FirebaseAuth;->e:Lcom/google/android/gms/internal/firebase-auth-api/zzacq;
                iget-object v6, v0, Lcom/google/firebase/auth/FirebaseAuth;->a:Lw3/g;
                invoke-virtual {v5, v6, v3, v4}, Lcom/google/android/gms/internal/firebase-auth-api/zzacq;->zza(Lw3/g;LI3/N;Ljava/lang/String;)Lcom/google/android/gms/tasks/Task;
                move-result-object p1
                :sw_a3_wire
                new-instance p2, LI4/M;
                const/16 v3, 0xa
                invoke-direct {p2, v2, v3}, LI4/M;-><init>(Ljava/lang/Object;I)V
                invoke-virtual {p1, p2}, Lcom/google/android/gms/tasks/Task;->addOnCompleteListener(Lcom/google/android/gms/tasks/OnCompleteListener;)Lcom/google/android/gms/tasks/Task;
                return-void
            """.trimIndent(),
        )
    }
}
