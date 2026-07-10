package patches.myzong

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/*
 * Claim the MyZong daily "watch ad to claim" reward without watching the ad.
 *
 * The reward (DailyRewards "Free Mode" bubble game) is gated by a rewarded / rewarded-interstitial
 * ad in com.zong.customercare.admob.AdmobRewardedInterKt. Its show methods take the on-reward
 * callback as a Function0 and — importantly — the app ALREADY invokes that callback directly (no ad)
 * when there's no internet:
 *
 *     :goto_0                                   // isInternetAvailable == false
 *     invoke-interface {p1}, Function0;->invoke()   // grant reward
 *     return-void
 *
 * i.e. the claim is granted client-side, not server-verified against the ad. This patch makes
 * showRewardedAd / showRewardedInterstitial always take that path: invoke the reward callback
 * immediately and return, so the reward is claimed with no ad shown.
 *
 * Method names survive R8 (the class is obfuscated, the methods are not), so we anchor on the name +
 * (Activity, Function0) signature. Both are static: p0 = Activity, p1 = Function0.
 */

private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"

internal val showRewardedInterstitialFingerprint = Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/app/Activity;", FUNCTION0),
    custom = { m, _ -> m.name == "showRewardedInterstitial" },
)
internal val showRewardedAdFingerprint = Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/app/Activity;", FUNCTION0),
    custom = { m, _ -> m.name == "showRewardedAd" },
)

@Suppress("unused")
val unlockDailyRewardPatch = bytecodePatch(
    name = "Unlock daily reward (skip ad)",
    description = "Claim MyZong's daily 'watch ad to claim' reward without watching the ad. Forces the " +
        "rewarded-ad show methods to invoke the reward callback immediately — the same no-ad path the " +
        "app already uses when offline — so the reward is granted with no ad shown.",
) {
    compatibleWith(COMPATIBILITY_MYZONG)

    execute {
        // Invoke the on-reward Function0 (p1) straight away and return — grant, no ad.
        val grantAndReturn = """
            invoke-interface { p1 }, $FUNCTION0->invoke()Ljava/lang/Object;
            return-void
        """.trimIndent()

        fun stub(fingerprint: Fingerprint) {
            try {
                fingerprint.method.addInstructions(0, grantAndReturn)
            } catch (_: Exception) {
                // method absent in this build — skip
            }
        }

        stub(showRewardedInterstitialFingerprint)
        stub(showRewardedAdFingerprint)
    }
}
