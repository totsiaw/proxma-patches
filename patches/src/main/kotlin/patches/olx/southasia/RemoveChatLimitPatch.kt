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
 * SECOND GATE — reply restriction (added after the r2 test build, issue 61 feedback):
 *
 *   With only the quota mapper forced, the reporter saw the Elite banner disappear but tapping Chat
 *   on another smartphone listing still showed "the same error". That is the chat module's
 *   ReplyRestrictionRepositoryImpl (com.naspers.ragnarok.data.repository.replyRestriction):
 *
 *     - isReplyResticted(counterpartJid, itemId): true when Features.isReplyRestriction() is on, the
 *       account has NO existing conversation with that seller for that item, and the server-sent user
 *       preference "reply_restriction_user" is set. It then stores the server text
 *       "reply_restriction_threshold_error" which the caller shows instead of opening the chat
 *       (n.smali:11401 → AdpvAction J1 error; AdDetailCTAsFragment:3463). Because it only fires for a
 *       conversation that doesn't exist yet, it matches "works on one ad, error on the next".
 *     - getNewUserReplyRestricted(): the same gate for the "is_reply_restricted_new_user" preference
 *       (n.smali:10403, AdDetailCTAsFragment:3511), consulted only after isReplyRestrictedForNewUser.
 *
 *   Verified against the 20.07.001 smali: those are the only call sites of both methods, and both
 *   callers already have an authored "not restricted" path (return false → open chat). Both methods
 *   are forced to return false. isReplyRestrictedForNewUser is deliberately left alone: it really
 *   means "no conversation exists yet" and is also read by recommendations and CTA code for other
 *   purposes.
 *
 * Both are client-side gates. If OLX also refuses the message on its servers once the account is
 * over the limit, that is outside what a patch can change; this removes the in-app blocks.
 */

private const val REPLY_RESTRICTION_REPO =
    "Lcom/naspers/ragnarok/data/repository/replyRestriction/ReplyRestrictionRepositoryImpl;"

/** ReplyRestrictionRepositoryImpl.isReplyResticted(String, long)Z — sic, the app's own spelling. */
internal val southAsiaReplyRestrictedFingerprint = Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Ljava/lang/String;", "J"),
    custom = { m, c -> c.type == REPLY_RESTRICTION_REPO && m.name == "isReplyResticted" },
)

/** ReplyRestrictionRepositoryImpl.getNewUserReplyRestricted()Z. */
internal val southAsiaNewUserReplyRestrictedFingerprint = Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    custom = { m, c -> c.type == REPLY_RESTRICTION_REPO && m.name == "getNewUserReplyRestricted" },
)

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
        "the chat-quota and reply-restriction checks always report the chat as allowed, so tapping " +
        "Chat on a new listing opens the conversation instead of the Elite paywall or the chat " +
        "limit error. If the server also blocks chats over the limit, that part is outside the app.",
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

        // Both bodies declare .locals >= 2, so v0 is free. Returning false is the "not restricted"
        // value every caller already handles by opening the chat.
        southAsiaReplyRestrictedFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """.trimIndent(),
        )
        println(
            "OLX India remove chat limit: forced ReplyRestrictionRepositoryImpl.isReplyResticted " +
                "to false; reply_restriction_threshold_error is no longer shown for new chats",
        )

        southAsiaNewUserReplyRestrictedFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """.trimIndent(),
        )
        println(
            "OLX India remove chat limit: forced ReplyRestrictionRepositoryImpl." +
                "getNewUserReplyRestricted to false; the new-user reply restriction no longer blocks chats",
        )
    }
}
