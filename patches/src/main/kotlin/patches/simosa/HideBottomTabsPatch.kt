package patches.simosa

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION = "extensions/extension.mpe"

/**
 * The bottom bar itself — `DashboardBottomNavRevamp` (`AppBottomNavRevamp.kt:67`), the single
 * composable every main-screen bottom bar render goes through.
 *
 * It takes the tab list as its third parameter (`bottomList: List<TilesListItem>`), so filtering
 * that list hides tabs without deleting any screen. Anchored on the Compose trace literal rather
 * than the obfuscated `J3.k->q(...)` name; the same literal is present in 3.3.5 and 3.3.4.2.
 */
internal val dashboardBottomNavFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    parameters = listOf(
        "Z",
        "Ljava/lang/String;",
        "Ljava/util/List;",
        "Lkotlin/jvm/functions/Function2;",
        "Z",
        "Z",
        "Landroidx/compose/runtime/Composer;",
        "I",
    ),
    filters = listOf(
        app.morphe.patcher.string(
            "com.jazz.jazzworld.presentation.navigation.bottomnavigation." +
                "DashboardBottomNavRevamp (AppBottomNavRevamp.kt:67)",
        ),
    ),
)

/*
 * Injected two instructions only, for two hard-won reasons:
 *
 *  1. This patcher's snippet assembler does not resolve `pN` parameter registers — a snippet that
 *     touches one is silently truncated at that instruction, and the patch still reports success.
 *     Registers v0..v15 are the only ones it accepts.
 *  2. For the same reason a snippet with labels or branches cannot be used: everything from the
 *     first `:label` onward is dropped without an error being raised.
 *
 * So the loop lives in the extension class, and the injection point is chosen to avoid parameters:
 * the Compose prologue copies `bottomList` (parameter p2) into v10 and then null-checks it through
 * `Intrinsics.checkNotNullParameter(v10, "bottomList")`. Injecting immediately after that check
 * gives us the list in a register the assembler accepts, and the check itself guarantees v10 holds
 * the list and not something else.
 */
private const val FILTER_TABS_SMALI = """
    invoke-static {v10}, Lapp/proxma/simosa/HideTabs;->filter(Ljava/util/List;)Ljava/util/List;
    move-result-object v10
"""

@Suppress("unused")
val hideBottomTabsPatch = bytecodePatch(
    name = "Remove Shop, Social+ and Lifestyle tabs",
    description = "Removes the Shop, Social+ and Lifestyle tabs from the bottom bar, leaving Home and " +
        "Account. Nothing is deleted: those screens still exist and still open if the app routes " +
        "to them (a deep link, a tile, a notification) — they just no longer take a tab.",
) {
    compatibleWith(COMPATIBILITY_SIMOSA)
    // Builder scope, not execute{} — extendWith is a BytecodePatchBuilder call, and the patcher
    // only merges the extension dex into the output when it is registered at build time. Inside
    // execute{} it compiles but silently merges nothing.
    extendWith(EXTENSION)

    execute {
        val method = dashboardBottomNavFingerprint.method
        val instructions = method.implementation!!.instructions.toList()
        val nameIndex = instructions.indexOfFirst { instruction ->
            instruction.opcode == Opcode.CONST_STRING &&
                ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                "bottomList"
        }
        check(nameIndex >= 0) { "bottomList parameter check not found in bottom bar composable" }
        // nameIndex = const-string "bottomList", nameIndex + 1 = checkNotNullParameter(v10, name)
        method.addInstructions(nameIndex + 2, FILTER_TABS_SMALI.trimIndent())
    }
}
