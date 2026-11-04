package patches.olx.southasia

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags

/*
 * OLX India 20.07.001 — remove the "new chats locked / Upgrade to Elite" chat limit.
 *
 * What the reporter sees (issue 61): after a number of chats started with sellers (they report
 * ~80–100), tapping Chat on a listing no longer opens the conversation. Instead the app shows an
 * "New Chats Locked for 30 Days!" / "Don't Wait. Unlock Chat with Elite" paywall sheet
 * (strings elite_pitch_chat_locked_for_30_days, elite_pitch_dont_wait_unlock_chat_with_elite,
 * elite_seller_pitch_chat_trigger_cta). It blocks the whole chat feature, not a single seller.
 *
 * Where the lock decision is made, in this build:
 *
 *   The chat CTA path asks the domain use case CheckChatQuotaUseCase (impl class
 *   com.olxgroup.panamera.domain.entitlements.usecase.e) whether the account may start a new chat.
 *   The use case fetches the "CHAT_QUOTA_LIMIT" entitlement and maps it, in the static mapper
 *   e.b(entity), to one of exactly two results of the sealed CheckChatQuotaUseCase$ChatQuotaResult:
 *
 *     - usecase.a  = Allowed       (object singleton, toString "Allowed")
 *     - usecase.b  = LimitReached(ChatLimitInfo)
 *
 *   Verified against the decompiled 20.07.001 tree:
 *     - LimitReached (usecase.b) is constructed in exactly one place, e.smali:515, inside e.b().
 *     - e.b() is called from exactly one place, e.a():960 (the suspend check function, which already
 *       returns Allowed on any repository error).
 *     - The result is branched on in exactly one place, the adpv chat helper
 *       com.olxgroup.panamera.app.buyers.adpv.helpers.n (n.smali:4653 `instance-of ... usecase/b`).
 *       When the result is Allowed the helper takes its already-authored, exercised path that opens
 *       the chat; only the LimitReached branch builds the f1 / AdpvAction$OpenChatLockPaywall signal
 *       that raises the paywall.
 *
 * STRATEGY: force the mapper to always report Allowed. Returning the Allowed singleton is not an
 * invented state — it is the state every account is in before it hits the limit, and the exact value
 * e.a() already substitutes when the entitlement call fails, so every consumer has an authored,
 * exercised "allowed" branch. The ChatLimitInfo object is simply never produced, so the paywall is
 * never reached and the chat opens as normal.
 *
 * This is a client-side gate only. If OLX also refuses the chat on its servers once the account is
 * over the limit, that is outside what a patch can change; this removes the in-app block.
 */

/** com.olxgroup.panamera.domain.entitlements.usecase.a — the `Allowed` ChatQuotaResult singleton. */
private const val ALLOWED_RESULT = "Lcom/olxgroup/panamera/domain/entitlements/usecase/a;"

/**
 * CheckChatQuotaUseCase's static result mapper, `e.b(entity.b)ChatQuotaResult`.
 *
 * Anchored on the fully-qualified declaring class + method name (both obfuscated but stable within
 * the 20.07.001 build that COMPATIBILITY_OLX_SOUTHASIA pins) plus the exact parameter and return
 * descriptors. `Lcom/olxgroup/panamera/domain/entitlements/usecase/e;` is a unique type, and this is
 * the only `public static b` on it with this shape, so the fingerprint resolves to exactly one
 * method. The body is `.locals 9`, so v0 is a free register for the returned reference.
 */
internal val southAsiaChatQuotaResultFingerprint = Fingerprint(
    returnType = "Lcom/olxgroup/panamera/domain/entitlements/usecase/CheckChatQuotaUseCase\$ChatQuotaResult;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Lcom/olxgroup/panamera/domain/entitlements/entity/b;"),
    custom = { m, c ->
        c.type == "Lcom/olxgroup/panamera/domain/entitlements/usecase/e;" && m.name == "b"
    },
)

@Suppress("unused")
val removeChatLimitSouthAsiaPatch = bytecodePatch(
    name = "Remove chat limit",
    description = "Removes OLX India's \"new chats locked / Upgrade to Elite\" chat limit by making " +
        "the chat-quota check always report the chat as allowed, so tapping Chat opens the " +
        "conversation instead of the Elite paywall. The limit is enforced client-side here; if the " +
        "server also blocks chats over the limit, that part is outside the app.",
) {
    compatibleWith(COMPATIBILITY_OLX_SOUTHASIA)

    execute {
        // Resolving throws on failure — the patch must fail loudly rather than ship a still-locked app.
        southAsiaChatQuotaResultFingerprint.method.addInstructions(
            0,
            """
                sget-object v0, $ALLOWED_RESULT->a:$ALLOWED_RESULT
                return-object v0
            """.trimIndent(),
        )
        println(
            "OLX India remove chat limit: forced CheckChatQuotaUseCase (e.b) to return Allowed; " +
                "the chat lock paywall (elite_pitch_chat_locked_for_30_days) is no longer reached",
        )
    }
}
