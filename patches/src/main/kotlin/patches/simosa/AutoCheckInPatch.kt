package patches.simosa

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/*
 * Simosa daily rewards: claim today's check-in and today's Daily Rewards rung without a tap.
 *
 * The "Daily Rewards" screen carries two independent reward systems:
 *
 *  1. "Daily Check-In" — `Li4/v`, the check-in ViewModel. Tapping "Check in" (`onCheckInClicked`) starts `v.G2()`, which
 *     runs one suspend lambda (obfuscated `Li4/v$n`, the only `invokeSuspend` in the app that logs
 *     " _pendingCheckInAdShow "). That lambda reads the check-in state out of three flags and
 *     publishes a "pending check-in ad" event into the screen state flow. The UI shows the check-in
 *     ad, and only after it is dismissed calls the ViewModel's check-in (`X1()`, which logs
 *     "access_token missing, skip check-in"). Part 1 calls `X1()` at the publish site (no ad), and
 *     part 2 schedules the same call from the state renderer (no tap).
 *
 *  2. "Daily Rewards" — `Le4/N`, the MB ladder (25/50/100/120/150/175/200 MB). Its "Claim it Now!"
 *     button drives exactly one call:
 *         Le4/N;->L1(U.i(), data.currentStatus ?: "")      (U.i() is the selected rung)
 *     and `L1` itself only launches the claim while `a0` is false. `a0` is the app's "today is already
 *     claimed" flag: `l2` sets it to true for `claimed == "true"` and to false for `claimed == "false"`.
 *     Part 3 makes the button's call for the user.
 *
 * Why part 3 hooks the completion callback of `t2()` and not `t2()` itself:
 *
 *  - Every ladder response is stored with `o2(response)` and handed to `J1()` = `t2()`, on every route.
 *  - But `t2()` only *launches* `Le4/N$j`, which applies the response (`l2` recomputes `a0`, the ladder
 *    state flow is rebuilt). Code at the tail of `t2()` therefore sees the previous response's `a0`
 *    and ladder. `t2()` registers a completion callback on that job (`Le4/M`, via `z0.n(Function1)`);
 *    it runs after the response has been applied, so that is the anchor.
 *  - `Le4/M` is a different class from `Le4/N`, and `a0`/`b0` are private (reading them from `Le4/M`
 *    is the IllegalAccessError of issue #38). So `Le4/M` only calls a public method this patch adds to
 *    `Le4/N`, and every private read happens inside `Le4/N`.
 *
 * One attempt per account per day, for each of the two systems:
 *  - The hook schedules a 2-6 s jittered coroutine on the ViewModel's own `viewModelScope` (through
 *    the app's own launch and delay); a ViewModel cleared during the delay cancels it.
 *  - When it fires, every guard is re-checked against the ViewModel's current state: day latch,
 *    response/data present, `claimed != "true"`, `a0 == false`, a selected rung (ladder), or
 *    `can_checkin_today && !must_restore` (check-in).
 *  - The latch (`proxma_claim_<msisdn-hash>_<yyyyMMdd>` in SharedPreferences "proxma_simosa") is
 *    committed *before* the app's own claim call. `a0` is never forced (`n2(false)` is not called).
 *  - Each outcome is one `PROXMA_CLAIM` logcat line; nothing is shown to the user.
 *
 * The runtime half (latch, schedule, coroutine body, logging) is the smali in
 * `resources/simosa/autoclaim/`, assembled into the patch's extension dex at patch time. It names no
 * obfuscated class: it reaches the ViewModels through the `ClaimTarget` interface this patch adds.
 *
 * The names above are the 3.3.5 ones. The shrinker renames all of them per release (on 3.3.6: `Li4/v` ->
 * `Ll4/u`, `Le4/N` -> `Lh4/O`, `Le4/U` -> `Lh4/V`, `Le4/M` -> `Lh4/N`, and the job's `z0.n` -> `A0.l`) while
 * the logic stays the same, so nothing obfuscated is pinned below: every class, field and method is
 * resolved from the APK at patch time, from the app's log strings and the unobfuscated models. A shape
 * that no longer fits fails the patch instead of shipping a build that does nothing.
 */

/** The (unobfuscated) model the check-in state response is deserialized into. */
private const val SIMOSA_CHECK_IN_STATE =
    "Lcom/jazz/jazzworld/data/appmodels/dailyreward/response/DailyRewardCheckInStateResponse;"

/** The (unobfuscated) result wrapper the check-in collector receives (Loading / Success / Failure). */
private const val SIMOSA_API_RESOURCES =
    "Lcom/jazz/jazzworld/data/network/genericapis/helper/ApiResources;"

// ---------------------------------------------------------------------------------------------
// Daily Rewards tab (the MB ladder, not the check-in).
// ---------------------------------------------------------------------------------------------

/** The (unobfuscated) DTO for one rung of the ladder. */
private const val SIMOSA_REWARD_DAY_ITEM =
    "Lcom/jazz/jazzworld/data/appmodels/dailyreward/response/DaysListItem;"

/** The ladder's `data` payload — `currentDay`, `currentStatus` and the per-day `claimed` flag. */
private const val SIMOSA_REWARD_RESPONSE =
    "Lcom/jazz/jazzworld/data/appmodels/dailyreward/response/DailyRewardResponse;"
private const val SIMOSA_REWARD_DATA =
    "Lcom/jazz/jazzworld/data/appmodels/dailyreward/response/Data;"

/** The (unobfuscated) callback interface the ladder claim (`L1`) reports its result through. */
private const val SIMOSA_REWARD_CLAIM_LISTENER =
    "Lcom/jazz/jazzworld/data/network/genericapis/dailyreward/OnDailyRewardSubscriptionListeners;"

// ---------------------------------------------------------------------------------------------
// The patch's own runtime classes (resources/simosa/autoclaim/*.smali).
// ---------------------------------------------------------------------------------------------

private const val AUTO_CLAIM = "Lapp/proxma/simosa/AutoClaim;"
private const val CLAIM_TARGET = "Lapp/proxma/simosa/ClaimTarget;"
private const val CONTEXT = "Landroid/content/Context;"

internal val dailyCheckInPublishFingerprint = Fingerprint(
    returnType = "Ljava/lang/Object;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(string(" _pendingCheckInAdShow ")),
    custom = { m, _ -> m.name == "invokeSuspend" },
)

/**
 * `X1()` — the check-in itself, on the check-in ViewModel; this is what locates that ViewModel. A private
 * helper logs the same line for a different flow, so the public no-argument shape is part of the match.
 */
internal val dailyCheckInCallFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = emptyList(),
    filters = listOf(string("access_token missing, skip check-in")),
)

/** `L1(day, status)` — the ladder claim the "Claim it Now!" button drives; it locates the Daily Rewards ViewModel. */
internal val dailyRewardClaimFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(SIMOSA_REWARD_DAY_ITEM, "Ljava/lang/String;"),
    filters = listOf(string("callingDailyRewardSubscription: isClaimCurrentRewardDay= ")),
)

private fun Method.references() =
    implementation?.instructions?.map { (it as? ReferenceInstruction)?.reference }.orEmpty()

private fun MethodReference.takes(vararg types: String) =
    parameterTypes.map { it.toString() } == types.toList()

/**
 * `e2()` — the check-in ViewModel's "state -> screen" renderer: it hands the state read out of the flow to
 * the model factory (`Li4/x;->j(state, Boolean)`). Its only caller is the synthetic `x1(v)`, which all three
 * daily-reward state handlers (load / restore / claim response) call right after they write the fetched
 * response into the flow. So it runs exactly once per state response — never per recompose — and one of
 * those responses is the one the app fetches when the screen is opened.
 */
private fun Method.isDailyRewardStateRender() =
    returnType == "V" && parameterTypes.isEmpty() && AccessFlags.PRIVATE.isSet(accessFlags) &&
        references().any { it is MethodReference && it.takes(SIMOSA_CHECK_IN_STATE, "Z") }

/**
 * `<facade>;->d(scope, context, start, Function2, int, Object)` — kotlinx `launch$default`. Both the facade's
 * package (`Lnb/i;` on 3.3.4.2, `Lub/i;` on 3.3.5, `Lwb/i;` on 3.3.6) and its job type (`z0`, then `A0`) are
 * repackaged by the app's shrinker, so only the shape is matched.
 */
private fun MethodReference.isCoroutineLaunch() =
    parameterTypes.size == 6 &&
        parameterTypes[3].toString() == "Lkotlin/jvm/functions/Function2;" &&
        parameterTypes[4].toString() == "I" &&
        parameterTypes[5].toString() == "Ljava/lang/Object;"

/**
 * Index of the completion-callback registration in `t2()`, the ladder ViewModel's "a response arrived" entry
 * point: `job = launch(...)` followed by `job.<callback>(Function1)`. Every response handler stores the
 * response with `o2(...)` and then calls the synthetic `J1()`, which is this method. -1 if this method is not
 * that shape, which is what distinguishes it from the ViewModel's other zero-argument helpers.
 */
private fun Method.responseAppliedRegistration(): Int {
    val references = references()
    val job = references.firstNotNullOfOrNull { reference ->
        (reference as? MethodReference)?.takeIf { it.isCoroutineLaunch() }?.returnType
    } ?: return -1
    return references.indexOfFirst { reference ->
        reference is MethodReference && reference.definingClass == job &&
            reference.takes("Lkotlin/jvm/functions/Function1;")
    }
}

/** Adds a public final method with an empty body and fills it from [smali]. */
internal fun MutableClass.addProxmaMethod(
    name: String,
    parameters: List<String>,
    returnType: String,
    registers: Int,
    smali: String,
) {
    val method = ImmutableMethod(
        type,
        name,
        parameters.map { ImmutableMethodParameter(it, null, null) },
        returnType,
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        null,
        null,
        MutableMethodImplementation(registers),
    ).toMutable()
    method.addInstructionsWithLabels(0, smali.trimIndent())
    methods.add(method)
}

/**
 * Makes [viewModel] a `ClaimTarget`: context accessor, the [fire] body, and `proxmaOnApplied()`
 * (the hook entry point that schedules [kind]).
 */
internal fun MutableClass.addClaimTarget(
    kind: String,
    fireRegisters: Int,
    fire: String,
) {
    interfaces.add(CLAIM_TARGET)

    val contextField = fields.single { it.type == CONTEXT && !AccessFlags.STATIC.isSet(it.accessFlags) }
    addProxmaMethod(
        "proxmaContext", emptyList(), CONTEXT, 2,
        """
            iget-object v0, p0, $type->${contextField.name}:$CONTEXT
            return-object v0
        """,
    )
    addProxmaMethod("proxmaFire", emptyList(), "V", fireRegisters, fire)
    addProxmaMethod(
        "proxmaOnApplied", emptyList(), "V", 2,
        """
            const-string v0, "$kind"
            invoke-static {p0, v0}, $AUTO_CLAIM->schedule($CLAIM_TARGET Ljava/lang/String;)V
            return-void
        """,
    )
}

@Suppress("unused")
val autoCheckInPatch = bytecodePatch(
    name = "Auto check-in & claim daily reward",
    description = "Claims today's daily check-in and today's Daily Rewards rung on their own, once a " +
        "day, a few seconds after the app has loaded them — no ad and nothing to tap. The missed-days " +
        "restore and the day-10/20/30 milestone claim are untouched.",
) {
    compatibleWith(COMPATIBILITY_SIMOSA)
    extendWith("extensions/extension.mpe")

    execute {
        // ---- The two ViewModels, located by the methods the claims go through ----
        val checkInCall = dailyCheckInCallFingerprint.method
        val checkInViewModel = checkInCall.definingClass
        val claim = dailyRewardClaimFingerprint.method
        val ladderViewModel = claim.definingClass

        // ---- The completion callback of the response job, taken from t2() ----
        val ladder = mutableClassDefBy(ladderViewModel)
        val applied = ladder.methods.single {
            it.returnType == "V" && it.parameterTypes.isEmpty() && AccessFlags.PRIVATE.isSet(it.accessFlags) &&
                it.responseAppliedRegistration() >= 0
        }
        val appliedInstructions = applied.instructions.toList()
        val callbackRegistration = applied.responseAppliedRegistration()
        check(callbackRegistration > 0) { "t2() registers no completion callback" }
        val callbackType = (appliedInstructions.subList(0, callbackRegistration)
            .last { it.opcode == Opcode.NEW_INSTANCE } as ReferenceInstruction).reference as TypeReference

        // ---- Part 1: check in without the ad gate (the user's own tap) ----
        val method = dailyCheckInPublishFingerprint.method
        val instructions = method.instructions.toList()

        // The day's check-in event is published exactly once: `sget-object <reg>, Li4/c;->a:Li4/c;`, the
        // first constant of the event enum, which lives next to the ViewModel.
        val checkInPackage = checkInViewModel.substringBeforeLast('/') + "/"
        val publishIndex = instructions.indexOfFirst { insn ->
            if (insn.opcode != Opcode.SGET_OBJECT) return@indexOfFirst false
            val reference = (insn as? ReferenceInstruction)?.reference as? FieldReference ?: return@indexOfFirst false
            reference.definingClass.startsWith(checkInPackage) && reference.type == reference.definingClass &&
                reference.name == "a"
        }
        check(publishIndex >= 0) { "daily check-in publish site not found" }

        // The publish block ends with its own `goto` to the end of the state evaluation; reuse it as
        // the jump target so no new control flow is invented.
        val skipTo = instructions.withIndex()
            .first { (index, insn) -> index > publishIndex && insn.opcode == Opcode.GOTO }
            .value

        // At this point p0 is the lambda, one of whose fields is the ViewModel; p1 already holds the
        // state flow (not the ViewModel) and v1 is a dead boolean, so load the ViewModel into v1.
        // `proxmaManual()` latches today and logs the tap, so part 2 does not repeat it.
        val publishViewModelField = mutableClassDefBy(method.definingClass).fields.single { it.type == checkInViewModel }
        method.addInstructionsWithLabels(
            publishIndex,
            """
                iget-object v1, p0, ${method.definingClass}->${publishViewModelField.name}:$checkInViewModel
                invoke-virtual {v1}, $checkInViewModel->proxmaManual()V
                invoke-virtual {v1}, $checkInViewModel->${checkInCall.name}()V
                goto :proxma_auto_check_in
            """.trimIndent(),
            ExternalLabel("proxma_auto_check_in", skipTo),
        )

        // ---- Part 2: check in by itself once the app knows today's check-in state ----
        // The publish site above only runs once the user taps "Check in", so the check-in is also
        // scheduled from the renderer every check-in state response goes through.
        val checkInClass = mutableClassDefBy(checkInViewModel)
        val render = checkInClass.methods.single { it.isDailyRewardStateRender() }
        val renderInstructions = render.instructions.toList()

        // The state flow is the field the renderer reads right before casting to the state model. Its
        // name and interface type are app-internal and drift between releases (`j0:Lqb/B;` on 3.3.4.2,
        // `j0:Lxb/B;` on 3.3.5, `f0:Lzb/B;` on 3.3.6), so take both from that read rather than pinning them.
        val stateCast = renderInstructions.indexOfFirst { insn ->
            insn.opcode == Opcode.CHECK_CAST &&
                ((insn as ReferenceInstruction).reference as TypeReference).type == SIMOSA_CHECK_IN_STATE
        }
        check(stateCast > 0) { "daily-reward state is not read where expected" }
        val stateFlow = (renderInstructions.subList(0, stateCast).last { it.opcode == Opcode.IGET_OBJECT }
            as ReferenceInstruction).reference as FieldReference
        val flowType = stateFlow.type
        val renderRegisters = render.implementation?.registerCount ?: 0

        // `e2()` is an instance method with no parameters, so its receiver sits in the last register and
        // the three registers injected code uses (v0-v2) are its dead locals.
        check(renderRegisters >= 4) { "unexpected register layout in the daily-reward renderer" }

        val checkInGuards = """
            invoke-virtual {v1}, ${SIMOSA_CHECK_IN_STATE}->getCan_checkin_today()Ljava/lang/Boolean;
            move-result-object v2
            sget-object v3, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
            invoke-static {v2, v3}, Lkotlin/jvm/internal/Intrinsics;->areEqual(Ljava/lang/Object;Ljava/lang/Object;)Z
            move-result v2
            if-eqz v2, :proxma_claimed
            invoke-virtual {v1}, ${SIMOSA_CHECK_IN_STATE}->getMust_restore()Ljava/lang/Boolean;
            move-result-object v2
            sget-object v3, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
            invoke-static {v2, v3}, Lkotlin/jvm/internal/Intrinsics;->areEqual(Ljava/lang/Object;Ljava/lang/Object;)Z
            move-result v2
            if-nez v2, :proxma_restore
        """

        checkInClass.apply {
            addClaimTarget(
                kind = "checkin",
                fireRegisters = 6,
                fire = """
                    const-string v4, "checkin"
                    invoke-virtual {p0}, $checkInViewModel->proxmaContext()$CONTEXT
                    move-result-object v0
                    invoke-static {v0, v4}, $AUTO_CLAIM->check($CONTEXT Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v1
                    if-nez v1, :proxma_skip
                    iget-object v1, p0, $checkInViewModel->${stateFlow.name}:$flowType
                    invoke-interface {v1}, $flowType->getValue()Ljava/lang/Object;
                    move-result-object v1
                    if-eqz v1, :proxma_no_state
                    check-cast v1, $SIMOSA_CHECK_IN_STATE
                    $checkInGuards
                    invoke-static {v0, v4}, $AUTO_CLAIM->latch($CONTEXT Ljava/lang/String;)V
                    const-string v1, "today"
                    const-string v2, "-"
                    invoke-static {v4, v1, v2}, $AUTO_CLAIM->fired(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
                    invoke-virtual {p0}, $checkInViewModel->${checkInCall.name}()V
                    return-void
                    :proxma_no_state
                    const-string v1, "skip no-state"
                    goto :proxma_skip
                    :proxma_claimed
                    const-string v1, "skip claimed"
                    goto :proxma_skip
                    :proxma_restore
                    const-string v1, "skip must-restore"
                    :proxma_skip
                    invoke-static {v4, v1}, $AUTO_CLAIM->log(Ljava/lang/String;Ljava/lang/String;)V
                    return-void
                """,
            )
            addProxmaMethod(
                "proxmaManual", emptyList(), "V", 3,
                """
                    invoke-virtual {p0}, $checkInViewModel->proxmaContext()$CONTEXT
                    move-result-object v0
                    const-string v1, "checkin"
                    invoke-static {v0, v1}, $AUTO_CLAIM->manual($CONTEXT Ljava/lang/String;)V
                    return-void
                """,
            )
        }

        // Same guards as before, but the renderer only schedules; the claim itself re-checks them.
        render.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p0, $checkInViewModel->${stateFlow.name}:$flowType
                invoke-interface {v0}, $flowType->getValue()Ljava/lang/Object;
                move-result-object v0
                if-eqz v0, :proxma_auto_check_in_done
                check-cast v0, $SIMOSA_CHECK_IN_STATE
                invoke-virtual {v0}, ${SIMOSA_CHECK_IN_STATE}->getCan_checkin_today()Ljava/lang/Boolean;
                move-result-object v1
                sget-object v2, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                invoke-static {v1, v2}, Lkotlin/jvm/internal/Intrinsics;->areEqual(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v1
                if-eqz v1, :proxma_auto_check_in_done
                invoke-virtual {v0}, ${SIMOSA_CHECK_IN_STATE}->getMust_restore()Ljava/lang/Boolean;
                move-result-object v1
                sget-object v2, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                invoke-static {v1, v2}, Lkotlin/jvm/internal/Intrinsics;->areEqual(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v1
                if-nez v1, :proxma_auto_check_in_done
                invoke-virtual {p0}, $checkInViewModel->proxmaOnApplied()V
            """.trimIndent(),
            ExternalLabel("proxma_auto_check_in_done", renderInstructions.first()),
        )

        // ---- Part 3: claim today's Daily Rewards rung, after the response has been applied ----
        val ladderPackage = ladderViewModel.substringBeforeLast('/') + "/"

        // `L1(day, status)` returns at once unless its "already claimed" flag is false: the boolean
        // field it reads right before its first `if-nez` is `a0`.
        val claimBody = claim.instructions.toList()
        val claimGate = claimBody.indexOfFirst { it.opcode == Opcode.IF_NEZ }
        check(claimGate > 0) { "L1() has no claimed-flag gate" }
        val claimedFlag = ((claimBody.subList(0, claimGate).last { it.opcode == Opcode.IGET_BOOLEAN }
            as ReferenceInstruction).reference as FieldReference)
        check(claimedFlag.definingClass == ladderViewModel) { "L1() gates on a foreign flag" }

        // The selected rung: what the button reads off the ladder state and hands to `L1`, the closest
        // no-argument `DaysListItem` getter before that call (`Le4/U;->i()` on 3.3.5, `Lh4/V;->i()` on 3.3.6).
        val rungGetters = mutableSetOf<MethodReference>()
        classDefForEach { classDef ->
            if (!classDef.type.startsWith(ladderPackage)) return@classDefForEach
            classDef.methods.forEach { m ->
                var closest: MethodReference? = null
                m.references().forEach { reference ->
                    if (reference !is MethodReference) return@forEach
                    if (reference.definingClass == ladderViewModel && reference.name == claim.name) {
                        closest?.let(rungGetters::add)
                    } else if (reference.returnType == SIMOSA_REWARD_DAY_ITEM && reference.parameterTypes.isEmpty()) {
                        closest = reference
                    }
                }
            }
        }
        val rungGetter = rungGetters.singleOrNull()
            ?: error("expected one rung getter before the ladder claim, found $rungGetters")
        val ladderState = rungGetter.definingClass

        val responseField = ladder.fields.single { it.type == SIMOSA_REWARD_RESPONSE }

        // The ladder state flow: the field whose value is read and cast to `Le4/U` across the ViewModel.
        val ladderStateReads = ladder.methods.flatMap { m ->
            val body = m.implementation?.instructions?.toList() ?: return@flatMap emptyList()
            body.withIndex().mapNotNull { (index, insn) ->
                if (insn.opcode != Opcode.IGET_OBJECT) return@mapNotNull null
                val field = (insn as ReferenceInstruction).reference as FieldReference
                if (field.definingClass != ladderViewModel) return@mapNotNull null
                val window = body.subList(index + 1, minOf(body.size, index + 8))
                val readsValue = window.any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "getValue" }
                val castsToState = window.any {
                    it.opcode == Opcode.CHECK_CAST &&
                        ((it as ReferenceInstruction).reference as TypeReference).type == ladderState
                }
                field.takeIf { readsValue && castsToState }
            }
        }.groupingBy { it.name to it.type }.eachCount()
        check(ladderStateReads.isNotEmpty()) { "ladder state flow not found" }
        val (ladderStateName, ladderStateType) = ladderStateReads.maxBy { it.value }.key

        ladder.addClaimTarget(
            kind = "ladder",
            fireRegisters = 8,
            fire = """
                const-string v6, "ladder"
                invoke-virtual {p0}, $ladderViewModel->proxmaContext()$CONTEXT
                move-result-object v0
                invoke-static {v0, v6}, $AUTO_CLAIM->check($CONTEXT Ljava/lang/String;)Ljava/lang/String;
                move-result-object v1
                if-nez v1, :proxma_skip
                iget-object v1, p0, $ladderViewModel->${responseField.name}:$SIMOSA_REWARD_RESPONSE
                if-eqz v1, :proxma_no_response
                invoke-virtual {v1}, $SIMOSA_REWARD_RESPONSE->getData()$SIMOSA_REWARD_DATA
                move-result-object v1
                if-eqz v1, :proxma_no_response
                invoke-virtual {v1}, $SIMOSA_REWARD_DATA->getClaimed()Ljava/lang/String;
                move-result-object v2
                const-string v3, "true"
                invoke-static {v2, v3}, Lkotlin/jvm/internal/Intrinsics;->areEqual(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v2
                if-nez v2, :proxma_claimed
                iget-boolean v2, p0, $ladderViewModel->${claimedFlag.name}:Z
                if-nez v2, :proxma_flag
                iget-object v2, p0, $ladderViewModel->$ladderStateName:$ladderStateType
                invoke-interface {v2}, $ladderStateType->getValue()Ljava/lang/Object;
                move-result-object v2
                if-eqz v2, :proxma_no_rung
                check-cast v2, $ladderState
                invoke-virtual {v2}, $ladderState->${rungGetter.name}()$SIMOSA_REWARD_DAY_ITEM
                move-result-object v2
                if-eqz v2, :proxma_no_rung
                invoke-virtual {v1}, $SIMOSA_REWARD_DATA->getCurrentStatus()Ljava/lang/String;
                move-result-object v3
                if-nez v3, :proxma_have_status
                const-string v3, ""
                :proxma_have_status
                invoke-static {v0, v6}, $AUTO_CLAIM->latch($CONTEXT Ljava/lang/String;)V
                invoke-virtual {v2}, $SIMOSA_REWARD_DAY_ITEM->getDay()Ljava/lang/String;
                move-result-object v4
                invoke-static {v6, v4, v3}, $AUTO_CLAIM->fired(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
                invoke-virtual {p0, v2, v3}, $ladderViewModel->${claim.name}(${SIMOSA_REWARD_DAY_ITEM}Ljava/lang/String;)V
                return-void
                :proxma_no_response
                const-string v1, "skip no-response"
                goto :proxma_skip
                :proxma_claimed
                const-string v1, "skip claimed"
                goto :proxma_skip
                :proxma_flag
                const-string v1, "skip a0"
                goto :proxma_skip
                :proxma_no_rung
                const-string v1, "skip no-rung"
                :proxma_skip
                invoke-static {v6, v1}, $AUTO_CLAIM->log(Ljava/lang/String;Ljava/lang/String;)V
                return-void
            """,
        )

        // The completion callback `invoke(Throwable?)`: on a normal completion (null), hand over to the
        // ViewModel. It only calls the public method added above — no private field is touched here.
        val callback = mutableClassDefBy(callbackType.type)
        val callbackInvoke = callback.methods.single {
            it.name == "invoke" && it.parameterTypes == listOf("Ljava/lang/Object;")
        }
        val callbackOwner = callback.fields.single { it.type == ladderViewModel }
        check((callbackInvoke.implementation?.registerCount ?: 0) >= 3) { "unexpected completion callback layout" }
        callbackInvoke.addInstructionsWithLabels(
            0,
            """
                if-nez p1, :proxma_applied_skip
                iget-object v0, p0, ${callback.type}->${callbackOwner.name}:$ladderViewModel
                invoke-virtual {v0}, $ladderViewModel->proxmaOnApplied()V
            """.trimIndent(),
            ExternalLabel("proxma_applied_skip", callbackInvoke.instructions.first()),
        )

        // ---- Result logging (PROXMA_CLAIM success / error), for attempts this patch fired ----
        val claimListeners = mutableListOf<String>()
        val checkInCollectors = mutableListOf<String>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith(ladderPackage) && SIMOSA_REWARD_CLAIM_LISTENER in classDef.interfaces) {
                claimListeners += classDef.type
            }
            if (classDef.type.startsWith(checkInPackage) && classDef.methods.any { m ->
                    m.name == "a" && m.parameterTypes == listOf(SIMOSA_API_RESOURCES, "Lkotlin/coroutines/Continuation;") &&
                        m.implementation?.instructions?.any {
                            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "checkin: "
                        } == true
                }
            ) {
                checkInCollectors += classDef.type
            }
        }
        check(claimListeners.size == 1) { "expected one ladder claim listener, found $claimListeners" }
        check(checkInCollectors.size == 1) { "expected one check-in result collector, found $checkInCollectors" }

        mutableClassDefBy(claimListeners.single()).methods.forEach { m ->
            when (m.name) {
                "onDailyRewardSubscriptionSuccess" -> m.addInstructions(
                    0,
                    "invoke-static/range {p1 .. p1}, $AUTO_CLAIM->ladderSuccess(Ljava/lang/Object;)V",
                )
                "onDailyRewardSubscriptionFailed" -> m.addInstructions(
                    0,
                    "invoke-static/range {p1 .. p2}, $AUTO_CLAIM->ladderFailed(Ljava/lang/String;Ljava/lang/String;)V",
                )
            }
        }
        mutableClassDefBy(checkInCollectors.single()).methods
            .single { it.name == "a" && it.parameterTypes.firstOrNull() == SIMOSA_API_RESOURCES }
            .addInstructions(0, "invoke-static/range {p1 .. p1}, $AUTO_CLAIM->checkinResult(Ljava/lang/Object;)V")
    }
}
