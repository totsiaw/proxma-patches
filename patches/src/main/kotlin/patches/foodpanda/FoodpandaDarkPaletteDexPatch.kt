package patches.foodpanda

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel

/*
 * foodpanda app-wide dark mode — the layer that actually owns the colors.
 *
 * Verified on 26.38.1. Three earlier builds failed (forceDarkAllowed, Base re-parent, and a
 * hand-authored values-night palette) because none of them touch the layer the UI reads.
 *
 * The colors live in `Lm7g` (implements `Lhl3`), the DeliveryHero "Bento" palette:
 *   - 180 `.field public final X:J` + `.field a:I` (the darkTheme flag)
 *   - `.method public constructor <init>(I)V`, `.locals 43`
 *   - 180 getters, each `.locals 2`, one `iget-wide` per color
 *
 * R8 merged the light and dark palettes. Measured from the constructor: both the fall-through
 * path and the `:pswitch_0` dark branch contain 94 `0x........L` literals, 71 unique, with ZERO
 * difference between them. So the shipped "dark" palette really is byte-identical to the light
 * one — this app genuinely ships no dark theme, and no flag value can change that.
 *
 * Every getter is therefore branch-identical too:
 *     iget v0, p0, Lm7g;->a:I
 *     packed-switch v0, ...
 *     iget-wide v0, p0, Lm7g;->I1:J
 *     return-wide v0
 *     :pswitch_0
 *     iget-wide v0, p0, Lm7g;->I1:J     <-- same field
 *     return-wide v0
 *
 * That is exactly why theme attributes and `values-night` cannot work here: Compose calls these
 * getters, they return light constants, and those constants live in the dex. Nothing resolves
 * `@color/foodpanda_color_*` at runtime, so overriding it in resources is inert.
 *
 * Fix: rewrite the getters that hold a neutral/surface/text color so they return a dark constant.
 * Fields are matched BY VALUE — the field -> literal map is recovered from the constructor — so
 * this is resilient to R8 renaming the fields or the class.
 *
 * Color encoding: every literal is passed through `Lh91.f(J)J`, which is
 *     public static final f(J)J { shl-long/2addr p0, 0x20; return-wide p0; }
 * i.e. it shifts the 32-bit ARGB left by 32 to produce a ULong. Getters return that ULong, so we
 * emit the same conversion.
 *
 * Brand colors (primary pink and friends) are deliberately left untouched — they read fine on
 * dark and overriding them would destroy the app's identity.
 */

/** Light ARGB hex -> dark ARGB. Neutrals, borders, surfaces, text. */
private val DARK_BY_LIGHT_HEX: Map<String, String> = mapOf(
    // surfaces
    "ffffffff" to "ff121417",
    "fff7f8f8" to "ff121417",
    "fffaf9f6" to "ff121417",
    "fff5f6f7" to "ff16181c",
    "fffdfdfe" to "ff181a1e",
    "ff090c0f" to "ffe8eaed", // dark tag surface -> light chip
    "ff0c0f0f" to "ffe8eaed",

    // text
    "ff2e3138" to "fff2f3f5", // neutralPrimary
    "ff2a3333" to "fff2f3f5",
    "ff6b7175" to "ffb4b9bf", // neutralSecondary
    "ff626b6b" to "ffb4b9bf",
    "ffb7babc" to "ff767c82", // neutralInactive
    "ffabb2b2" to "ff767c82",

    // borders / dividers
    "ffd3d5d7" to "ff2f3338", // neutralBorder
    "ffd0d6d5" to "ff2f3338",
    "ffe8e9ea" to "ff23262a", // neutralDivider / skeleton
    "ffe4e8e4" to "ff23262a",

    // Pale tinted "Low"/highlight surfaces. Left light they carried the now-light neutral text and
    // read near-white-on-pale: the home header gradient (surfaceGradientTop/Bottom), the flash-deal
    // and promo cards (dealHighlightLvl1/2, surfacePromobannerLow), tags and tiles.
    // Brand pinks: surfaceGradientTop, dealHighlightLvl1, ... / brandHighlight, dealHighlightLvl2,
    // surfacePromobannerLow, tilePrimary* / dealTertiary
    "ffffebeb" to "ff2b1820",
    "00ffebeb" to "002b1820", // surfaceGradientBottom: same hue, so the fade has no pale fringe
    "ffffdddd" to "ff3b1d2b",
    "ffffc4cc" to "ff52253a",
    // error / spicy
    "ffffe3db" to "ff3a211b",
    "fffdefeb" to "ff2e1d19",
    "ffffb4a0" to "ff52291f",
    // accent / popular / warning (amber)
    "fffff4dd" to "ff2b2616",
    "ffffedc6" to "ff33291a",
    "ffffe9b8" to "ff3a2f18",
    "fffeefcd" to "ff2f2818",
    "fffee2a6" to "ff40331a",
    // information / shops / AI (blue)
    "ffdfedf7" to "ff1a2836",
    "ffedf6fc" to "ff16212b",
    "ffc9dded" to "ff22384a",
    // success (green)
    "ffe0f5ed" to "ff15291f",
    "00e0f5ed" to "0015291f",
    "ffcbf1e1" to "ff1b3528",
    // pro (purple)
    "fffcf3fe" to "ff241a2c",
    "ffefddf5" to "ff2e1f38",
    "ffdec0eb" to "ff3d2a4d",
    // neutral tiles + transparent gradient stops that fade into the dark surface
    "ffe3e6e8" to "ff2a2d31",
    "fff0f2f2" to "ff1c1f22",
    "00f7f8f8" to "00121417",
    "00ffffff" to "00121417",

    // Text/icon colors designed for those pale surfaces -> light equivalents (all >= 4.5:1 on them)
    "ff39434d" to "ffe8eaed", // textPromobannerOnLow / iconPromobannerOnLow
    "ff25537c" to "ff8fbce6", // information / textOnShopsLow / iconOnShopsLow
    "ff357258" to "ff7cc8a4", // tagDietaryVegan / Vegetarian
    "ffbb2800" to "ffff7a5c", // error / tagDietarySpicy
    "ff6b7680" to "ffb4b9bf", // brandSecondary

    // --- v6: literals measured still rendering light in fp-dark5 (dex-verified) ---
    // These 16 getters survived v5 because FIELD_OVERRIDES forced them back to their original
    // value. With the override list gone they now fall through to the value map.
    "ffd3d5d7" to "ff2f3338", // neutralBorder x4 getters
    "ffb7babc" to "ff767c82", // neutralInactive
    "ffc69adf" to "ff5a3f5f", // pale-pink promo/deal surface
    "ffb2e2f0" to "ff1c2b2e", // pale cyan chip
    "ff919da6" to "ffa0a8ae", // muted red/disabled text
    "ff97d8bc" to "ff2a3a34", // pale green chip
    "fffff4dd" to "ff2b2616", // amber surface
    "ffffedc6" to "ff33291a", // amber surface 2
    "ffdec0eb" to "ff3d2a4d", // pro purple surface
    "ffe0f5ed" to "ff15291f", // success green surface
    "ffc21760" to "ffff7aa8", // deep pink text -> light pink
    "ff6e3dab" to "ffc98cf0", // brand purple -> readable on dark
    "80ffffff" to "ff121417", // 50% white overlays -> dark scrim
    "8fffffff" to "ff121417",
    "ccffffff" to "ff1a1c20", // 80% white overlay
    "ffffff80" to "ff121417",
    "ffcc54c" to "ff3a2f18", // amber/gold surface
    "ffffb413" to "ff3a3018", // gold accent
    "ffffb4a0" to "ff52291f", // spicy tag surface
    "ffffffff" to "ff121417", // pure white surface/text
)

/**
 * Per-field exceptions to the value map, for tokens that share a value with a surface but are used
 * as foreground. Field names are R8's for 26.38.1 (matched to Bento token names by aligning the
 * field order with the alphabetical `foodpanda_color_*` resources: 179/180 values line up). Each
 * entry carries the light literal it must hold; a mismatch aborts instead of silently no-op'ing.
 * A null dark value keeps the original color.
 */
private val FIELD_OVERRIDES: Map<String, Pair<String, String?>> = mapOf(
    // v6: INTENTIONALLY EMPTY.
    //
    // v5 pinned 20 fields to their original value ("keep white"). Because the remap step consults
    // FIELD_OVERRIDES *before* DARK_BY_LIGHT_HEX, an override with a null dark value short-circuits
    // the lookup entirely — those fields could never be darkened, no matter what the value map said.
    // On device that produced the reported regression: white text/icons on pale-pink surfaces and a
    // pink header, while the body was correctly dark.
    //
    // Brand identity is now preserved by mapping the pink/brand hues themselves to dark equivalents
    // (see DARK_BY_LIGHT_HEX: ffc69adf -> ff5a3f5f, ffc21760 -> ffff7aa8, ff6e3dab -> ffc98cf0)
    // instead of freezing white foreground tokens.
)


/** dexlib2 builder instructions expose opcode/registers as protected fields; read them reflectively. */
private fun Any.fld(name: String): Any? {
    var c: Class<*>? = javaClass
    while (c != null) {
        try {
            val f = c.getDeclaredField(name)
            f.isAccessible = true
            return f.get(this)
        } catch (_: Exception) {
            c = c.superclass
        }
    }
    return null
}

private fun Any.opcodeName(): String = fld("opcode")?.toString()?.lowercase() ?: ""
private fun Any.litHex(): String? =
    (fld("literal") as? Number)?.let { "0x" + java.lang.Long.toHexString(it.toLong()) }
private fun Any.refStr(): String? = fld("reference")?.toString()
private fun Any.regA(): Int? = fld("registerA") as? Int
private fun Any.regC(): Int? = fld("registerC") as? Int
private fun Any.startReg(): Int? = fld("startRegister") as? Int

/**
 * 32-bit ARGB hex -> the Compose Color ULong packing the getter must return: `(argb and 0xffffffff) << 32`.
 * The original getters compute this via `Lh91;->f(J)J` (`shl-long/2addr p0, 0x20`); we fold the shift in
 * and emit the finished 64-bit value as one `const-wide`, because `shl-long/2addr` needs its count in a
 * register and the getters only have `.locals 2` (both taken by the wide pair). The result is a signed
 * 64-bit long (top bit set), which `const-wide` encodes directly.
 */
private fun packedColor(hex: String): Long = java.lang.Long.parseUnsignedLong(hex, 16) shl 32

private const val PALETTE_CLASS = "Lm7g;"

/*
 * The "How was <shop>?" rating card is not an Lm7g color: it is a gradient Brush from the Bento
 * gradient-token class `Lq7g` (implements `Lsm3`, foodpanda's twin of foodora's `Lc6g`), built in
 * the ctor as `Lgnd;->i([Pair;JJ)Lzom` (linearGradient over color stops) into field `l`, returned
 * by `Lsm3;->m()`. The rating sheet (`wue0.h`, "qc-survey-*") passes it to `Lijb;->f`, i.e.
 * `Modifier.background(brush, shape)` — a container, not a foreground. Its stops are exactly
 * foodpanda_color_surfaceRatingGradientStart/End; the card pixels sampled from the device lie on
 * that interpolation. Only this one field is touched; the other 10 gradients stay as shipped.
 */
private const val GRADIENT_CLASS = "Lq7g;"
private const val RATING_GRADIENT_FIELD = "$GRADIENT_CLASS->l:Lzom;"

/**
 * surfaceRatingGradient stops in order: light literal (asserted) -> extension helper that returns the
 * dark stop when the toggle is on (ff2b1820 / ff52253a, same as Lm7g's map) and the input otherwise.
 */
private val RATING_GRADIENT_STOPS = listOf("ffffebeb" to "ratingStart", "ffffc4cc" to "ratingEnd")

/*
 * Dark-mode toggle. `extensions/foodpanda-darkmode/smali` is assembled into `extensions/fpdark.mpe`
 * (a raw dex) and merged by extendWith. `DarkMode.on` (default true; SharedPreferences
 * "fp_dark_mode") gates every rewrite below, and SettingsFragment gets a "Dark mode" checkbox card
 * that flips it and restarts the app — the Bento theme objects are built once, so a static flag
 * cannot recompose live.
 */
private const val DARK_MODE = "Lapp/proxma/fpdark/DarkMode;"
private const val SETTINGS_FRAGMENT = "Lcom/deliveryhero/preferences/settings/SettingsFragment;"

@Suppress("unused")
val foodpandaDarkPaletteDexPatch = bytecodePatch(
    name = "Force dark mode",
    description = "Rewrites foodpanda's baked-in Bento color constants to dark equivalents so the " +
        "app renders a real dark theme. The app ships no dark palette — R8 merged its light and " +
        "dark token sets into one identical set of dex constants — so theme, force-dark and " +
        "values-night changes have no effect. Brand colors are left untouched.",
) {
    compatibleWith(COMPATIBILITY_FOODPANDA)
    extendWith("extensions/fpdark.mpe")

    execute {
        val palette = mutableClassDefByOrNull(PALETTE_CLASS)
        check(palette != null) { "foodpanda: $PALETTE_CLASS not found — palette class moved?" }

        val ctor = palette.methods.firstOrNull { it.name == "<init>" && it.parameters.size == 1 }
        check(ctor != null) { "foodpanda: $PALETTE_CLASS.<init>(I)V not found" }

        // 1) recover field -> light literal from the constructor by straight-line dataflow.
        //    The ctor never stores a literal directly: it does
        //        const-wide vX, 0xAARRGGBBL ; invoke-static {vX..}, Lh91;->f(J)J ; move-result-wide vY ;
        //        iput-wide vY, v0, Lm7g;->FIELD:J
        //    with vY often != vX, cached literals re-fed to f() later, and move-wide spills. So track
        //    what each register holds: RAW literal, or a literal already converted by f(). For
        //    iput-wide the VALUE register is registerA (registerB is `this`).
        //    `Lh91;->e(I)J` (alpha-premultiplied int colors) is not tracked — those are overlays.
        val fieldToLiteral = LinkedHashMap<String, String>()
        val raw = HashMap<Int, String>()
        val converted = HashMap<Int, String>()
        var pending: String? = null
        for (insn in ctor.instructions) {
            val op = insn.opcodeName()
            val a = insn.regA()
            when {
                op == "return_void" -> { raw.clear(); converted.clear(); pending = null }
                op.startsWith("const_wide") -> {
                    if (a == null) continue
                    converted.remove(a)
                    val lit = insn.litHex()
                    if (lit != null) raw[a] = lit.removePrefix("0x") else raw.remove(a)
                }
                op.startsWith("const") -> {
                    if (a == null) continue
                    converted.remove(a)
                    // 32-bit `const` feeds Lh91.e(I)J (translucent / transparent gradient stops)
                    val lit = (insn.fld("literal") as? Number)?.toLong()
                    if (lit != null && !op.contains("string") && !op.contains("class")) {
                        raw[a] = "%08x".format(lit and 0xffffffffL)
                    } else {
                        raw.remove(a)
                    }
                }
                op.startsWith("move_wide") -> {
                    val b = insn.fld("registerB") as? Int
                    if (a == null || b == null) continue
                    raw[b]?.let { raw[a] = it } ?: raw.remove(a)
                    converted[b]?.let { converted[a] = it } ?: converted.remove(a)
                }
                op.startsWith("invoke_static") -> {
                    val ref = insn.refStr()
                    val first = if (op.contains("range")) insn.startReg() else insn.regC()
                    // f(J)J and e(I)J both pack ARGB as `argb << 32`
                    val packs = ref == "Lh91;->f(J)J" || ref == "Lh91;->e(I)J"
                    pending = if (packs && first != null) raw[first] else null
                }
                op.startsWith("invoke") -> pending = null
                op == "move_result_wide" -> {
                    if (a == null) continue
                    raw.remove(a)
                    val p = pending
                    if (p != null) converted[a] = p else converted.remove(a)
                    pending = null
                }
                op.startsWith("iput_wide") -> {
                    val ref = insn.refStr() ?: continue
                    if (!ref.startsWith("$PALETTE_CLASS->") || !ref.endsWith(":J")) continue
                    val field = ref.removePrefix("$PALETTE_CLASS->").removeSuffix(":J")
                    val lit = a?.let { converted[it] }
                    if (lit != null) fieldToLiteral.putIfAbsent(field, lit)
                }
            }
        }
        check(fieldToLiteral.size >= 100) {
            "foodpanda: recovered only ${fieldToLiteral.size} palette fields (expected ~180)"
        }

        // 2) fields currently holding a color we want to darken
        for ((field, expected) in FIELD_OVERRIDES) {
            val actual = fieldToLiteral[field]
            check(actual == expected.first) {
                "foodpanda: override field $PALETTE_CLASS->$field holds ${actual ?: "no recovered literal"}, " +
                    "expected ${expected.first} — palette layout changed"
            }
        }
        val remap = LinkedHashMap<String, String>()
        var kept = 0
        for ((field, lit) in fieldToLiteral) {
            val override = FIELD_OVERRIDES[field]
            val dark = if (override != null) override.second else DARK_BY_LIGHT_HEX[lit]
            if (override != null && dark == null) kept++
            if (dark != null) remap[field] = dark
        }
        check(remap.isNotEmpty()) { "foodpanda: no palette fields matched the dark palette map" }

        // 3) rewrite the getter(s) that read each remapped field
        var rewritten = 0
        val getters = palette.methods.filter { it.parameters.isEmpty() && it.name != "<init>" }
        for ((field, dark) in remap) {
            val target = "$PALETTE_CLASS->$field:J"
            for (method in getters) {
                val readsField = method.instructions.any {
                    it.opcodeName().startsWith("iget_wide") && it.refStr() == target
                }
                if (!readsField) continue
                val packed = packedColor(dark)
                // toggle off -> fall through to the untouched original getter
                method.addInstructionsWithLabels(
                    0,
                    """
                        sget-boolean v0, $DARK_MODE->on:Z
                        if-eqz v0, :fp_dark_off
                        const-wide v0, ${packed}L
                        return-wide v0
                    """.trimIndent(),
                    ExternalLabel("fp_dark_off", method.instructions.first()),
                )
                rewritten++
            }
        }
        check(rewritten > 0) { "foodpanda: no palette getters could be rewritten" }
        println(
            "foodpanda dark mode: mapped ${fieldToLiteral.size} palette fields, " +
                "remapped ${remap.size}, kept $kept, rewrote $rewritten getters",
        )

        // 4) rating card gradient: route the packed stop colors that feed Lq7g->l (only) through
        //    DarkMode. Each stop is `invoke-static Lh91;->f/e ; move-result-wide vY ; Lfi8.<init>(vY)`,
        //    so `vY = DarkMode.ratingX(vY)` right after the move-result swaps that stop without
        //    touching the source registers other gradients share.
        val gradients = mutableClassDefByOrNull(GRADIENT_CLASS)
        check(gradients != null) { "foodpanda: $GRADIENT_CLASS not found — gradient class moved?" }
        val gradCtor = gradients.methods.firstOrNull { it.name == "<init>" && it.parameters.size == 1 }
        check(gradCtor != null) { "foodpanda: $GRADIENT_CLASS.<init>(I)V not found" }

        val stopRaw = HashMap<Int, String>()
        // registers loaded before the flag `packed-switch` are live again in the :pswitch_0 branch
        // that follows the first return-void (v12 = ffffebeb is one), so restore them there
        var atSwitch: Map<Int, String> = emptyMap()
        var stopPending: String? = null
        val segment = ArrayList<Triple<Int, Int, String>>() // (insn index, result register, light literal)
        var builtStops: List<Triple<Int, Int, String>>? = null // stops consumed by the last Lgnd.i
        val brushStops = HashMap<Int, List<Triple<Int, Int, String>>>() // brush register -> its stops
        val inserts = ArrayList<Triple<Int, Int, String>>()  // (insert index, register, dark literal)
        var ratingSegments = 0
        for ((index, insn) in gradCtor.instructions.withIndex()) {
            val op = insn.opcodeName()
            val a = insn.regA()
            when {
                op == "return_void" -> {
                    stopRaw.clear(); stopRaw.putAll(atSwitch)
                    segment.clear(); brushStops.clear(); builtStops = null; stopPending = null
                }
                op == "packed_switch" -> atSwitch = HashMap(stopRaw)
                op.startsWith("const_wide") -> {
                    if (a == null) continue
                    val lit = (insn.fld("literal") as? Number)?.toLong()
                    if (lit != null) stopRaw[a] = "%08x".format(lit and 0xffffffffL) else stopRaw.remove(a)
                }
                op.startsWith("const") -> {
                    if (a == null) continue
                    val lit = (insn.fld("literal") as? Number)?.toLong()
                    if (lit != null && !op.contains("string") && !op.contains("class")) {
                        stopRaw[a] = "%08x".format(lit and 0xffffffffL)
                    } else {
                        stopRaw.remove(a)
                    }
                }
                op.startsWith("move_wide") -> {
                    val b = insn.fld("registerB") as? Int
                    if (a == null || b == null) continue
                    stopRaw[b]?.let { stopRaw[a] = it } ?: stopRaw.remove(a)
                }
                op.startsWith("invoke_static") -> {
                    val ref = insn.refStr()
                    val first = if (op.contains("range")) insn.startReg() else insn.regC()
                    val packs = ref == "Lh91;->f(J)J" || ref == "Lh91;->e(I)J"
                    stopPending = if (packs && first != null) stopRaw[first] else null
                    // linearGradient(stops, start, end): its stops are the colors converted since the last one
                    if (ref?.startsWith("Lgnd;->i([Lkotlin/Pair;JJ)") == true) {
                        builtStops = segment.toList()
                        segment.clear()
                    }
                }
                op.startsWith("invoke") -> stopPending = null
                op == "move_result_wide" -> {
                    if (a == null) continue
                    stopRaw.remove(a)
                    stopPending?.let { segment.add(Triple(index, a, it)) }
                    stopPending = null
                }
                op == "move_result_object" -> {
                    if (a == null) continue
                    builtStops?.let { brushStops[a] = it } ?: brushStops.remove(a)
                    builtStops = null
                }
                op.startsWith("iput_object") -> {
                    if (insn.refStr() == RATING_GRADIENT_FIELD) {
                        val stops = a?.let { brushStops[it] } ?: emptyList()
                        val lights = stops.map { it.third }
                        check(lights == RATING_GRADIENT_STOPS.map { it.first }) {
                            "foodpanda: $RATING_GRADIENT_FIELD stops are $lights, expected " +
                                "${RATING_GRADIENT_STOPS.map { it.first }} — gradient layout changed"
                        }
                        for ((i, stop) in stops.withIndex()) {
                            check(stop.second < 15) { "foodpanda: stop register v${stop.second} too high for invoke-static" }
                            inserts.add(Triple(stop.first + 1, stop.second, RATING_GRADIENT_STOPS[i].second))
                        }
                        ratingSegments++
                    }
                }
            }
        }
        check(ratingSegments > 0) { "foodpanda: no store to $RATING_GRADIENT_FIELD found in $GRADIENT_CLASS.<init>" }
        for ((at, reg, helper) in inserts.sortedByDescending { it.first }) {
            gradCtor.addInstructions(
                at,
                """
                    invoke-static {v$reg, v${reg + 1}}, $DARK_MODE->$helper(J)J
                    move-result-wide v$reg
                """.trimIndent(),
            )
        }
        println(
            "foodpanda dark mode: rating card gradient $RATING_GRADIENT_FIELD rewrote " +
                "${inserts.size} stops in $ratingSegments ctor branch(es)",
        )

        // 5) the toggle row: SettingsFragment.onCreateView returns the inflated ScrollView exactly
        //    once on its success path; hand it to DarkMode.attach first.
        val settings = mutableClassDefByOrNull(SETTINGS_FRAGMENT)
        check(settings != null) { "foodpanda: $SETTINGS_FRAGMENT not found" }
        val onCreateView = settings.methods.firstOrNull {
            it.name == "onCreateView" && it.returnType == "Landroid/view/View;"
        }
        check(onCreateView != null) { "foodpanda: $SETTINGS_FRAGMENT.onCreateView not found" }
        val returns = onCreateView.instructions.withIndex().filter { it.value.opcodeName() == "return_object" }
        // [0] returns the binding root (ScrollView); [1] is the "Missing required view" failure path
        check(returns.size == 2) { "foodpanda: onCreateView has ${returns.size} return-object, expected 2" }
        val (retAt, ret) = returns.first()
        val rootReg = ret.regA()
        check(rootReg != null && rootReg < 16) { "foodpanda: unexpected onCreateView return register $rootReg" }
        onCreateView.addInstructions(retAt, "invoke-static {v$rootReg}, $DARK_MODE->attach(Landroid/view/View;)V")
        println("foodpanda dark mode: toggle row hooked into $SETTINGS_FRAGMENT.onCreateView (v$rootReg)")
    }
}