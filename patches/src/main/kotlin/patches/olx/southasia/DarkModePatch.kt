package patches.olx.southasia

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch

/*
 * OLX India 20.07.001 — dark mode, the layer that actually owns the colours.
 *
 * Route (b), established by t_acef942f: this build ships **no app-authored night resources**
 * (res/values-night/colors.xml has 10 library entries; none of the app's own palette colours has a
 * `-night` variant) and its theme chain is hard-Light, so
 * `UiModeManager.setApplicationNightMode` — the `com.olx.pk` technique — is a no-op for the app's
 * own UI. The colours therefore have to be rewritten where they are materialised.
 *
 * Where that is: `Lcom/olx/compose_hub/theme/a` is the app's Compose palette — 103
 * `.field public static final <f>:J` fields, every one of them assigned in `<clinit>` from one
 * light literal:
 *
 *     const-wide v8, 0xff020812L
 *     invoke-static {v8, v9}, Landroidx/compose/ui/graphics/E;->d(J)J   # ColorKt.Color(J)J: argb shl 32
 *     move-result-wide v8
 *     sput-wide v8, Lcom/olx/compose_hub/theme/a;->d:J
 *
 * and read 578 times across `com/olxgroup`, `com/olx` and `com/naspers`. `Landroidx/compose/ui/
 * graphics/E` is R8's renamed `ColorKt`: `d(J)J` is `Color(Long)` (`shl-long/2addr p0, 0x20`),
 * `c(I)J` is `Color(Int)`.
 *
 * Fix: hook each of those results and let the runtime helper swap in the dark value:
 *
 *     invoke-static/range {v8 .. v9}, Lapp/proxma/olxsa/DarkMode;->dark(J)J
 *     move-result-wide v8
 *
 * Fields are matched **by value** (the light 24-bit RGB of the literal), so R8 renaming fields or
 * moving them between classes cannot desynchronise the patch — but a palette whose *values* moved
 * fails the two count assertions below instead of silently shipping a light app.
 *
 * Why hook the palette and not the 578 read sites: a read site may be any of
 * `Text(color=…)`, `Modifier.background(color, shape)` or a colour handed to another composable,
 * and the value-keyed identity of a token (is it a surface? a foreground?) is not recoverable from
 * the call shape — 347 of the 475 resolvable read sites are "passed somewhere else". Rewriting the
 * single definition point keeps the decision in one place. Measured: 128 of the 475 resolvable read
 * sites feed `Modifier.background`/`border`; 0 feed a text/icon API directly, i.e. this palette
 * paints surfaces and the text ramp is read through the theme objects.
 *
 * What this patch deliberately does NOT rewrite:
 *   - `Lcom/olx/compose_hub/theme/b` (15 long fields). Their values come from the Jio Ads SDK
 *     (`Lcom/jio/jioads/jioreel/tracker/model/b;->C(I)J`), not from this palette — library colours,
 *     out of scope for this card.
 *   - library constants. The rewrite is confined to `theme/a`'s own `<clinit>`; no androidx
 *     constant is touched.
 *   - 14 palette values that must stay as shipped (brand teal, premium gold, alpha overlays, and
 *     `#000000`, whose two roles — `button_background_color` and `nh_default_text_color` — cannot
 *     both be served). Enumerated in DARK_MODE.md with the reason for each.
 *
 * Alpha is preserved by the helper, so translucent tokens (`k` = primary at 40%, `x`/`y` = white at
 * 0%/24%, `X`/`Y` = a navy overlay at 8%/16%) survive the rewrite with the same alpha they had.
 * The rewrite is idempotent (no dark value is also a light key — asserted in DARK_MODE.md §4), which
 * matters because the palette reuses registers across fields.
 */

/**
 * Light 24-bit RGB -> dark 24-bit RGB. Generated from `work/darkmap.json`, which is itself emitted
 * by `work/darkmap.py` from the palette recovered out of the real `<clinit>`; the trailing comment
 * on each line names the palette fields that read that value. Do not hand-edit: re-run
 * `python3 work/darkmap.py`.
 */
internal val DARK_BY_LIGHT_RGB: Map<Int, Int> = mapOf(
    0xffffff to 0x121417, // G0 a o0 x y y0
    0x020812 to 0xf2f4f6, // J0 X0 d
    0x004896 to 0x4f93e0, // B0 g0 i k
    0x464b52 to 0xd7dade, // e
    0x0078fa to 0x4d9bff, // R0 c n
    0x8d9094 to 0x9aa0a6, // r s
    0x1ba39c to 0x35c1b9, // g h0
    0xe6f2ff to 0x1a2a3d, // l u
    0xf5f5f6 to 0x1a1d21, // F0 z
    0xe2e2e2 to 0x2c3036, // H j0
    0xfffce6 to 0x2b2616, // K0 P w
    0x5b5e65 to 0xc8ccd2, // A e0
    0xf2f4f5 to 0x1c2024, // o
    0xb0d5fd to 0x24384d, // D
    0x0060c8 to 0x4f93e0, // E
    0xe8f6f5 to 0x142a29, // U
    0xbf2600 to 0xff7a5c, // f0
    0xf6e4b3 to 0x3a2f18, // I L0
    0x111111 to 0xf2f4f6, // T0 c0
    0xd9dadb to 0x343a41, // t
    0xf6f6f6 to 0x1a1d21, // N i0
    0x282d36 to 0xe8eaed, // A0 b
    0xfff5b1 to 0x33291a, // O Q
    0xfffad9 to 0x33291a, // R
    0xc0c1c4 to 0x8b9298, // W z0
    0xf6f8fc to 0x1a1d21, // b0
    0x212121 to 0xe8eaed, // p0
    0xd8dfe0 to 0x343a41, // q
    0x235aaa to 0x5c8fd8, // D0
    0xfffdef to 0x2b2616, // F
    0x12b76a to 0x3ddc97, // M
    0xe8e8e8 to 0x2a2e33, // U0
    0xf9e9e6 to 0x2e1d19, // W0
    0x00469b to 0x4a8ad4, // a0
    0x13726d to 0x2f9d96, // h
    0xa2d9d6 to 0x1c3a38, // G
    0xf0f0f1 to 0x22262b, // P0
    0xceddff to 0x22344a, // S
    0xf5faff to 0x1a2029, // T
    0xfcfcfc to 0x16191d, // V0
    0x42b3ad to 0x45c2bb, // Z
    0xeeeeee to 0x26292e, // d0
    0xca4b2b to 0xff8a6b, // f
    0x847ce3 to 0xa79ff0, // l0
    0xebfee6 to 0x15291f, // m
    0xfff8fc to 0x1a1d21, // m0
    0x727272 to 0xa8aeb5, // q0
    0x3a77ff to 0x6f9cff, // s0
    0x00a49f to 0x2fc4be, // t0
    0x5a8cc8 to 0x8fb6e0, // v
    0xe5e5e5 to 0x2e3238, // v0
    0xf5f8ff to 0x1a2029, // w0
    0xde1609 to 0xff6b5e, // x0
    0xe6c803 to 0x3a2f18, // N0
    0x424242 to 0xb4b9bf, // p
)

private const val PALETTE_CLASS = "Lcom/olx/compose_hub/theme/a;"

/** R8's names for ColorKt's packers, read off the decompiled `<clinit>` above. */
private const val PACK_COLOR = "Landroidx/compose/ui/graphics/E;->d(J)J" // Color(Long): argb shl 32
private const val PACK_INT = "Landroidx/compose/ui/graphics/E;->c(I)J"   // Color(Int)
private const val ALPHA_COPY = "Landroidx/compose/ui/graphics/x;->c(JF)J" // Color.copy(alpha)

/** The runtime helper, built from extensions/extension/src/main/java/app/proxma/olxsa/DarkMode.java. */
private const val HELPER = "Lapp/proxma/olxsa/DarkMode;"

/**
 * Measured against the APK this card names (sha256 4e1dd7c1…d729c6) by
 * `work/darktransform.py`, the Python mirror of the loop below:
 * 98 packer results in `<clinit>`, 76 of them on a mapped literal, covering 55 distinct values.
 */
private const val EXPECTED_HOOK_SITES = 76
private const val EXPECTED_HOOK_LITERALS = 55

/** dexlib2 builder instructions expose opcode/literal/registers as protected fields. */
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

/**
 * Opcode constant name, lowercased: `const_wide`, `const_wide_16`, `const_high16`,
 * `move_result_wide`, `invoke_static_range`, `sput_wide`, …
 */
private fun Any.opcode(): String = fld("opcode")?.toString()?.lowercase() ?: ""

private fun Any.literal(): Long? = (fld("literal") as? Number)?.toLong()
private fun Any.reference(): String? = fld("reference")?.toString()
private fun Any.regA(): Int? = fld("registerA") as? Int
private fun Any.regB(): Int? = fld("registerB") as? Int
private fun Any.regC(): Int? = fld("registerC") as? Int
private fun Any.startReg(): Int? = fld("startRegister") as? Int

@Suppress("unused")
val olxSouthAsiaDarkModePatch = bytecodePatch(
    name = "Force dark mode",
    description = "Rewrites the baked-in colour constants of OLX India's Compose palette " +
        "(com.olx.compose_hub.theme.a) to dark equivalents. The app ships no dark palette and no " +
        "app-authored night resources, so theme, force-dark and values-night changes have no " +
        "effect on its own UI. Brand colours are kept.",
) {
    compatibleWith(COMPATIBILITY_OLX_SOUTHASIA)
    extendWith("extensions/extension.mpe")

    execute {
        val palette = mutableClassDefByOrNull(PALETTE_CLASS)
            ?: error("OLX India dark mode: $PALETTE_CLASS not found — the palette class moved")
        val clinit = palette.methods.firstOrNull { it.name == "<clinit>" }
            ?: error("OLX India dark mode: $PALETTE_CLASS.<clinit> not found")

        // Straight-line dataflow over <clinit>, mirroring work/darktransform.py. A register holds
        // either a raw literal or a literal already converted by a packer; a packer result is what
        // gets stored, so hooking the move-result covers both encoding paths (Color(Long) and
        // Color(Int), plus Color.copy(alpha) which re-derives from an already-packed value).
        val raw = HashMap<Int, Long>()
        val pending = arrayOfNulls<Long>(2) // [0] = the literal, [1] = the source kind marker
        var pendingKind: String? = null
        val sites = ArrayList<Pair<Int, Int>>() // instruction index -> result register
        val hooked = LinkedHashSet<Int>()

        for ((index, insn) in clinit.instructions.withIndex()) {
            val op = insn.opcode()
            when {
                op.startsWith("const_wide") -> {
                    val a = insn.regA() ?: continue
                    insn.literal()?.let { raw[a] = it } ?: raw.remove(a)
                }
                op.startsWith("const") -> {
                    val a = insn.regA() ?: continue
                    if (op.contains("string") || op.contains("class")) continue
                    insn.literal()?.let { raw[a] = it } ?: raw.remove(a)
                }
                op.startsWith("move_wide") -> {
                    val a = insn.regA() ?: continue
                    val b = insn.regB() ?: continue
                    raw[b]?.let { raw[a] = it } ?: raw.remove(a)
                }
                op.startsWith("move_object") -> {
                    insn.regA()?.let { raw.remove(it) }
                }
                op.startsWith("invoke_static") -> {
                    val ref = insn.reference()
                    val first = if (op.contains("range")) insn.startReg() else insn.regC()
                    val src = first?.let { raw[it] }
                    when {
                        ref == PACK_COLOR && src != null -> {
                            pending[0] = src; pendingKind = "packed"
                        }
                        ref == PACK_INT && src != null -> {
                            pending[0] = src; pendingKind = "int-packed"
                        }
                        ref == ALPHA_COPY && src != null -> {
                            pending[0] = src; pendingKind = "alpha"
                        }
                        else -> {
                            pending[0] = null; pendingKind = null
                        }
                    }
                }
                op.startsWith("invoke") -> {
                    pending[0] = null; pendingKind = null
                }
                op == "move_result_wide" -> {
                    val a = insn.regA() ?: continue
                    val src = pending[0]
                    if (src != null) {
                        raw[a] = src
                        val rgb = (src and 0xFFFFFFL).toInt()
                        if (DARK_BY_LIGHT_RGB.containsKey(rgb)) {
                            sites.add(index to a)
                            hooked.add(rgb)
                        }
                    } else {
                        raw.remove(a)
                    }
                    pending[0] = null; pendingKind = null
                }
                op == "sget_wide" -> {
                    // a value read from another field is not this palette's literal
                    insn.regA()?.let { raw.remove(it) }
                }
                op.startsWith("iget") || op.startsWith("iput") || op.startsWith("new_instance") ||
                    op.startsWith("check_cast") -> {
                    pending[0] = null; pendingKind = null
                }
            }
        }

        check(sites.size == EXPECTED_HOOK_SITES) {
            "OLX India dark mode: found ${sites.size} colour sites in $PALETTE_CLASS.<clinit>, " +
                "expected $EXPECTED_HOOK_SITES — the palette changed, re-derive the map with " +
                "work/darkmap.py before shipping"
        }
        check(hooked.size == EXPECTED_HOOK_LITERALS) {
            "OLX India dark mode: hooked ${hooked.size} distinct light values, expected " +
                "$EXPECTED_HOOK_LITERALS"
        }

        // Descending order so the recorded indices stay valid while injecting.
        for ((index, reg) in sites.sortedByDescending { it.first }) {
            clinit.addInstructions(
                index + 1,
                """
                    invoke-static/range {v$reg .. v${reg + 1}}, $HELPER->dark(J)J
                    move-result-wide v$reg
                """.trimIndent(),
            )
        }
        println(
            "OLX India dark mode: rewrote ${sites.size} palette values " +
                "(${hooked.size} distinct) in $PALETTE_CLASS.<clinit>",
        )

        // Anchor: the manifest-declared Application's onCreate()V. It is `.locals 0`, so the
        // injected call must be register-free — p0 only, no local registers touched.
        southAsiaApplicationOnCreateFingerprint.method.addInstructions(
            0,
            "invoke-static {p0}, $HELPER->install(Landroid/content/Context;)V",
        )
        println("OLX India dark mode: preference hook installed in DeloreanApplication.onCreate()V")
    }
}
