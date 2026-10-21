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
 * Simosa daily check-in: claim the day's reward without the ad gate.
 *
 * The screen is a Compose screen over an obfuscated ViewModel (`Li4/v`). Opening the daily-reward
 * screen starts `v.G2()`, which runs one suspend lambda (obfuscated `Li4/v$n`, the only
 * `invokeSuspend(Ljava/lang/Object;)Ljava/lang/Object;` in the app that logs " _pendingCheckInAdShow ").
 * That lambda reads the check-in state out of three flags and publishes a "pending check-in ad" event
 * into the screen state flow:
 *
 *     isRestore == false && isLevelReached == false && canCheckInToday == true
 *         -> publish Li4/b(c = Li4/c.a, d = Li4/d.a)   // "pending check-in ad show"
 *     otherwise
 *         -> publish Li4/b(c = Li4/c.b, ...)           // restore / level-up paths (not touched here)
 *
 * The UI reacts to that event by showing the daily check-in ad, and only after the ad is dismissed
 * does it call the ViewModel's check-in (obfuscated `X1()`, the method that logs
 * "access_token missing, skip check-in", calls the daily-reward `performCheckIn` API and then
 * auto-claims the reward for the level). So today: open the screen -> ad -> claim.
 *
 * Fix: at the point where the daily-check-in event is published, call the check-in directly and jump
 * past the publish. The ad never fires; the reward is still granted (same server calls). Everything
 * else in that lambda (the restore and level-up events) is left alone, so the "missed days" restore
 * sheet and the day-10/20/30 milestone claim keep working exactly as before.
 *
 * The publish site above only runs when the UI decides to check in (the flow the "Check in" button
 * starts), so on its own it does nothing on a plain screen open — the ad event is never published
 * until the user taps. Part 2 below covers the case the reporter actually hit: the reward is claimed
 * by itself as soon as the app knows today's check-in state.
 *
 * Anchored on the log string plus the SGET_OBJECT of the day's check-in event enum constant, so the
 * only hard-coded obfuscated names are the lambda's field holding the ViewModel and the enum with the
 * event type — both of which are release-pinned by COMPATIBILITY_SIMOSA (3.3.4.2).
 */

/** The daily-reward ViewModel. */
private const val SIMOSA_REWARD_VIEW_MODEL = "Li4/v;"

/** Field holding the fetched check-in state in that ViewModel (`StateFlow<DailyRewardCheckInStateResponse>`). */
private const val SIMOSA_REWARD_STATE_FLOW = "j0"

/** The (unobfuscated) model the check-in state response is deserialized into. */
private const val SIMOSA_REWARD_STATE =
    "Lcom/jazz/jazzworld/data/appmodels/dailyreward/response/DailyRewardCheckInStateResponse;"

/** `Li4/c` — the check-in event type enum; its `a` constant is the "daily check-in" event. */
private const val CHECK_IN_EVENT_ENUM = "Li4/c;"

// ---------------------------------------------------------------------------------------------
// Daily Rewards tab (the OLD reward system — the ladder, not the check-in).
// ---------------------------------------------------------------------------------------------

/** `Le4/N` — the Daily Rewards tab's ViewModel (`DailyRewardViewModel` in its metadata). */
private const val SIMOSA_REWARD_LADDER_VIEW_MODEL = "Le4/N;"

/**
 * `Le4/N$d$a` — the anonymous listener `Le4/N` installs as `OnDailyRewardListListeners` to receive
 * the day ladder (`data.daysList` + `currentDay` + `currentStatus`) from the repository.
 */
private const val SIMOSA_REWARD_LIST_LISTENER = "Le4/N\$d\$a;"

/** `Le4/U` — the ladder state (`UnlockRewardState`) the listener publishes into the state flow `Le4/N;->m0`. */
private const val SIMOSA_REWARD_LADDER_STATE = "Le4/U;"

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
 * `e2()` — the daily-reward ViewModel's "state -> screen" renderer. It reads the check-in state out
 * of the `j0` flow and hands it to the model factory. Its only caller is the synthetic `x1(v)`, which
 * all three daily-reward state handlers (load / restore / claim response) call right after they write
 * the fetched response into `j0`. So it runs exactly once per state response — never per recompose —
 * and one of those responses is the one the app fetches when the daily-rewards screen is opened.
 */
internal val dailyRewardStateRenderFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    parameters = emptyList(),
    custom = { m, c ->
        c.type == SIMOSA_REWARD_VIEW_MODEL && m.name == "e2" && m.implementation?.instructions?.any { insn ->
            val reference = (insn as? ReferenceInstruction)?.reference
            reference is MethodReference && reference.definingClass == "Li4/x;" && reference.name == "j"
        } == true
    },
)

/**
 * `Le4/N$d$a;->onDailyRewardListSuccess(DailyRewardResponse)V` — the listener that carries the day
 * ladder into the Daily Rewards tab. It is the only implementation of `OnDailyRewardListListeners`
 * that runs on the tab's list response: it reads the ViewModel's ladder state flow
 * (`Le4/N;->m0`), casts it to `Le4/U`, rebuilds it with the fetched `data.daysList` via
 * `Le4/U;->b(...)` and publishes it back. Its tail is therefore exactly "the ladder is loaded and
 * today's day is known", which is the moment the tab shows the "Claim it Now!" button.
 *
 * The class name is obfuscated but pinned by COMPATIBILITY_SIMOSA (3.3.4.2); the method name is not
 * obfuscated (it implements the SDK interface), and the parameter/return types are app DTOs.
 */
internal val dailyRewardListListenerFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Lcom/jazz/jazzworld/data/appmodels/dailyreward/response/DailyRewardResponse;"),
    custom = { m, c -> c.type == SIMOSA_REWARD_LIST_LISTENER && m.name == "onDailyRewardListSuccess" },
)

/**
 * `Le4/J$c;->n(...)` — the screen-shown lambda of the Daily Rewards screen. It runs once when the
 * screen is shown and is handed the ViewModel, the ladder state and the raw response, so the claim
 * can be started from there whichever route filled the ladder.
 *
 * Part 3 above is anchored on the *full list* response, which the app only requests for one account
 * shape (`!isNonJazzLogin && isPrepaid && isCurrentUserParrent`). For an account that takes another
 * route (the eligibility / claim-status refresh) that callback never runs, so nothing is attempted:
 * no claim, no message, no error — exactly the report on issue #29.
 *
 * The lambda's numbering inside `Le4/J` is compiler-generated and drifts between releases (3.3.4.2:
 * `J$c`, 3.3.5: `J$d`), so the anchor pins the enclosing class and the exact 7-parameter signature
 * plus the screen-name literal, never the numbered subclass itself.
 */
internal val dailyRewardScreenShownFingerprint = Fingerprint(
    returnType = "Lkotlin/Unit;",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC, AccessFlags.FINAL),
    parameters = listOf(
        "Le4/T;",
        "Landroid/content/Context;",
        "Ljava/lang/String;",
        SIMOSA_REWARD_LADDER_VIEW_MODEL,
        SIMOSA_REWARD_LADDER_STATE,
        SIMOSA_REWARD_RESPONSE,
        "Le4/a;",
    ),
    filters = listOf(string("Daily Reward Screen")),
    custom = { m, c -> c.type.startsWith("Le4/J\$") && m.name == "n" },
)

@Suppress("unused")
val autoCheckInPatch = bytecodePatch(
    name = "Auto check-in & claim daily reward",
    description = "Claims the daily check-in reward on its own: opening the daily-rewards screen no " +
        "longer waits for the check-in ad, the reward is granted right away. The missed-days " +
        "restore and the day-10/20/30 milestone claim are untouched.",
) {
    compatibleWith(COMPATIBILITY_SIMOSA)

    execute {
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

        // ---- Part 2: claim the reward by itself, without waiting for a tap ----
        // The reporter's case: opening the daily-rewards screen does nothing, because the UI only
        // enters the lambda hooked above once the user taps "Check in". So instead of waiting for that
        // tap, claim as soon as the app knows today's check-in state: hook the renderer that every
        // state response runs through, and from there call the same check-in the tap would call.
        val render = dailyRewardStateRenderFingerprint.method
        val renderInstructions = render.instructions.toList()
        val stateReads = renderInstructions.count { insn ->
            val reference = (insn as? ReferenceInstruction)?.reference as? FieldReference
            reference != null && reference.definingClass == SIMOSA_REWARD_VIEW_MODEL &&
                reference.name == SIMOSA_REWARD_STATE_FLOW
        }
        check(stateReads == 1) { "daily-reward state is not read where expected" }
        val renderRegisters = render.implementation?.registerCount ?: 0

        // `e2()` is an instance method with no parameters, so its receiver sits in the last register and
        // the three registers injected code uses (v0-v2) are its dead locals.
        check(renderRegisters >= 4) { "unexpected register layout in the daily-reward renderer" }

        // Detect the obfuscated package name for kotlinx.coroutines.flow.StateFlow (`Lqb/B;` on 3.3.4.2, `Lxb/B;` on 3.3.5)
        // dynamically from the method's own `reference.type` so the descriptor is always identical to the host class.
        val stateFlowRef = (renderInstructions.first { insn ->
            val ref = (insn as? ReferenceInstruction)?.reference as? FieldReference
            ref != null && ref.definingClass == SIMOSA_REWARD_VIEW_MODEL && ref.name == SIMOSA_REWARD_STATE_FLOW
        } as ReferenceInstruction).reference as FieldReference
        val stateFlowType = stateFlowRef.type

        render.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p0, $SIMOSA_REWARD_VIEW_MODEL->${SIMOSA_REWARD_STATE_FLOW}:$stateFlowType
                invoke-interface {v0}, ${stateFlowType}->getValue()Ljava/lang/Object;
                move-result-object v0
                if-eqz v0, :proxma_auto_claim_done
                check-cast v0, $SIMOSA_REWARD_STATE
                invoke-virtual {v0}, ${SIMOSA_REWARD_STATE}->getCan_checkin_today()Ljava/lang/Boolean;
                move-result-object v1
                sget-object v2, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                invoke-static {v1, v2}, Lkotlin/jvm/internal/Intrinsics;->areEqual(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v1
                if-eqz v1, :proxma_auto_claim_done
                invoke-virtual {v0}, ${SIMOSA_REWARD_STATE}->getMust_restore()Ljava/lang/Boolean;
                move-result-object v1
                sget-object v2, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                invoke-static {v1, v2}, Lkotlin/jvm/internal/Intrinsics;->areEqual(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v1
                if-nez v1, :proxma_auto_claim_done
                invoke-virtual {p0}, ${SIMOSA_REWARD_VIEW_MODEL}->X1()V
            """.trimIndent(),
            ExternalLabel("proxma_auto_claim_done", renderInstructions.first()),
        )

        // ---- Part 3: claim the Daily Rewards tab's day on screen load ----
        // "Daily Check-In" (parts 1-2) and "Daily Rewards" are two separate reward systems behind the
        // one "Daily Rewards" screen. The Daily Rewards tab renders the MB ladder
        // (25/50/100/120/150/175/200) and its "Claim it Now!" button drives exactly one call:
        //     Le4/N;->L1(DaysListItem day, String currentStatus)
        // which POSTs `gamification/claim` and publishes the result as a new ladder state.
        //
        // This listener is the moment the tab knows the ladder: it stores the response in the
        // ViewModel (`o2`) and republishes the ladder state, whose two day slots are "today's rung"
        // (field g, read by `f()`) and "the rung the user selected" (field f, read by `i()`), plus the
        // response's `currentStatus` (field j, read by `e()`). The button's own call passes exactly
        // `i()` / `data.currentStatus`; for the automatic case the right rung is today's — `f()`.
        //
        // Two things make the automated call safe to fire on every list load:
        //  - `L1` is itself gated on `Le4/N;->a0` (`true` = today's reward is already claimed). That
        //    flag is recomputed asynchronously from the response, so at this point it can still hold
        //    the previous value. We therefore mirror what the app's own computation would set —
        //    `a0 = (data.claimed == "true")` — via the public setter `n2(Z)` before calling `L1`.
        //  - The guard below bails out when the ladder has no rung for today, and when `data.claimed`
        //    is already `"true"`, so a re-fetch after a successful claim cannot claim twice.
        //
        // Every branch jumps to the method's own trailing `return-void` — no new control flow.
        val listener = dailyRewardListListenerFingerprint.method
        val listenerInstructions = listener.instructions.toList()
        val listenerReturns = listenerInstructions.count { it.opcode == Opcode.RETURN_VOID }
        check(listenerReturns == 1) { "unexpected daily-rewards listener body" }
        val listenerEnd = listenerInstructions.last()
        check(listenerEnd.opcode == Opcode.RETURN_VOID) {
            "daily-rewards listener does not end with return-void"
        }
        // Instance method, one parameter: p0 = the listener, p1 = the response (`.registers 24`, so
        // p0/p1 are v22/v21 and v2-v8 are dead locals at the tail).
        check((listener.implementation?.registerCount ?: 0) >= 9) {
            "unexpected register layout in the daily-rewards listener"
        }

        // Detect the return type of `Le4/N;->F1()` directly from the listener class's own instructions
        // (`Lqb/B;` on 3.3.4.2, `Lxb/B;` on 3.3.5) so it matches whichever package the app was built with.
        val f1Call = listenerInstructions.first { insn ->
            val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
            ref != null && ref.definingClass == SIMOSA_REWARD_LADDER_VIEW_MODEL && ref.name == "F1"
        }
        val ladderStateFlowType = ((f1Call as ReferenceInstruction).reference as MethodReference).returnType

        listener.addInstructionsWithLabels(
            listenerInstructions.size - 1,
            """
                move-object/from16 v2, p0
                move-object/from16 v4, p1
                iget-object v2, v2, $SIMOSA_REWARD_LIST_LISTENER->a:$SIMOSA_REWARD_LADDER_VIEW_MODEL
                invoke-static {v2}, ${SIMOSA_REWARD_LADDER_VIEW_MODEL}->F1(${SIMOSA_REWARD_LADDER_VIEW_MODEL})$ladderStateFlowType
                move-result-object v3
                invoke-interface {v3}, ${ladderStateFlowType}->getValue()Ljava/lang/Object;
                move-result-object v3
                if-eqz v3, :proxma_auto_claim_ladder
                check-cast v3, $SIMOSA_REWARD_LADDER_STATE
                invoke-virtual {v3}, ${SIMOSA_REWARD_LADDER_STATE}->f()$SIMOSA_REWARD_DAY_ITEM
                move-result-object v5
                if-eqz v5, :proxma_auto_claim_ladder
                invoke-virtual {v5}, $SIMOSA_REWARD_DAY_ITEM->getDay()Ljava/lang/String;
                move-result-object v6
                if-eqz v6, :proxma_auto_claim_ladder
                if-eqz v4, :proxma_auto_claim_ladder
                invoke-virtual {v4}, $SIMOSA_REWARD_RESPONSE->getData()$SIMOSA_REWARD_DATA
                move-result-object v6
                if-eqz v6, :proxma_auto_claim_ladder
                invoke-virtual {v6}, $SIMOSA_REWARD_DATA->getClaimed()Ljava/lang/String;
                move-result-object v7
                const-string v8, "true"
                invoke-static {v7, v8}, Lkotlin/jvm/internal/Intrinsics;->areEqual(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v7
                if-eqz v7, :proxma_auto_claim_ladder
                invoke-virtual {v6}, $SIMOSA_REWARD_DATA->getCurrentStatus()Ljava/lang/String;
                move-result-object v7
                if-eqz v7, :proxma_auto_claim_status
                const-string v7, ""
                :proxma_auto_claim_status
                const/4 v8, 0x0
                invoke-virtual {v2, v8}, ${SIMOSA_REWARD_LADDER_VIEW_MODEL}->n2(Z)V
                invoke-virtual {v2, v5, v7}, ${SIMOSA_REWARD_LADDER_VIEW_MODEL}->L1(${SIMOSA_REWARD_DAY_ITEM}Ljava/lang/String;)V
                const/4 v8, 0x1
                invoke-virtual {v2, v8}, ${SIMOSA_REWARD_LADDER_VIEW_MODEL}->n2(Z)V
            """.trimIndent(),
            ExternalLabel("proxma_auto_claim_ladder", listenerEnd),
        )

        // ---- Part 4: claim when the screen is shown, whichever route filled the ladder ----
        // Only one of the routes that fill the Daily Rewards tab runs the listener hooked in part 3.
        // This lambda runs on every route, and it is handed the ViewModel (p3), the ladder state (p4)
        // and the response (p5), so the same claim can be started from here.
        //
        // Guards: `a0` is the ViewModel's own "today already claimed" flag — it blocks the attempt,
        // and the attempt latches it so neither part 3 nor a later screen show can claim again;
        // `claimed == "true"` is the app's own already-claimed marker and blocks it too. Today's rung
        // is `f()`; `i()` (the selected rung) is the fallback when only that slot is filled in.
        //
        // Note: p3/p4/p5 live above v15, and `invoke-virtual` only reaches v0-v15 — so they are
        // copied into low registers first. The internal label must differ from the ExternalLabel
        // name: a duplicate label makes the inline assembler return no class at all.
        val shown = dailyRewardScreenShownFingerprint.method
        val shownRegisters = shown.implementation?.registerCount ?: 0
        check(shownRegisters >= 10) { "unexpected register layout in the daily-rewards screen lambda" }

        shown.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v3, p3
                move-object/from16 v4, p4
                move-object/from16 v5, p5
                if-eqz v4, :proxma_shown_skip
                if-eqz v3, :proxma_shown_skip
                invoke-virtual {v4}, $SIMOSA_REWARD_LADDER_STATE->f()$SIMOSA_REWARD_DAY_ITEM
                move-result-object v0
                if-eqz v0, :proxma_shown_have_day
                invoke-virtual {v4}, $SIMOSA_REWARD_LADDER_STATE->i()$SIMOSA_REWARD_DAY_ITEM
                move-result-object v0
                :proxma_shown_have_day
                if-eqz v0, :proxma_shown_skip
                invoke-virtual {v0}, $SIMOSA_REWARD_DAY_ITEM->getDay()Ljava/lang/String;
                move-result-object v1
                if-eqz v1, :proxma_shown_skip
                iget-boolean v1, v3, $SIMOSA_REWARD_LADDER_VIEW_MODEL->a0:Z
                if-eqz v1, :proxma_shown_skip
                const-string v1, ""
                if-eqz v5, :proxma_shown_claim
                invoke-virtual {v5}, $SIMOSA_REWARD_RESPONSE->getData()$SIMOSA_REWARD_DATA
                move-result-object v2
                if-eqz v2, :proxma_shown_claim
                invoke-virtual {v2}, $SIMOSA_REWARD_DATA->getClaimed()Ljava/lang/String;
                move-result-object v1
                const-string v2, "true"
                invoke-static {v1, v2}, Lkotlin/jvm/internal/Intrinsics;->areEqual(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v2
                if-eqz v2, :proxma_shown_skip
                const-string v1, ""
                invoke-virtual {v5}, $SIMOSA_REWARD_RESPONSE->getData()$SIMOSA_REWARD_DATA
                move-result-object v2
                if-eqz v2, :proxma_shown_claim
                invoke-virtual {v2}, $SIMOSA_REWARD_DATA->getCurrentStatus()Ljava/lang/String;
                move-result-object v2
                if-eqz v2, :proxma_shown_claim
                move-object v1, v2
                :proxma_shown_claim
                invoke-virtual {v3, v0, v1}, $SIMOSA_REWARD_LADDER_VIEW_MODEL->L1(${SIMOSA_REWARD_DAY_ITEM}Ljava/lang/String;)V
                const/4 v1, 0x1
                invoke-virtual {v3, v1}, $SIMOSA_REWARD_LADDER_VIEW_MODEL->n2(Z)V
                :proxma_shown_skip
            """.trimIndent(),
            ExternalLabel("proxma_shown_entry", shown.instructions.first()),
        )
    }
}
