package patches.foodpanda

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * foodpanda (issue #19 + #23):
 * (1) PandaMart "CrossSell" popup — forced, reappearing bottom sheet on order-tracking screen.
 * (2) DeliveryHero adtech SDK banner ads — all placements (OTP, home, discovery, reels) via
 *     `getCreatives()` → empty list. Anchored on stable SDK class name, not obfuscated names.
 * (3) Forced review prompt — DeliveryHero's in-app rating SDK launches `InAppRatingActivity`
 *     which calls `E()` to kick off the Play ReviewManager flow. Stubbing `E()` to return-void
 *     prevents the rating dialog from ever appearing. Anchor: the stable non-obfuscated
 *     `InAppRatingActivity` class + `setContentView` call in its `onCreate` (confirms the method
 *     shape without relying on the obfuscated `E` name).
 */
private const val ADTECH_CREATIVES = "Lcom/deliveryhero/adtechsdk/data/model/CreativesResponse;"

// The Compose lambda `invoke(Object, Object)Object` that renders the CrossSell bottom sheet.
internal val crossSellSheetFingerprint = Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
    filters = listOf(string("CrossSellBottomSheet: shown with ")),
)

// adtech ad-list getter.
internal val creativesGetterFingerprint = Fingerprint(
    returnType = "Ljava/util/List;",
    parameters = listOf(),
    custom = { m, c -> c.type == ADTECH_CREATIVES && m.name == "getCreatives" },
)

private const val IN_APP_RATING_ACTIVITY = "Lcom/deliveryhero/inapprating/InAppRatingActivity;"

/**
 * The method in InAppRatingActivity that launches the Play ReviewManager flow.
 * InAppRatingActivity.onCreate calls one no-arg void method after setContentView — that is the
 * launcher (`E()` in this build). We anchor on InAppRatingActivity's class type + the
 * setContentView call that precedes it in onCreate, then resolve the next no-arg void call to stub.
 * Simpler and more stable: just find the no-arg void virtual method in InAppRatingActivity that
 * contains a `requestReviewFlow`/GMS Task call — keyed on it calling into a GMS Task type.
 *
 * Because InAppRatingActivity is a stable non-obfuscated name, we use a custom fingerprint that
 * directly matches the class and a void-returning no-arg public final instance method whose body
 * contains a call to `com.google.android.gms.tasks.Task` — the Play Review API entrypoint.
 */
internal val inAppRatingLauncherFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/google/android/gms/tasks/Task;",
        ),
    ),
    custom = { _, c -> c.type == IN_APP_RATING_ACTIVITY },
)

@Suppress("unused")
val removeAdsPatch = bytecodePatch(
    name = "Remove ads",
    description = "Removes foodpanda's adtech banner ads (all placements: order-tracking, home, " +
        "discovery, reels) and the forced PandaMart \"CrossSell\" popup, and suppresses the " +
        "forced in-app review prompt (DeliveryHero InAppRatingActivity).",
) {
    compatibleWith(COMPATIBILITY_FOODPANDA)

    execute {
        // (1) Force the CrossSell sheet's visibility boolean false at its render gate.
        val sheet = crossSellSheetFingerprint.method
        val insns = sheet.instructions
        val boolIdx = insns.indexOfFirst {
            val r = (it as? ReferenceInstruction)?.reference as? MethodReference
            r != null && r.definingClass == "Ljava/lang/Boolean;" && r.name == "booleanValue"
        }
        check(boolIdx >= 0) { "foodpanda: CrossSell booleanValue() gate not found" }
        val ifIdx = (boolIdx + 1 until insns.size).first { insns[it].opcode == Opcode.IF_EQZ }
        val reg = (insns[ifIdx] as OneRegisterInstruction).registerA
        sheet.addInstructions(ifIdx, "const/4 v$reg, 0x0")

        // (2) adtech creatives -> empty list (no ad ever renders: OTP, home, discovery, reels).
        creativesGetterFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
                move-result-object p0
                return-object p0
            """.trimIndent(),
        )

        // (3) Suppress forced review prompt — stub the InAppRatingActivity launcher to return-void.
        inAppRatingLauncherFingerprint.matchAllOrNull()?.forEach { match ->
            match.method.addInstructions(0, "return-void")
        }
    }
}
