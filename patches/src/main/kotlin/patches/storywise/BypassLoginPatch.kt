package patches.storywise

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel

@Suppress("unused")
val bypassLoginPatch = bytecodePatch(
    name = "Bypass login",
    description = "Restores a signed-in user from an embedded assets/sw_cred.json " +
        "(pre-formatted Firebase credential file) into SharedPreferences before " +
        "FirebaseAuth initialises, so the constructor picks up the stored user. " +
        "Falls back to anonymous sign-in (F4/h + zzacq.zza) if the asset is missing.",
) {
    compatibleWith(COMPATIBILITY_STORYWISE)

    execute {
        // =====================================================================
        // A1: firebase_auth plugin init — getPluginConstantsForFirebaseApp.
        //   v1.0.5: the plugin is de-obfuscated; this hook lives on
        //   Lio/flutter/plugins/firebase/auth/c; and takes the FirebaseApp as
        //   Lu4/h; (fields a:Context, b:String name — same layout as the old
        //   Lw3/g;). The method is a clean standalone method (.registers 6);
        //   locals v0..v3 are free, p1 = FirebaseApp.
        //
        //   Strategy: BEFORE FirebaseAuth is used, write the embedded credential
        //   JSON into the auth SharedPreferences so the constructor's restore
        //   path picks it up. On a later launch the data is already persisted,
        //   so the asset read is skipped. The asset read is wrapped in a
        //   try/catch, so a missing/garbage asset degrades to the anonymous
        //   sign-in fallback instead of crashing.
        //
        //   NOTE (v1.0.5 scope): the v1.0.3 patch also injected into the
        //   auth-state StreamHandler (A2) and the Google signInWithProvider flow
        //   (A3). In v1.0.5 both were merged into packed-switch dispatcher
        //   methods (LP6/d;->onListen and Lio/flutter/plugins/firebase/auth/o;->j),
        //   where blind injection can corrupt register state. Those two hooks are
        //   intentionally omitted here; the credential-restore + anonymous
        //   fallback below is self-contained and covers the primary flow.
        //
        //   sw_cred.json format (user fills real values):
        //   {
        //     "firebaseUser": "<full FIREBASE_USER SharedPref JSON>",
        //     "tokenResponse": "<GET_TOKEN_RESPONSE JSON>",
        //     "userId": "<Firebase UID>"
        //   }
        // =====================================================================
        val a1Class = mutableClassDefBy("Lio/flutter/plugins/firebase/auth/c;")
        val a1Method = a1Class.methods.first { it.name == "getPluginConstantsForFirebaseApp" }
        val a1First = a1Method.instructions[0]

        a1Method.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p1, Lu4/h;->a:Landroid/content/Context;
                iget-object v1, p1, Lu4/h;->b:Ljava/lang/String;
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
                invoke-static {p1}, Lcom/google/firebase/auth/FirebaseAuth;->getInstance(Lu4/h;)Lcom/google/firebase/auth/FirebaseAuth;
                move-result-object v0
                iget-object v1, v0, Lcom/google/firebase/auth/FirebaseAuth;->f:LF4/p;
                if-nez v1, :sw_a1_skip
                new-instance v1, LF4/h;
                invoke-direct {v1, v0}, LF4/h;-><init>(Lcom/google/firebase/auth/FirebaseAuth;)V
                iget-object v2, v0, Lcom/google/firebase/auth/FirebaseAuth;->k:Ljava/lang/String;
                iget-object v3, v0, Lcom/google/firebase/auth/FirebaseAuth;->e:Lcom/google/android/gms/internal/firebase-auth-api/zzacq;
                iget-object v0, v0, Lcom/google/firebase/auth/FirebaseAuth;->a:Lu4/h;
                invoke-virtual {v3, v0, v1, v2}, Lcom/google/android/gms/internal/firebase-auth-api/zzacq;->zza(Lu4/h;LG4/O;Ljava/lang/String;)Lcom/google/android/gms/tasks/Task;
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
    }
}
