package patches.olx

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/**
 * EXPERIMENTAL / local-only, shape-matched to OLX 18.8.0.
 *
 * (1) Native feed ads (u57): force the ad-slot state getter u57.a() to DISCARDED (be.e) so the
 *     dispatcher (p87/q87) skips the whole slot — no ad, no placeholder box, no ad request.
 * (2) "Buy with Delivery" promo section: the grid builder h5b.c(List) pairs listings into y84 rows
 *     for the delivery section; return an empty list so no y84 items are inserted (removes the promo
 *     cards, the header bar and the View-all). Real search results (built elsewhere) are untouched.
 *     Also force the y84 header flag off as a belt-and-suspenders for any y84 built by another path.
 * (3) The large single ad slot (df6, inserted once): rebuild its state field as DISCARDED (be.e) at
 *     the end of its constructor so the dispatcher skips the whole full-height slot.
 * (4) Item-detail page (PDP) banner slots — see the block comment on
 *     [itemPageAdSlotLoaderFingerprint] for the mechanism and the matched signature.
 */
internal val adItemStateGetterFingerprint = Fingerprint(
    returnType = "Lbe;",
    parameters = listOf(),
    custom = { m, c -> c.type == "Lu57;" && m.name == "a" },
)

internal val deliveryGridBuilderFingerprint = Fingerprint(
    returnType = "Lx37;",
    parameters = listOf("Ljava/util/List;"),
    custom = { m, c -> c.type == "Lh5b;" && m.name == "c" },
)

internal val deliveryBarItemConstructorFingerprint = Fingerprint(
    returnType = "V",
    parameters = listOf("Z", "Z", "Ln67;", "Ln67;"),
    custom = { m, c -> c.type == "Ly84;" && m.name == "<init>" },
)

internal val largeAdItemConstructorFingerprint = Fingerprint(
    returnType = "V",
    parameters = listOf("Lif8;"),
    custom = { m, c -> c.type == "Ldf6;" && m.name == "<init>" },
)

/**
 * Item-detail (PDP) ad slots — mechanism: **per-slot gate flip at the page's ad loader**, i.e. the
 * `new-instance Lzj5;` request-object construction is forced onto its skip branch so the slot never
 * gets an ad state, never calls the view builder and never issues the BANNER request.
 *
 * Matched smali signature (classes2.dex of com.olx.pk 18.8.0):
 *
 *     .class final Lis3;
 *     .method public final invokeSuspend(Ljava/lang/Object;)Ljava/lang/Object;
 *
 * Why this mechanism and not the feed's predicate: the feed patch flips `Lu57;->a()` (the listing
 * cell's ad-slot state getter). The PDP never reaches `u57` — it has its own three slots
 * (`top_ad` / `middle_ad` / `bottom_ad` DetailSections inserted in `Lou3;->u`, uiTypes `Lw8b;->g`,
 * `Ll8b;->g`, `La8b;->g`) and its own loader, this method. There is also no SDK-wide master
 * predicate to flip here: 18.8.0 repackages Google Ads (the trimmed `ads_mobile_sdk` package) and
 * contains no
 * Baxter SDK at all, unlike the OLX India build whose patch flips
 * `Lcom/naspers/advertising/baxterandroid/f;->d()Z`. This loader is the closest thing the PDP has to
 * a per-placement gate, so the flip is done there.
 *
 * The three guarded build sites in this method (smali line numbers in the clean 18.8.0 tree):
 *
 *     1638: if-nez v4, :cond_249   ->  1642: new-instance v4, Lzj5;   1646: const-string "top"
 *     1681: if-nez v4, :cond_25f   ->  1685: new-instance v4, Lzj5;   1689: const-string "middle"
 *     1724: if-nez v4, :cond_275   ->  1728: new-instance v4, Lzj5;   1732: const-string "bottom"
 *
 * `v4` there is the value currently held by `Lou3;->T` / `ou3;->X` / `ou3;->b0`, the three
 * `Ldb9;` ad-state holders (all created empty in `Lou3;-><init>`, and written from nowhere else in
 * the app). Forcing each branch (`const/4 v4, 0x0` inserted immediately before the `if-nez`) means:
 *
 *   - `zj5` is never allocated, so `ou3.T/X/b0` stay null;
 *   - the `if-eqz <zj5>` guards below (smali 1767 / 1826 / 1885) skip `Lou3;->S(...)` at
 *     1808 / 1867 / 1926 — `S` is the builder that news the `qg` FrameLayout container and calls
 *     `qg.d(wx0)`, the `ads_mobile_sdk/rf1` load with format `Lads_mobile_sdk/av2;->f` = BANNER;
 *   - `Lym3;->invoke` (the Composable dispatcher) reads the same flows and takes its authored
 *     empty branch (`if-eqz v0, :cond_afc` at smali 6190) — it emits no node at all, so there is no
 *     empty box or gap where the banner was.
 *
 * Net effect: no ad request, no impression, no view — the same shape as the feed fix, which is why
 * the request is killed rather than the view merely hidden.
 *
 * Deliberately NOT done:
 *   - `Lou3;->G(String, Li67) -> Lwx0;` forced to return null: `zj5` would still be allocated, its
 *     `if-eqz` guard would pass, and `qg.d(null, ...)` would NPE. (Unsafe — measured in recon.)
 *   - Forcing the three `instance-of` checks in `Lym3;->invoke` (6156 / 6369 / 6468) instead: that
 *     hides the card but leaves the request and the impression live. Render-only, not sufficient.
 *
 * Scope: only `Lis3` is touched. The home-screen ad path (`Lrv;->invoke`) builds its own `zj5` per
 * "home"/"vehicles_home"/"property_home" and is left alone; the feed, delivery-promo and large-slot
 * fixes (1)-(3) above are unchanged.
 */
internal val itemPageAdSlotLoaderFingerprint = Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    strings = listOf("top", "middle", "bottom"),
    custom = { m, c -> c.type == "Lis3;" && m.name == "invokeSuspend" },
)

/** DetailSection ids of the three PDP ad slots, which double as the `ou3.G` request position ids. */
private val itemPageAdSlotIds = listOf("top", "middle", "bottom")

/** The ad-state holder class the guarded blocks allocate: `zj5` = GoogleAdData(adRequest). */
private const val AD_STATE_TYPE = "Lzj5;"

/**
 * Indices of the three `if-nez` guards that front the `zj5` construction blocks, found from the
 * inside out: each block contains exactly one `const-string "<slot>"` (the position id passed to
 * `ou3.G`), and the guard is the nearest preceding `if-nez`.
 *
 * Validated rather than assumed — every located guard must be directly followed by the
 * `new-instance Lzj5;` it gates, so a shifted/duplicated string marker fails the patch loudly
 * instead of silently neutering unrelated code.
 */
private fun MutableMethod.itemPageAdSlotGuardIndices(): List<Int> {
    val insns = instructions
    return itemPageAdSlotIds.map { slot ->
        val markerIdx = insns.indexOfFirst { insn ->
            insn.opcode == Opcode.CONST_STRING &&
                (insn as ReferenceInstruction).reference.toString() == slot
        }
        require(markerIdx > 0) {
            "OLX item-page ads: const-string \"$slot\" not found in Lis3;->invokeSuspend — " +
                "the 18.8.0 shape this patch is pinned to has changed"
        }

        val guardIdx = (markerIdx - 1 downTo 0).firstOrNull { insns[it].opcode == Opcode.IF_NEZ }
            ?: error("OLX item-page ads: no if-nez guard before the \"$slot\" slot block")

        val guardedBlock = insns.subList(guardIdx + 1, markerIdx)
        require(
            guardedBlock.any { insn ->
                insn.opcode == Opcode.NEW_INSTANCE &&
                    (insn as ReferenceInstruction).reference.toString() == AD_STATE_TYPE
            },
        ) {
            "OLX item-page ads: the \"$slot\" guard does not front a new-instance $AD_STATE_TYPE " +
                "block — refusing to flip an unrelated branch"
        }
        guardIdx
    }
}

@Suppress("unused")
val removeAdsPatch = bytecodePatch(
    name = "Remove ads",
    description = "Removes native feed ads (Google GMA), the full-height ad slot, the " +
        "\"Buy with Delivery\" promo section (bar, cards and View all), and the three item-page " +
        "banner slots from OLX. Pinned to the 18.8.0 build (matches that build's obfuscated " +
        "feed and item-page classes).",
) {
    compatibleWith(COMPATIBILITY_OLX)

    execute {
        // (1) ad slots -> DISCARDED
        val getter = adItemStateGetterFingerprint.method
        val beType = getter.returnType
        getter.addInstructions(0, "sget-object v0, $beType->e:$beType\nreturn-object v0")

        // (2a) delivery promo grid builder -> empty list
        deliveryGridBuilderFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, Liqe;->y()Lx37;
                move-result-object v0
                invoke-static {v0}, Liqe;->w(Ljava/util/List;)Lx37;
                move-result-object v0
                return-object v0
            """.trimIndent(),
        )

        // (2b) belt-and-suspenders: any y84 that still gets built has its header flag forced off
        deliveryBarItemConstructorFingerprint.method.addInstructions(0, "const/4 p1, 0x0")

        // (3) the large single ad slot (df6, inserted once): rebuild its state field as DISCARDED
        //     (be.e) at the end of its constructor so the dispatcher skips the whole full-height slot.
        val df6Ctor = largeAdItemConstructorFingerprint.method
        df6Ctor.addInstructions(
            df6Ctor.instructions.size - 1,
            """
                sget-object p1, Lbe;->e:Lbe;
                invoke-static {p1}, Ljod;->f(Ljava/lang/Object;)Ldb9;
                move-result-object p1
                iput-object p1, p0, Ldf6;->b:Ldb9;
            """.trimIndent(),
        )

        // (4) item-page banner slots: force each slot's gate onto its skip branch, so the
        //     `new-instance Lzj5;` request object is never allocated and `ou3.S` (the qg BANNER
        //     container + load) is never reached. Inserted bottom-up because each insertion
        //     shifts the indices after it.
        val itemPageLoader = itemPageAdSlotLoaderFingerprint.method
        val guardIndices = itemPageLoader.itemPageAdSlotGuardIndices()
        require(guardIndices.toSet().size == itemPageAdSlotIds.size) {
            "OLX item-page ads: the three slot gates resolved to ${guardIndices.toSet().size} " +
                "distinct branches ($guardIndices) — refusing to patch a collapsed match"
        }
        guardIndices.sortedDescending().forEach { guardIdx ->
            val register = (itemPageLoader.getInstruction(guardIdx) as OneRegisterInstruction).registerA
            // `const/4` only encodes v0-v15; `const/16` covers a wider register if the shape moves.
            itemPageLoader.addInstruction(
                guardIdx,
                if (register < 16) "const/4 v$register, 0x0" else "const/16 v$register, 0x0",
            )
        }
    }
}
