package patches.netmonster

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

/*
 * NetMonster premium = an Adapty (server-validated) subscription. The whole app reads premium from a
 * single `StateFlow<Boolean>` on the entitlement repo (`Liagsakvyq;` in 4.0.4). That flow's value is
 * (re)computed on every Adapty profile update by the repo's FlowCollector `emit(...)`: it scans the
 * user's `AdaptyProfile.Subscription`s and sets the boolean to true iff one is active or in grace.
 *
 * 3.4.x approach (overwriting the StateFlow FIELDS at the repo constructor's end) no longer holds on
 * 4.0.x: the collector re-writes the field after construction on the first profile update, reverting it
 * to false. So we patch the STEADY-STATE WRITER instead: force the computed premium boolean to true in
 * `emit()` right before it is boxed (`Boolean.valueOf(Z)`), so premium is always true no matter what the
 * Adapty profile says. This unlocks real-time LTE/NR-NSA location calc, removes ads, and shows Active.
 *
 * Name-agnostic anchoring (obfuscated class/field/StateFlow-type names are NEVER hardcoded — they drift
 * every release): the collector is identified purely by STABLE Adapty API names — the only `emit` method
 * whose body calls BOTH `AdaptyProfile$Subscription.isActive()` and `.isInGracePeriod()`. We then inject
 * `const/4 <reg>, 0x1` on the register feeding the single `Boolean.valueOf(Z)` in that method.
 */
private const val SUBSCRIPTION = "Lcom/adapty/models/AdaptyProfile\$Subscription;"

/**
 * The settings "NetMonster Premium" card does NOT read the premium boolean patched above. It renders
 * from a nullable expiry DATE: non-null → "Active till <date>", null → the PURCHASE button. That date is
 * derived from the Adapty subscription list, which is empty for anyone who never subscribed — so on a
 * re-signed build of 5.0.x the card can never reach the "active" branch no matter what the boolean says.
 * That is exactly what issue #32 reports (1.18.1, 5.0.1, both patches ticked, card still offers PURCHASE).
 *
 * Anchor: the composable that takes the expiry date as its first parameter and formats it for display
 * (`OffsetDateTime.toInstant()` + `SimpleDateFormat.<init>`). Unique in 5.0.0 and 5.0.1, and needs no
 * obfuscated name. Kept optional (see the execute block) so a build whose card shape differs — e.g. the
 * 4.0.x line, where the card already behaved — is left exactly as it was.
 */
internal val premiumCardFingerprint = Fingerprint(
    returnType = "V",
    custom = { m, _ ->
        m.parameterTypes.firstOrNull() == "Ljava/time/OffsetDateTime;" && run {
            val refs = (m.implementation?.instructions ?: emptyList())
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            refs.any { it.definingClass == "Ljava/time/OffsetDateTime;" && it.name == "toInstant" } &&
                refs.any { it.definingClass == "Ljava/text/SimpleDateFormat;" && it.name == "<init>" }
        }
    },
)

internal val premiumEmitFingerprint = Fingerprint(
    returnType = "Ljava/lang/Object;",
    // The FlowCollector that decides premium: the only emit() calling both Adapty subscription checks
    // AND boxing the computed flag exactly once. The "exactly once" half is load-bearing from 5.0.x on:
    // those builds add a second emit() that also reads both Adapty checks (a ~6k-instruction state
    // machine) but boxes the flag TWICE, so the two-Adapty-checks predicate alone is ambiguous and the
    // patcher cannot pick a method. The premium collector boxes once.
    custom = { m, _ ->
        m.name == "emit" && run {
            val refs = (m.implementation?.instructions ?: emptyList())
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            refs.any { it.definingClass == SUBSCRIPTION && it.name == "isActive" } &&
                refs.any { it.definingClass == SUBSCRIPTION && it.name == "isInGracePeriod" } &&
                refs.count {
                    it.definingClass == "Ljava/lang/Boolean;" && it.name == "valueOf" &&
                        it.parameterTypes.firstOrNull() == "Z"
                } == 1
        }
    },
)

/**
 * OPTIONAL. NetMonster's built-in Google Maps key is restricted to their release signing certificate,
 * so it stops working the moment the APK is re-signed → the map renders blank (Google logo, no tiles).
 * This is a re-signing artifact, independent of premium (the map is a free feature).
 *
 * We do NOT ship a key (that would mean embedding/leeching someone else's Google Cloud quota). Instead
 * this is opt-in: supply YOUR OWN key and it gets written into the manifest; leave it empty and the map
 * is left untouched (stays blank on the re-signed build — the rest of the app works fine).
 *
 * How to get one (free): Google Cloud Console → enable "Maps SDK for Android" → create an API key.
 * Either leave it unrestricted, or restrict it to application `cz.mroczis.netmonster` + the SHA-1 of the
 * keystore you sign the patched APK with. Then pass it at patch time:
 *   morphe-cli patch … -e "Google Maps API key (NetMonster)" -O maps-api-key=AIza...
 *
 * ⚠️ This MUST stay a named patch. While it was an unnamed private resourcePatch the option was only
 * reachable from the CLI: `list-patches --with-options` showed options: [] for the premium patch and
 * Manager had no field to type a key into, so map users had no fix at all (issue #32).
 */
@Suppress("unused")
val netmonsterMapsKeyPatch = resourcePatch(
    name = "Google Maps API key (NetMonster)",
    description = "Optional: write YOUR OWN Google Maps API key so the map renders after re-signing " +
        "(NetMonster's built-in key is cert-locked). Leave the key empty to leave the map as-is.",
) {
    compatibleWith(COMPATIBILITY_NETMONSTER)

    val mapsApiKey by stringOption(
        key = "maps-api-key",
        default = null,
        title = "Google Maps API key",
        description = "Your own unrestricted 'Maps SDK for Android' key (Google Cloud Console). " +
            "NetMonster's built-in key is locked to their signing cert and dies on re-sign (blank map). " +
            "Leave empty to keep the map disabled.",
        required = false,
    )

    finalize {
        val key = mapsApiKey?.takeIf { it.isNotBlank() } ?: return@finalize
        document("AndroidManifest.xml").use { doc ->
            val metas = doc.getElementsByTagName("meta-data")
            var replaced = false
            for (i in 0 until metas.length) {
                val el = metas.item(i) as Element
                if (el.getAttribute("android:name") == "com.google.android.geo.API_KEY") {
                    el.setAttribute("android:value", key)
                    replaced = true
                }
            }
            check(replaced) { "NetMonster: com.google.android.geo.API_KEY meta-data not found" }
        }
    }
}

@Suppress("unused")
val unlockPremiumPatch = bytecodePatch(
    name = "Unlock premium (NetMonster)",
    description = "Unlocks NetMonster Premium — forces the Adapty entitlement collector to always report " +
        "premium active, so real-time LTE/NR-NSA location calculation is unlocked, ads are removed, and " +
        "the status shows Active without a subscription.",
) {
    compatibleWith(COMPATIBILITY_NETMONSTER)
    // Optional map-key fix (no-op unless the user supplies -O maps-api-key=… / fills the field in Manager).
    dependsOn(netmonsterMapsKeyPatch)

    execute {
        // (1) emit-force: whenever the Adapty collector runs, force the computed premium flag true.
        val emit = premiumEmitFingerprint.method
        val emitInsns = emit.instructions
        val valueOfIdx = emitInsns.indexOfFirst {
            val r = (it as? ReferenceInstruction)?.reference as? MethodReference
            r != null && r.definingClass == "Ljava/lang/Boolean;" && r.name == "valueOf" &&
                r.parameterTypes.firstOrNull() == "Z"
        }
        check(valueOfIdx >= 0) { "NetMonster: Boolean.valueOf(Z) in premium emit() not found" }
        val reg = (emitInsns[valueOfIdx] as Instruction35c).registerC
        emit.addInstructions(valueOfIdx, "const/4 v$reg, 0x1")

        // (2) THE effective fix: set the premium StateFlow's VALUE true at the repo constructor's end.
        // emit only runs on an Adapty profile delivery (never on a re-signed / no-account build), so
        // without this the flag stays at its init false and ads/locks stay on. We derive everything from
        // emit's own bytecode so no obfuscated name is hardcoded:
        //   - the MutableStateFlow type = the class of emit's compareAndSet ((Object,Object)Z) call,
        //   - the premium field = the field read in emit whose type is that MSF (its class = the repo),
        //   - setValue = that MSF class's instance (Object)V method (the StateFlow value setter).
        val emitRefs = emitInsns
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        // The StateFlow the collector publishes into. Two shapes exist across versions:
        //   4.0.x — emit updates it through compareAndSet(Object,Object)Z;
        //   5.0.x — emit calls the value setter (Object)V directly (no compareAndSet in emit at all).
        // Either one names the StateFlow type, so try the 4.0.x shape first and fall back to 5.0.x.
        val msfType = emitRefs.firstOrNull {
            it.returnType == "Z" && it.parameterTypes.size == 2 &&
                it.parameterTypes.all { p -> p == "Ljava/lang/Object;" }
        }?.definingClass
            ?: emitRefs.firstOrNull {
                it.returnType == "V" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == "Ljava/lang/Object;"
            }?.definingClass
            ?: error("NetMonster: could not derive the premium StateFlow type from emit()")
        val premiumField = emitInsns
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
            .first { it.type == msfType }
        val repoType = premiumField.definingClass
        // Prefer the setter emit itself calls (name can drift); else look it up on the StateFlow type.
        val setValue = emitRefs.firstOrNull {
            it.definingClass == msfType && it.returnType == "V" &&
                it.parameterTypes.size == 1 && it.parameterTypes[0] == "Ljava/lang/Object;"
        }?.name ?: mutableClassDefBy(msfType).methods.first {
            it.name != "<init>" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == "Ljava/lang/Object;" && it.returnType == "V"
        }.name

        // The repo constructor (the one that wires Adapty's profile listener).
        val repo = mutableClassDefBy(repoType)
        val ctor = repo.methods.firstOrNull { m ->
            m.name == "<init>" && (m.implementation?.instructions ?: emptyList()).any {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "setOnProfileUpdatedListener"
            }
        } ?: repo.methods.first { it.name == "<init>" && it.implementation != null }

        // (3) THE effective fix — flip the build's own "paid flavor" flag true.
        // In production this flag is false, so the ctor launches an Adapty collector that recomputes
        // premium from the (empty, for a non-subscriber) subscription list and writes it back FALSE —
        // reverting (1) and (2). Forcing the flag true makes the ctor take the dev-flavor branch, which
        // sets premium TRUE and NEVER launches that collector, so nothing can ever overwrite it. Premium
        // then stays owned for the app's lifetime → the premium card shows owned and ads are removed.
        // Anchor: this ctor has exactly one `iget-boolean` (the flag read); its result gates the branch.
        val ctorInsns = ctor.instructions
        val flagIndices = ctorInsns.withIndex().filter { it.value.opcode == Opcode.IGET_BOOLEAN }.map { it.index }
        check(flagIndices.size == 1) {
            "NetMonster: expected exactly one iget-boolean (paid-flavor flag) in repo ctor, found ${flagIndices.size}"
        }
        val flagIdx = flagIndices[0]
        val flagReg = (ctorInsns[flagIdx] as TwoRegisterInstruction).registerA

        // (2 cont.) belt-and-braces: also set the premium StateFlow value true at the ctor's return
        // (covers the window before the flavor branch runs). retIdx > flagIdx, so inject here first.
        val retIdx = ctorInsns.indexOfLast { it.opcode == Opcode.RETURN_VOID }
        check(retIdx >= 0) { "NetMonster: repo constructor return-void not found" }
        ctor.addInstructions(
            retIdx,
            """
                iget-object v0, p0, $repoType->${premiumField.name}:$msfType
                sget-object v1, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                invoke-virtual {v0, v1}, $msfType->$setValue(Ljava/lang/Object;)V
            """.trimIndent(),
        )
        // The flavor-flag flip itself (flagIdx is unaffected by the return-site insertion above).
        ctor.addInstructions(flagIdx + 1, "const/4 v$flagReg, 0x1")

        // (4) THE PREMIUM CARD: force a future expiry date so the settings card renders as owned.
        // The card branches on this date being null (null → PURCHASE button, non-null → "Active till …"),
        // and the date is computed from the Adapty subscription list, which stays empty on a build that
        // never had a subscription. Injecting the date at the card composable's entry is enough and is
        // independent of anything Adapty delivers later. Optional by design: if the shape does not match
        // exactly one method (e.g. a future release, or the 4.0.x card), the patch leaves it untouched
        // rather than failing the whole run.
        val card = runCatching { premiumCardFingerprint.methodOrNull }.getOrNull()
        if (card != null) {
            // Need three contiguous scratch registers for the long argument, and p0 must be writable.
            val scratch = (card.implementation?.registerCount ?: 0) - card.parameterTypes.size
            card.addInstructions(
                0,
                if (scratch >= 3) """
                    invoke-static {}, Ljava/time/OffsetDateTime;->now()Ljava/time/OffsetDateTime;
                    move-result-object v0
                    const-wide/16 v1, 0x64
                    invoke-virtual/range {v0 .. v2}, Ljava/time/OffsetDateTime;->plusYears(J)Ljava/time/OffsetDateTime;
                    move-result-object v0
                    move-object/from16 p0, v0
                """.trimIndent() else """
                    invoke-static {}, Ljava/time/OffsetDateTime;->now()Ljava/time/OffsetDateTime;
                    move-result-object p0
                """.trimIndent(),
            )
        }
    }
}
