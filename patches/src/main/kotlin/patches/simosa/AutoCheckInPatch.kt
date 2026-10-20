package patches.simosa

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Simosa daily rewards: claim the day's rung without a tap.
 *
 * The "Daily Rewards" screen carries two independent reward systems:
 *
 *  1. "Daily Check-In" — `Li4/v`, the check-in ViewModel. Opening the screen starts `v.G2()`, which
 *     runs one suspend lambda (obfuscated `Li4/v$n`, the only `invokeSuspend` in the app that logs
 *     " _pendingCheckInAdShow "). That lambda reads the check-in state out of three flags and
 *     publishes a "pending check-in ad" event into the screen state flow. The UI shows the check-in
 *     ad, and only after it is dismissed calls the ViewModel's check-in (`X1()`, which logs
 *     "access_token missing, skip check-in"). Parts 1-2 below call `X1()` at the publish site and at
 *     the state renderer, so the check-in goes through with no ad and no tap. That half works and is
 *     unchanged.
 *
 *  2. "Daily Rewards" — `Le4/N`, the MB ladder (25/50/100/120/150/175/200 MB). Its "Claim it Now!"
 *     button drives exactly one call:
 *         Le4/N;->L1(DaysListItem day, String currentStatus)
 *     which POSTs `gamification/claim`. Part 3 below makes that call for the user.
 *
 * Why part 3 is anchored on `t2()` and not on the screen:
 *
 *  - Every ladder response the app receives is stored with `o2(response)` and then handed to `J1()`,
 *    which is `t2()`. All three response handlers (full list, claim result, cached) do exactly this,
 *    so `t2()` is the one place that runs for *every* route that fills the ladder — which route a
 *    given account takes no longer matters.
 *  - `t2()` runs with the fresh response already in `b0`, and it runs *before* its own coroutine has
 *    applied that response to the state flows. The rung is therefore read from the response's own
 *    `daysList` (the entry whose `day` equals `data.currentDay`), which is correct on the very first
 *    response. Reading the ladder out of the screen state at that moment would see the previous
 *    value — the earlier hooks did exactly that, and on a cold screen open they saw nothing.
 *  - `data.currentStatus` is passed through untouched. The app's own claim passes the real status
 *    string; passing anything else (an empty string) makes the server reject the claim silently.
 *
 * Guards, so the automatic claim can never claim twice:
 *  - `data.claimed == "true"` — the app's own "today is claimed" marker.
 *  - `Le4/N;->a0` — the ViewModel's "current reward day already claimed" flag, which the app
 *    recomputes from every response.
 *  - after firing the claim, `a0` is latched to `true` via the public setter `n2(Z)`.
 *
 * Anchored on unobfuscated app types (the DTOs and `Lnb/i;->d`) plus the release-pinned ViewModel,
 * all of which COMPATIBILITY_SIMOSA (3.3.4.2) fixes.
 */

/** The check-in ViewModel. */
private const val SIMOSA_CHECK_IN_VIEW_MODEL = "Li4/v;"

/** Field holding the fetched check-in state in that ViewModel (`StateFlow<DailyRewardCheckInStateResponse>`). */
private const val SIMOSA_CHECK_IN_STATE_FLOW = "j0"

/** The (unobfuscated) model the check-in state response is deserialized into. */
private const val SIMOSA_CHECK_IN_STATE =
    "Lcom/jazz/jazzworld/data/appmodels/dailyreward/response/DailyRewardCheckInStateResponse;"

/** `Li4/c` — the check-in event type enum; its `a` constant is the "daily check-in" event. */
private const val CHECK_IN_EVENT_ENUM = "Li4/c;"

// ---------------------------------------------------------------------------------------------
// Daily Rewards tab (the MB ladder, not the check-in).
// ---------------------------------------------------------------------------------------------

/** `Le4/N` — the Daily Rewards tab's ViewModel (`DailyRewardViewModel` in its metadata). */
private const val SIMOSA_REWARD_LADDER_VIEW_MODEL = "Le4/N;"

/** The (unobfuscated) DTO for one rung of the ladder. */
private const val SIMOSA_REWARD_DAY_ITEM =
    "Lcom/jazz/jazzworld/data/appmodels/dailyreward/response/DaysListItem;"

/** The ladder's `data` payload — `currentDay`, `currentStatus` and the per-day `claimed` flag. */
private const val SIMOSA_REWARD_RESPONSE =
    "Lcom/jazz/jazzworld/data/appmodels/dailyreward/response/DailyRewardResponse;"
private const val SIMOSA_REWARD_DATA =
    "Lcom/jazz/jazzworld/data/appmodels/dailyreward/response/Data;"

internal val dailyCheckInPublishFingerprint = Fingerprint(
    returnType = "Ljava/lang/Object;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(string(" _pendingCheckInAdShow ")),
    custom = { m, _ -> m.name == "invokeSuspend" },
)

/**
 * `e2()` — the check-in ViewModel's "state -> screen" renderer. It reads the check-in state out of
 * the `j0` flow and hands it to the model factory. Its only caller is the synthetic `x1(v)`, which
 * all three daily-reward state handlers (load / restore / claim response) call right after they
 * write the fetched response into `j0`. So it runs exactly once per state response — never per
 * recompose — and one of those responses is the one the app fetches when the screen is opened.
 */
internal val dailyRewardStateRenderFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    parameters = emptyList(),
    custom = { m, c ->
        c.type == SIMOSA_CHECK_IN_VIEW_MODEL && m.name == "e2" && m.implementation?.instructions?.any { insn ->
            val reference = (insn as? ReferenceInstruction)?.reference
            reference is MethodReference && reference.definingClass == "Li4/x;" && reference.name == "j"
        } == true
    },
)

/**
 * `Le4/N;->t2()` — the ladder ViewModel's "a response arrived" entry point. Every response handler
 * stores the response with `o2(...)` and then calls the synthetic `J1()`, which is this method; it
 * launches the coroutine that applies the response to the state flows. Hooking its tail therefore
 * runs once per ladder response, on every route, with the fresh response already in `b0`.
 *
 * Pinned on the structure rather than only the name: the body launches a coroutine through
 * `Lnb/i;->d(...)` (6 arguments), which is what distinguishes it from the ViewModel's other
 * zero-argument helpers.
 */
internal val dailyRewardResponseAppliedFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    parameters = emptyList(),
    custom = { m, c ->
        c.type == SIMOSA_REWARD_LADDER_VIEW_MODEL && m.name == "t2" &&
            m.implementation?.instructions?.any { insn ->
                val reference = (insn as? ReferenceInstruction)?.reference
                reference is MethodReference && reference.definingClass == "Lnb/i;" &&
                    reference.name == "d" && reference.parameterTypes.size == 6
            } == true
    },
)

@Suppress("unused")
val autoCheckInPatch = bytecodePatch(
    name = "Auto check-in & claim daily reward",
    description = "Claims today's daily check-in and today's Daily Rewards rung on their own, with " +
        "no ad and nothing to tap. The missed-days restore and the day-10/20/30 milestone claim " +
        "are untouched.",
) {
    compatibleWith(COMPATIBILITY_SIMOSA)

    execute {
        // ---- Part 1: check in without the ad gate ----
        val method = dailyCheckInPublishFingerprint.method
        val instructions = method.instructions.toList()

        // The day's check-in event is published exactly once: `sget-object <reg>, Li4/c;->a:Li4/c;`.
        val publishIndex = instructions.indexOfFirst { insn ->
            if (insn.opcode != Opcode.SGET_OBJECT) return@indexOfFirst false
            val reference = (insn as? ReferenceInstruction)?.reference as? FieldReference ?: return@indexOfFirst false
            reference.definingClass == CHECK_IN_EVENT_ENUM && reference.name == "a"
        }
        check(publishIndex >= 0) { "daily check-in publish site not found" }

        // The publish block ends with its own `goto` to the end of the state evaluation; reuse it as
        // the jump target so no new control flow is invented.
        val skipTo = instructions.withIndex()
            .first { (index, insn) -> index > publishIndex && insn.opcode == Opcode.GOTO }
            .value

        // At this point p0 is the lambda, whose field `e` is the ViewModel; p1 already holds the
        // state flow (not the ViewModel) and v1 is a dead boolean, so load the ViewModel into v1.
        method.addInstructionsWithLabels(
            publishIndex,
            """
                iget-object v1, p0, Li4/v${'$'}n;->e:Li4/v;
                invoke-virtual {v1}, Li4/v;->X1()V
                goto :proxma_auto_check_in
            """.trimIndent(),
            ExternalLabel("proxma_auto_check_in", skipTo),
        )

        // ---- Part 2: check in as soon as the app knows today's check-in state ----
        // The publish site above only runs once the user taps "Check in", so the check-in is also
        // started from the renderer every check-in state response goes through.
        val render = dailyRewardStateRenderFingerprint.method
        val renderInstructions = render.instructions.toList()
        val stateReads = renderInstructions.mapNotNull { insn ->
            val reference = (insn as? ReferenceInstruction)?.reference as? FieldReference
            reference?.takeIf {
                it.definingClass == SIMOSA_CHECK_IN_VIEW_MODEL && it.name == SIMOSA_CHECK_IN_STATE_FLOW
            }
        }
        check(stateReads.isNotEmpty()) { "daily-reward state is not read where expected" }

        // The flow's interface type is app-internal and drifts between releases (`Lqb/B;` on
        // 3.3.4.2, `Lxb/B;` on 3.3.5), so take it from the instruction that reads the field rather
        // than pinning it.
        val flowType = stateReads.first().type
        val renderRegisters = render.implementation?.registerCount ?: 0

        // `e2()` is an instance method with no parameters, so its receiver sits in the last register and
        // the three registers injected code uses (v0-v2) are its dead locals.
        check(renderRegisters >= 4) { "unexpected register layout in the daily-reward renderer" }

        render.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p0, $SIMOSA_CHECK_IN_VIEW_MODEL->${SIMOSA_CHECK_IN_STATE_FLOW}:$flowType
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
                invoke-virtual {p0}, ${SIMOSA_CHECK_IN_VIEW_MODEL}->X1()V
            """.trimIndent(),
            ExternalLabel("proxma_auto_check_in_done", renderInstructions.first()),
        )

        // ---- Part 3: claim today's Daily Rewards rung, on every route ----
        // `L1` is what the "Claim it Now!" button calls. Its second argument is the response's own
        // `currentStatus` — the server validates it, so it is passed through as-is.
        //
        // `t2()` runs before its own coroutine has applied the response to the state flows, so the
        // rung is taken from the response itself: the `daysList` entry whose `day` equals
        // `data.currentDay`. That is exactly the rung the screen highlights as ready.
        val applied = dailyRewardResponseAppliedFingerprint.method
        val appliedInstructions = applied.instructions.toList()
        val appliedReturns = appliedInstructions.count { it.opcode == Opcode.RETURN_VOID }
        check(appliedReturns == 1) { "unexpected daily-rewards response handler body" }
        val appliedEnd = appliedInstructions.last()
        check(appliedEnd.opcode == Opcode.RETURN_VOID) {
            "daily-rewards response handler does not end with return-void"
        }
        // `t2()` is an instance method with no parameters (`.registers 7`): p0 is v6 and v0-v5 are
        // its dead locals after the trailing callback registration.
        check((applied.implementation?.registerCount ?: 0) >= 7) {
            "unexpected register layout in the daily-rewards response handler"
        }

        applied.addInstructionsWithLabels(
            appliedInstructions.size - 1,
            """
                iget-object v0, v6, $SIMOSA_REWARD_LADDER_VIEW_MODEL->b0:$SIMOSA_REWARD_RESPONSE
                if-eqz v0, :proxma_ladder_claim_end
                iget-boolean v1, v6, $SIMOSA_REWARD_LADDER_VIEW_MODEL->a0:Z
                if-eqz v1, :proxma_ladder_claim_end
                invoke-virtual {v0}, $SIMOSA_REWARD_RESPONSE->getData()$SIMOSA_REWARD_DATA
                move-result-object v0
                if-eqz v0, :proxma_ladder_claim_end
                invoke-virtual {v0}, $SIMOSA_REWARD_DATA->getClaimed()Ljava/lang/String;
                move-result-object v1
                const-string v5, "true"
                invoke-static {v1, v5}, Lkotlin/jvm/internal/Intrinsics;->areEqual(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v1
                if-eqz v1, :proxma_ladder_claim_end
                invoke-virtual {v0}, $SIMOSA_REWARD_DATA->getCurrentStatus()Ljava/lang/String;
                move-result-object v2
                if-eqz v2, :proxma_ladder_claim_end
                invoke-virtual {v0}, $SIMOSA_REWARD_DATA->getCurrentDay()Ljava/lang/String;
                move-result-object v1
                if-eqz v1, :proxma_ladder_claim_end
                invoke-virtual {v0}, $SIMOSA_REWARD_DATA->getDaysList()Ljava/util/List;
                move-result-object v0
                if-eqz v0, :proxma_ladder_claim_end
                invoke-interface {v0}, Ljava/util/List;->iterator()Ljava/util/Iterator;
                move-result-object v3
                :proxma_ladder_claim_loop
                invoke-interface {v3}, Ljava/util/Iterator;->hasNext()Z
                move-result v4
                if-eqz v4, :proxma_ladder_claim_end
                invoke-interface {v3}, Ljava/util/Iterator;->next()Ljava/lang/Object;
                move-result-object v4
                check-cast v4, $SIMOSA_REWARD_DAY_ITEM
                invoke-virtual {v4}, $SIMOSA_REWARD_DAY_ITEM->getDay()Ljava/lang/String;
                move-result-object v5
                invoke-static {v5, v1}, Lkotlin/jvm/internal/Intrinsics;->areEqual(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v5
                if-eqz v5, :proxma_ladder_claim_loop
                invoke-virtual {v6, v4, v2}, $SIMOSA_REWARD_LADDER_VIEW_MODEL->L1(${SIMOSA_REWARD_DAY_ITEM}Ljava/lang/String;)V
                const/4 v5, 0x1
                invoke-virtual {v6, v5}, $SIMOSA_REWARD_LADDER_VIEW_MODEL->n2(Z)V
            """.trimIndent(),
            ExternalLabel("proxma_ladder_claim_end", appliedEnd),
        )
    }
}
