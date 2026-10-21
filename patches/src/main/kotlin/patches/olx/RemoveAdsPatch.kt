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
 * (5) Every remaining Google banner: the app funnels all of them through one container,
 *     `Lqg;->d(Lwx0;Lxd;)V`, which is the only place a BANNER load is issued. Stub it to
 *     `return-void` so no banner request is ever made, wherever the container was created — the
 *     home-screen strip and any PDP slot built by a path other than `is3` included.
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

/**
 * The app's single Google banner container load: `Lqg;->d(Lwx0;Lxd;)V`.
 *
 * `qg` is the `FrameLayout` that every GMA banner in this build renders into, and `d()` is the only
 * method in it that issues a load — it builds `ads_mobile_sdk/rf1` and calls it with format
 * `Lads_mobile_sdk/av2;->f` (= BANNER). Measured callers in the 18.8.0 dex:
 *
 *     csm2/mj.smali:2398    (the shared banner composable's coroutine — the home strip)
 *     csm2/ou3.smali:3214   (the PDP slots the is3 flip above already suppresses)
 *     csm2/j26.smali:1985   (the feed listing cell, already suppressed via u57)
 *
 * Because the home-screen banner and the item-page banner are one and the same container, and the
 * home path builds its own request (`Lrv;->invoke`, positions "home"/"vehicles_home"/
 * "property_home") that the is3 flip never touches, stubbing this method is the only place that
 * stops *all* of them without touching each caller. It is a `void` method with no return value,
 * nothing in this dex reads `getBannerAd()` afterwards, and the sibling lifecycle methods
 * (`a`/`b`/`c`) null-check the field they use, so an early return cannot strand the container.
 */
internal val bannerContainerLoadFingerprint = Fingerprint(
    returnType = "V",
    parameters = listOf("Lwx0;", "Lxd;"),
    custom = { m, c -> c.type == "Lqg;" && m.name == "d" },
)

/**
 * Home-screen ad slots — mechanism: **do not build the request at all** at the home screens'
 * coroutine dispatcher, so the per-tab ad-state flow is never published.
 *
 * `Lrv;->invoke()` is the shared completion dispatcher for the three home tabs. Each tab's case
 * looks like this (smali from the clean 18.8.0 tree, classes2.dex):
 *
 *     :pswitch_38
 *     check-cast v0, Lm3a;                      # property_home holder
 *     new-instance v1, Lzj5;                    # GoogleAdData(adRequest)
 *     iget-object v2, v0, Lm3a;->k:Lhk5;        # the app's ad client
 *     const-string v3, "property_home"
 *     invoke-virtual {v2, v3}, Lhk5;->d(Ljava/lang/String;)Lwx0;   # builds the request
 *     invoke-direct {v1, v2}, Lzj5;-><init>(Lwx0;)V
 *     iget-object v0, v0, Lm3a;->w:Ldb9;
 *     invoke-virtual {v0, v1}, Ldb9;->setValue(Ljava/lang/Object;)V   # <- published to the UI
 *
 * (`home` -> `Lou5;->I`, `vehicles_home` -> `Lo58;->y`; same shape.)
 *
 * Inserting `return-object v6` immediately before each `new-instance Lzj5;` skips the request
 * build and the `setValue`, so the state flow keeps the value it already holds — the "no ad yet"
 * state the screen is designed to render (the renderer, e.g. `Lmj;->invoke`, returns early on a
 * null state: `if-nez v1, :cond_f4`). `v6` is the register that holds `Llbd;->a` from the method
 * prologue and is what every other completed branch of this dispatcher returns, so the coroutine
 * contract is unchanged.
 *
 * Scope: only `rv` is touched. This kills the request itself, not just the view.
 */
internal val homeAdSlotDispatcherFingerprint = Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf(),
    custom = { m, c -> c.type == "Lrv;" && m.name == "invoke" },
)

/** Placement id -> the home-tab holder class whose ad-state flow that placement publishes to. */
private val homeAdSlotOwners = mapOf(
    "home" to "Lou5;",
    "vehicles_home" to "Lo58;",
    "property_home" to "Lm3a;",
)

/**
 * Indices of the three `new-instance Lzj5;` request builds in the home dispatcher, validated:
 * each must be directly fronted by a `check-cast v0, L<owner>;` for the owner its placement id
 * maps to, and must be followed by that placement id. A shifted or collapsed match fails loudly
 * instead of neutering an unrelated branch.
 */
private fun MutableMethod.homeAdSlotBuildIndices(): List<Int> {
    val insns = instructions
    val sites = insns.mapIndexedNotNull { idx, insn ->
        if (insn.opcode == Opcode.NEW_INSTANCE &&
            (insn as ReferenceInstruction).reference.toString() == AD_STATE_TYPE
        ) {
            idx
        } else {
            null
        }
    }
    require(sites.size == homeAdSlotOwners.size) {
        "OLX home ads: expected ${homeAdSlotOwners.size} ad-request builds in Lrv;->invoke, " +
            "found ${sites.size} — the 18.8.0 shape this patch is pinned to has changed"
    }

    val placements = sites.map { site ->
        val placement = insns.subList(site, minOf(site + 12, insns.size))
            .firstOrNull { insn ->
                insn.opcode == Opcode.CONST_STRING &&
                    (insn as ReferenceInstruction).reference.toString() in homeAdSlotOwners
            }
            ?.let { (it as ReferenceInstruction).reference.toString() }
            ?: error("OLX home ads: no known placement id follows the ad build at instruction $site")

        val owner = (site - 1 downTo 0).firstNotNullOfOrNull { i ->
            val prev = insns[i]
            if (prev.opcode == Opcode.CHECK_CAST) (prev as ReferenceInstruction).reference.toString()
            else null
        }
        require(owner == homeAdSlotOwners[placement]) {
            "OLX home ads: the \"$placement\" build is fronted by check-cast $owner, expected " +
                "${homeAdSlotOwners[placement]} — refusing to patch an unrelated branch"
        }
        placement
    }
    require(placements.toSet().size == homeAdSlotOwners.size) {
        "OLX home ads: the three home placements resolved to ${placements.toSet()} — " +
            "refusing to patch a collapsed match"
    }
    return sites
}

@Suppress("unused")
val removeAdsPatch = bytecodePatch(
    name = "Remove ads",
    description = "Removes native feed ads (Google GMA), the full-height ad slot, the " +
        "\"Buy with Delivery\" promo section (bar, cards and View all), the three item-page " +
        "banner slots, and the home-screen banner slots from OLX. Pinned to the 18.8.0 build " +
        "(matches that build's obfuscated feed, item-page and home classes).",
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

        // (5) home-screen banner: the dispatcher `rv.invoke` builds one ad request per home tab
        //     ("home" / "vehicles_home" / "property_home") and publishes it to that tab's state.
        //     Return the coroutine sentinel (v6, the same value every finished branch returns)
        //     immediately before each request object is allocated, so no request is built and the
        //     renderer (`mj`) keeps taking its documented null-state early exit.
        val homeDispatcher = homeAdSlotDispatcherFingerprint.method
        homeDispatcher.homeAdSlotBuildIndices().sortedDescending().forEach { buildIdx ->
            homeDispatcher.addInstruction(buildIdx, "return-object v6")
        }

        // (6) every remaining Google banner: stub the container's load to return immediately, so no
        //     banner request is ever issued regardless of which screen built the container. This is
        //     what covers any banner slot the request-side flips above do not own.
        bannerContainerLoadFingerprint.method.addInstructions(0, "return-void")
    }
}
