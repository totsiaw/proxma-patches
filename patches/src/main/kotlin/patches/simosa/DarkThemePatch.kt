package patches.simosa

import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

/*
 * Dark (black) theme for Simosa.
 *
 * Why the previous resource patch could not work: Simosa's whole UI is Jetpack Compose. The XML theme
 * (`Theme.CleanArchitecture` in res/values/styles.xml) only paints the window underneath the Compose
 * surface, and every screen/sheet/dialog Composable paints its own background over it. Rewriting the
 * XML theme therefore changed nothing a user could see — which is what the reporters on issue #38 said
 * ("I didn't notice a dark theme or black colour anywhere in the app").
 *
 * Where the colours actually come from (verified on 3.3.5, and identically on 3.3.4.2):
 *  - The app's Material3 theme builds its ColorScheme from
 *    `androidx.compose.material3.ColorSchemeKt.lightColorScheme`. Only a handful of brand colours are
 *    passed explicitly (primary / primaryContainer / secondary and their on-colours); background,
 *    surface, onSurface, outline … are left to the library's light defaults.
 *  - On Android 12+ the theme prefers wallpaper-based dynamic colour and calls
 *    `DynamicTonalPaletteKt.dynamicLightColorScheme(Context)` instead — the LIGHT variant, so even a
 *    device in dark mode got a light scheme.
 *
 * Fix: point both builders at their dark counterparts and change nothing else. No colour is invented
 * and no individual token is darkened by hand: the app keeps its own brand colours (passed explicitly,
 * untouched), while everything it takes from the library default flips to the Material3 dark palette —
 * background/surface near-black, onSurface/onBackground light — so text stays readable. Screens,
 * sheets, dialogs and menus the app paints from the theme follow automatically.
 *
 * Deliberately NOT touched: the panels the app paints with a fixed white background and dark text
 * (several cards/panels are hand-coloured, not theme-coloured). They stay light and stay readable.
 *
 * The rewrite is only made when the dark builder is proven to exist in the same library class that is
 * actually in the APK (same class, same descriptor), so a Compose version bump fails the patch loudly
 * instead of shipping an APK that would throw NoSuchMethodError at launch.
 */

private const val COLOR_SCHEME_KT = "Landroidx/compose/material3/ColorSchemeKt;"
private const val DYNAMIC_PALETTE_KT = "Landroidx/compose/material3/DynamicTonalPaletteKt;"

/** `lightColorScheme-C-Xl9yA$default` -> `darkColorScheme-C-Xl9yA$default` (suffix preserved). */
private fun darkCounterpart(name: String): String? = when {
    name.startsWith("lightColorScheme-") -> name.replaceFirst("lightColorScheme-", "darkColorScheme-")
    name == "dynamicLightColorScheme" -> "dynamicDarkColorScheme"
    else -> null
}

/** The light-scheme builder calls a method makes, if any. */
private fun Method.lightSchemeCalls(): List<MethodReference> =
    (implementation?.instructions ?: emptyList())
    .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
    .filter { reference ->
        (reference.definingClass == COLOR_SCHEME_KT && reference.name.startsWith("lightColorScheme-")) ||
            (reference.definingClass == DYNAMIC_PALETTE_KT && reference.name.startsWith("dynamicLightColorScheme"))
    }

/** Rewrites every light-scheme call in this method, reusing the opcode and register list as-is. */
private fun MutableMethod.shiftToDarkScheme() {
    val replacements = mutableListOf<Pair<Int, BuilderInstruction>>()
    instructions.forEachIndexed { index, insn ->
        val reference = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: return@forEachIndexed
        val newName = darkCounterpart(reference.name) ?: return@forEachIndexed
        val newReference = ImmutableMethodReference(
            reference.definingClass,
            newName,
            reference.parameterTypes,
            reference.returnType,
        )
        replacements += index to when (insn) {
            is Instruction3rc ->
                BuilderInstruction3rc(insn.opcode, insn.startRegister, insn.registerCount, newReference)
            is Instruction35c -> BuilderInstruction35c(
                insn.opcode,
                insn.registerCount,
                insn.registerC,
                insn.registerD,
                insn.registerE,
                insn.registerF,
                insn.registerG,
                newReference,
            )
            else -> error("unexpected instruction format ${insn.opcode} in $definingClass->$name")
        }
    }
    check(replacements.isNotEmpty()) { "no light-scheme call left in $definingClass->$name" }
    replacements.forEach { (index, replacement) -> replaceInstruction(index, replacement) }
}

@Suppress("unused")
val darkThemePatch = bytecodePatch(
    name = "Dark theme (black background)",
    description = "Makes Simosa a dark app: screens, sheets, dialogs and menus use the dark palette " +
        "(near-black background, light text) instead of the light one, including on Android 12+ " +
        "where the app would otherwise take a light colour scheme from the wallpaper. The app's own " +
        "red/orange branding and its white cards are left alone so text on them stays readable.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SIMOSA)

    execute {
        // Collect the app's own call sites first: mutating a class while the patcher walks the class
        // list is not safe, and the Compose library itself must not be touched.
        val callers = mutableListOf<ClassDef>()
        classDefForEach { classDef ->
            if (!classDef.type.startsWith("Landroidx/") &&
                classDef.methods.any { it.lightSchemeCalls().isNotEmpty() }
            ) {
                callers += classDef
            }
        }
        check(callers.isNotEmpty()) { "no light-scheme call site found in the app" }

        var rewritten = 0
        callers.forEach { classDef ->
            val mutable = mutableClassDefBy(classDef)
            classDef.methods.filter { it.lightSchemeCalls().isNotEmpty() }.forEach { original ->
                // Prove the dark builder exists in the Compose copy that is actually in this APK before
                // pointing anything at it: same class, same name shape, same parameter types and return
                // type. A Compose bump that renamed the mangled suffix would otherwise ship an APK that
                // throws NoSuchMethodError when the theme is first built.
                original.lightSchemeCalls().forEach { reference ->
                    val darkName = darkCounterpart(reference.name) ?: return@forEach
                    val library = classDefByOrNull { it.type == reference.definingClass }
                        ?: error("${reference.definingClass} is not in the APK")
                    val present = library.methods.any { candidate ->
                        candidate.name == darkName &&
                            candidate.returnType == reference.returnType &&
                            candidate.parameterTypes.toList() == reference.parameterTypes.toList()
                    }
                    check(present) { "${reference.definingClass}->$darkName is not in this APK's Compose version" }
                }
                val method = mutable.methods.firstOrNull {
                    it.name == original.name &&
                        it.parameterTypes.toList() == original.parameterTypes.toList() &&
                        it.returnType == original.returnType
                } ?: error("cannot locate ${classDef.type}->${original.name} for rewriting")
                method.shiftToDarkScheme()
                rewritten++
            }
        }
        check(rewritten > 0) { "no theme method was rewritten" }
    }
}
