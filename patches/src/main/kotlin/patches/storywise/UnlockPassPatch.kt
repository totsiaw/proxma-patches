package patches.storywise

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode

internal val firestoreDocConverterFingerprint = Fingerprint(
    returnType = "Ljava/util/HashMap;",
    filters = listOf(string("Provided serverTimestampBehavior value must not be null.")),
)

@Suppress("unused")
val unlockPassPatch = bytecodePatch(
    name = "Unlock pass",
    description = "Unlocks paid content (Monthly Pass: lessons, tutor, reports) by forging " +
        "pass-expiry and active fields in Firestore document maps the app reads.",
) {
    compatibleWith(COMPATIBILITY_STORYWISE)

    execute {
        val method = firestoreDocConverterFingerprint.method
        val insns = method.instructions

        val retIdx = insns.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
        check(retIdx >= 0) { "storywise: return-object not found in doc converter" }

        method.addInstructionsWithLabels(
            retIdx,
            """
                iget-object v0, p0, Lj4/p;->b:Lp4/h;
                if-eqz v0, :sw_b_end
                iget-object v0, v0, Lp4/h;->a:Lp4/m;
                if-eqz v0, :sw_b_end
                invoke-virtual {v0}, Lp4/m;->c()Ljava/lang/String;
                move-result-object v0
                if-eqz v0, :sw_b_end
                const-string v1, "users/"
                invoke-virtual {v0, v1}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
                move-result v1
                if-nez v1, :sw_b_scope_ok
                const-string v1, "subscription"
                invoke-virtual {v0, v1}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v1
                if-nez v1, :sw_b_scope_ok
                const-string v1, "pass"
                invoke-virtual {v0, v1}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v1
                if-nez v1, :sw_b_scope_ok
                const-string v1, "entitle"
                invoke-virtual {v0, v1}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v1
                if-nez v1, :sw_b_scope_ok
                const-string v1, "premium"
                invoke-virtual {v0, v1}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v1
                if-nez v1, :sw_b_scope_ok
                const-string v1, "billing"
                invoke-virtual {v0, v1}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v1
                if-nez v1, :sw_b_scope_ok
                const-string v1, "member"
                invoke-virtual {v0, v1}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v1
                if-nez v1, :sw_b_scope_ok
                const-string v1, "plan"
                invoke-virtual {v0, v1}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v1
                if-nez v1, :sw_b_scope_ok
                goto :sw_b_end
                :sw_b_scope_ok
                if-nez p1, :sw_b_modify
                new-instance p1, Ljava/util/HashMap;
                invoke-direct {p1}, Ljava/util/HashMap;-><init>()V
                new-instance p0, Lw3/q;
                const/4 v2, 0x0
                const-wide v0, 0xf4865700L
                invoke-direct {p0, v2, v0, v1}, Lw3/q;-><init>(IJ)V
                const-string v0, "pass_expires_at"
                invoke-interface {p1, v0, p0}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                const-string v0, "is_active"
                sget-object v1, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                invoke-interface {p1, v0, v1}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                const-string v0, "subscription_id"
                const-string v1, "premium"
                invoke-interface {p1, v0, v1}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                const-string v0, "billing_status"
                const-string v1, "active"
                invoke-interface {p1, v0, v1}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                goto :sw_b_end
                :sw_b_modify
                const-string v1, "users/"
                invoke-virtual {v0, v1}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
                move-result v1
                if-eqz v1, :sw_b_expiry_keys
                const-string v0, "pass_expires_at"
                invoke-interface {p1, v0}, Ljava/util/Map;->containsKey(Ljava/lang/Object;)Z
                move-result v1
                if-nez v1, :sw_b_ensure_active
                new-instance p0, Lw3/q;
                const/4 v2, 0x0
                const-wide v0, 0xf4865700L
                invoke-direct {p0, v2, v0, v1}, Lw3/q;-><init>(IJ)V
                const-string v0, "pass_expires_at"
                invoke-interface {p1, v0, p0}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                :sw_b_ensure_active
                const-string v0, "is_active"
                invoke-interface {p1, v0}, Ljava/util/Map;->containsKey(Ljava/lang/Object;)Z
                move-result v1
                if-nez v1, :sw_b_expiry_keys
                sget-object v1, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                invoke-interface {p1, v0, v1}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                :sw_b_expiry_keys
                const-string v1, "pass_expires_at"
                invoke-interface {p1, v1}, Ljava/util/Map;->containsKey(Ljava/lang/Object;)Z
                move-result v2
                if-eqz v2, :sw_b_ek2
                invoke-interface {p1, v1}, Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v2
                if-nez v2, :sw_b_ek1_typed
                new-instance p0, Lw3/q;
                const/4 v2, 0x0
                const-wide v0, 0xf4865700L
                invoke-direct {p0, v2, v0, v1}, Lw3/q;-><init>(IJ)V
                const-string v0, "pass_expires_at"
                invoke-interface {p1, v0, p0}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                goto :sw_b_ek2
                :sw_b_ek1_typed
                instance-of v0, v2, Lw3/q;
                if-eqz v0, :sw_b_ek1_date
                new-instance p0, Lw3/q;
                const/4 v2, 0x0
                const-wide v0, 0xf4865700L
                invoke-direct {p0, v2, v0, v1}, Lw3/q;-><init>(IJ)V
                const-string v0, "pass_expires_at"
                invoke-interface {p1, v0, p0}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                goto :sw_b_ek2
                :sw_b_ek1_date
                instance-of v0, v2, Ljava/util/Date;
                if-eqz v0, :sw_b_ek1_long
                new-instance p0, Ljava/util/Date;
                const-wide v0, 0x3bb2cc3d800L
                invoke-direct {p0, v0, v1}, Ljava/util/Date;-><init>(J)V
                const-string v0, "pass_expires_at"
                invoke-interface {p1, v0, p0}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                goto :sw_b_ek2
                :sw_b_ek1_long
                instance-of v0, v2, Ljava/lang/Long;
                if-eqz v0, :sw_b_ek1_int
                const-wide v0, 0x3bb2cc3d800L
                invoke-static {v0, v1}, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;
                move-result-object p0
                const-string v0, "pass_expires_at"
                invoke-interface {p1, v0, p0}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                goto :sw_b_ek2
                :sw_b_ek1_int
                instance-of v0, v2, Ljava/lang/Integer;
                if-eqz v0, :sw_b_ek1_str
                const v0, 0x7fffffff
                invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
                move-result-object p0
                const-string v0, "pass_expires_at"
                invoke-interface {p1, v0, p0}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                goto :sw_b_ek2
                :sw_b_ek1_str
                instance-of v0, v2, Ljava/lang/String;
                if-eqz v0, :sw_b_ek2
                const-string p0, "2099-12-31T23:59:59Z"
                const-string v0, "pass_expires_at"
                invoke-interface {p1, v0, p0}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                :sw_b_ek2
                const-string v1, "validTill"
                invoke-interface {p1, v1}, Ljava/util/Map;->containsKey(Ljava/lang/Object;)Z
                move-result v2
                if-eqz v2, :sw_b_ek3
                invoke-interface {p1, v1}, Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v2
                if-nez v2, :sw_b_ek2_typed
                new-instance p0, Lw3/q;
                const/4 v2, 0x0
                const-wide v0, 0xf4865700L
                invoke-direct {p0, v2, v0, v1}, Lw3/q;-><init>(IJ)V
                const-string v0, "validTill"
                invoke-interface {p1, v0, p0}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                goto :sw_b_ek3
                :sw_b_ek2_typed
                instance-of v0, v2, Lw3/q;
                if-eqz v0, :sw_b_ek2_other
                new-instance p0, Lw3/q;
                const/4 v2, 0x0
                const-wide v0, 0xf4865700L
                invoke-direct {p0, v2, v0, v1}, Lw3/q;-><init>(IJ)V
                const-string v0, "validTill"
                invoke-interface {p1, v0, p0}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                goto :sw_b_ek3
                :sw_b_ek2_other
                instance-of v0, v2, Ljava/lang/String;
                if-eqz v0, :sw_b_ek3
                const-string p0, "2099-12-31T23:59:59Z"
                const-string v0, "validTill"
                invoke-interface {p1, v0, p0}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                :sw_b_ek3
                const-string v1, "expires_at"
                invoke-interface {p1, v1}, Ljava/util/Map;->containsKey(Ljava/lang/Object;)Z
                move-result v2
                if-eqz v2, :sw_b_ek4
                invoke-interface {p1, v1}, Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v2
                if-nez v2, :sw_b_ek3_typed
                new-instance p0, Lw3/q;
                const/4 v2, 0x0
                const-wide v0, 0xf4865700L
                invoke-direct {p0, v2, v0, v1}, Lw3/q;-><init>(IJ)V
                const-string v0, "expires_at"
                invoke-interface {p1, v0, p0}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                goto :sw_b_ek4
                :sw_b_ek3_typed
                instance-of v0, v2, Lw3/q;
                if-eqz v0, :sw_b_ek3_other
                new-instance p0, Lw3/q;
                const/4 v2, 0x0
                const-wide v0, 0xf4865700L
                invoke-direct {p0, v2, v0, v1}, Lw3/q;-><init>(IJ)V
                const-string v0, "expires_at"
                invoke-interface {p1, v0, p0}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                goto :sw_b_ek4
                :sw_b_ek3_other
                instance-of v0, v2, Ljava/lang/String;
                if-eqz v0, :sw_b_ek4
                const-string p0, "2099-12-31T23:59:59Z"
                const-string v0, "expires_at"
                invoke-interface {p1, v0, p0}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                :sw_b_ek4
                const-string v1, "expiresAt"
                invoke-interface {p1, v1}, Ljava/util/Map;->containsKey(Ljava/lang/Object;)Z
                move-result v2
                if-eqz v2, :sw_b_bools
                invoke-interface {p1, v1}, Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v2
                if-nez v2, :sw_b_ek4_typed
                new-instance p0, Lw3/q;
                const/4 v2, 0x0
                const-wide v0, 0xf4865700L
                invoke-direct {p0, v2, v0, v1}, Lw3/q;-><init>(IJ)V
                const-string v0, "expiresAt"
                invoke-interface {p1, v0, p0}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                goto :sw_b_bools
                :sw_b_ek4_typed
                instance-of v0, v2, Lw3/q;
                if-eqz v0, :sw_b_ek4_other
                new-instance p0, Lw3/q;
                const/4 v2, 0x0
                const-wide v0, 0xf4865700L
                invoke-direct {p0, v2, v0, v1}, Lw3/q;-><init>(IJ)V
                const-string v0, "expiresAt"
                invoke-interface {p1, v0, p0}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                goto :sw_b_bools
                :sw_b_ek4_other
                instance-of v0, v2, Ljava/lang/String;
                if-eqz v0, :sw_b_bools
                const-string p0, "2099-12-31T23:59:59Z"
                const-string v0, "expiresAt"
                invoke-interface {p1, v0, p0}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                :sw_b_bools
                const-string v0, "is_active"
                invoke-interface {p1, v0}, Ljava/util/Map;->containsKey(Ljava/lang/Object;)Z
                move-result v1
                if-eqz v1, :sw_b_bool2
                sget-object v1, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                invoke-interface {p1, v0, v1}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                :sw_b_bool2
                const-string v0, "isActive"
                invoke-interface {p1, v0}, Ljava/util/Map;->containsKey(Ljava/lang/Object;)Z
                move-result v1
                if-eqz v1, :sw_b_end
                sget-object v1, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                invoke-interface {p1, v0, v1}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
            """.trimIndent(),
            ExternalLabel("sw_b_end", insns[retIdx]),
        )
    }
}
