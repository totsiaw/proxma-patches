package patches.simosa

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode

/*
 * Simosa 3.3.6 moved off com.google.android.gms.ads onto Google's next-gen ads SDK
 * (com.google.android.libraries.ads.mobile.sdk): every banner, interstitial and rewarded ad the app
 * shows now goes through this package, so the gms.ads stubs in the ad patches find nothing to do.
 *
 * These are the entry points the app itself calls. A stubbed load never fires its callback and a
 * stubbed preloader never holds an ad, so pollAd() is null and the holders (obfuscated `T7/a`..`e` on
 * 3.3.6) take their own "ad is not available" branch, which still reports back through onAdFinished.
 *
 * The rewarded preloader is the Daily Rewards check-in ad (`TAG_REWARDED_AD_DailyCheckIN`: the level-up and
 * streak-restore ads). "Remove ads & tracking" stubs it too, because the Daily Rewards tab showed it to
 * users who patch with Morphe Manager and leave the CLI-only "Remove daily check-in ads" off.
 *
 * Deliberately the smallest set of classes: Morphe Manager's dex writer breaks when too many classes are
 * modified (see the note in RemoveCheckInAdsPatch), and this set keeps the Manager-safe selection at
 * its 3.3.5 count (13 modified classes). The SDK's other loaders (`BannerAd`, `InterstitialAd`, `RewardedAd`,
 * `NativeAdLoader`) are only called by the AnyMind mediation adapter, which only runs inside an ad request.
 *
 * The SDK's own class names are not obfuscated. Older builds do not ship it, so nothing happens there;
 * on a build that does, a target that is missing fails the patch rather than silently leaving ads on.
 */
private const val SDK = "Lcom/google/android/libraries/ads/mobile/sdk/"
private const val CONTINUATION = "Lkotlin/coroutines/Continuation;"

internal val NEXT_GEN_ADS = mapOf(
    "${SDK}MobileAds\$Companion;" to setOf("initialize"),
    "${SDK}interstitial/InterstitialAdPreloader\$Companion;" to setOf("start", "pollAd"),
    "${SDK}banner/AdView;" to setOf("loadAd"),
)

internal val NEXT_GEN_REWARDED_ADS = mapOf(
    "${SDK}rewarded/RewardedAdPreloader\$Companion;" to setOf("start", "pollAd"),
)

internal fun BytecodePatchContext.stubNextGenAds(targets: Map<String, Set<String>>) {
    if (classDefByOrNull { it.type == "${SDK}MobileAds;" } == null) return

    targets.forEach { (type, names) ->
        val stubs = mutableClassDefBy(type).methods.filter { method ->
            method.name in names && method.implementation != null &&
                // The suspend overloads return through their continuation; the app does not call them.
                method.parameterTypes.lastOrNull()?.toString() != CONTINUATION
        }
        names.forEach { name ->
            check(stubs.any { it.name == name }) { "next-gen ads SDK has no $type->$name" }
        }
        stubs.forEach { method ->
            // "Remove ads & tracking" and "Remove daily check-in ads" both stub the rewarded preloader;
            // when both are selected, the second one finds it done already.
            if (method.isNextGenStub()) return@forEach
            method.addInstructions(
                0,
                when (method.returnType) {
                    "V" -> "return-void"
                    "Z" -> "const/4 v0, 0x0\nreturn v0"
                    else -> "const/4 v0, 0x0\nreturn-object v0"
                },
            )
        }
    }
}

private fun MutableMethod.isNextGenStub(): Boolean {
    val body = implementation?.instructions?.take(2)?.map { it.opcode } ?: return false
    return body.firstOrNull() == Opcode.RETURN_VOID ||
        body == listOf(Opcode.CONST_4, Opcode.RETURN) ||
        body == listOf(Opcode.CONST_4, Opcode.RETURN_OBJECT)
}
