package com.vermouthx.stocker.finance

import kotlin.math.abs

/**
 * Pure logic that maps `(scenario_tree, live_leader_price) → which branch is active`.
 * No Swing / no IntelliJ types — unit-testable.
 *
 * Active branch picking rule:
 *   1. If price is strictly above `out_of_scope_threshold.upper` → OUT_OF_SCOPE_UP
 *   2. If price is strictly below `out_of_scope_threshold.lower` → OUT_OF_SCOPE_DOWN
 *      (out_of_scope wins even if a branch also matches — the agent's predicted phase
 *      is already past its own ceiling/floor and must be re-reviewed)
 *   3. Otherwise: pick the first branch whose `price_trigger` is currently satisfied.
 *      Branches are evaluated in declaration order (the agent writes them in
 *      confidence-descending order so A_乐观 / B_基准 / C_悲观 already encodes
 *      priority — first match wins).
 *   4. If no branch matches: activeBranchId = null (in-between zone — UI shows
 *      distances but no row is highlighted).
 *
 * Volume condition (`volume_yi_gte`) is **NOT** checked here — we don't have live
 * intraday volume in this signal path. The branch can still be marked triggered
 * by price; the volume requirement is documented in the condition text for the
 * user to verify separately. This keeps the evaluator stateless and fast.
 */
internal object FinanceScenarioEvaluator {

    enum class OutOfScopeKind { UP, DOWN }

    data class BranchStatus(
        val branch: ScenarioBranch,
        val triggered: Boolean,
        /** Signed distance to the most-relevant anchor, in percent. */
        val distancePct: Double,
        /** The anchor price displayed alongside this branch ("¥145"). */
        val displayAnchor: Double,
        /** Display arrow: ↗ (above), ↘ (below), ▶ (in range), ● (triggered/exact). */
        val arrow: String,
    )

    data class Evaluation(
        val tree: ThreadScenarioTree,
        val leaderPrice: Double,
        /** % change of leaderPrice vs leaderRefPrice. */
        val leaderChangePct: Double,
        val branchStatuses: List<BranchStatus>,
        /** id of the active branch, or null if no branch matched (and not OOS). */
        val activeBranchId: String?,
        val outOfScope: OutOfScopeKind?,
        /** When [outOfScope] is non-null, the breached threshold (for display). */
        val breachedThreshold: Double?,
    ) {
        val anyTriggered: Boolean get() = activeBranchId != null || outOfScope != null
    }

    fun evaluate(tree: ThreadScenarioTree, leaderPrice: Double): Evaluation {
        val change = if (tree.leaderRefPrice > 0)
            (leaderPrice - tree.leaderRefPrice) / tree.leaderRefPrice * 100.0
        else 0.0

        // Out-of-scope check
        val oosUp = tree.outOfScopeUpper
        val oosDown = tree.outOfScopeLower
        val (oosKind, oosBreached) = when {
            oosUp != null && leaderPrice > oosUp -> OutOfScopeKind.UP to oosUp
            oosDown != null && leaderPrice < oosDown -> OutOfScopeKind.DOWN to oosDown
            else -> null to null
        }

        val statuses = tree.branches.map { branchStatus(it, leaderPrice) }
        // OOS suppresses active branch (out-of-scope branch should NOT be considered "matched")
        val activeId = if (oosKind != null) null
        else statuses.firstOrNull { it.triggered }?.branch?.id

        return Evaluation(
            tree = tree,
            leaderPrice = leaderPrice,
            leaderChangePct = change,
            branchStatuses = statuses,
            activeBranchId = activeId,
            outOfScope = oosKind,
            breachedThreshold = oosBreached,
        )
    }

    private fun branchStatus(branch: ScenarioBranch, cur: Double): BranchStatus {
        return when (branch.priceTriggerType) {
            PriceTriggerType.ABOVE -> {
                val v = branch.priceTriggerValue ?: 0.0
                val triggered = v > 0 && cur >= v
                val dist = if (v > 0) (cur - v) / v * 100.0 else 0.0
                BranchStatus(
                    branch = branch,
                    triggered = triggered,
                    distancePct = dist,
                    displayAnchor = v,
                    arrow = if (triggered) "●" else "↗",
                )
            }
            PriceTriggerType.BELOW -> {
                val v = branch.priceTriggerValue ?: 0.0
                val triggered = v > 0 && cur < v
                val dist = if (v > 0) (cur - v) / v * 100.0 else 0.0
                BranchStatus(
                    branch = branch,
                    triggered = triggered,
                    distancePct = dist,
                    displayAnchor = v,
                    arrow = if (triggered) "●" else "↘",
                )
            }
            PriceTriggerType.RANGE -> {
                val low = branch.priceTriggerLow ?: 0.0
                val high = branch.priceTriggerHigh ?: 0.0
                val triggered = low > 0 && high > low && cur in low..high
                // For range, anchor = midpoint, distance = % to nearest boundary (0 if inside)
                val mid = (low + high) / 2.0
                val dist = when {
                    !triggered && cur < low && low > 0 -> (cur - low) / low * 100.0
                    !triggered && cur > high && high > 0 -> (cur - high) / high * 100.0
                    else -> 0.0
                }
                BranchStatus(
                    branch = branch,
                    triggered = triggered,
                    distancePct = dist,
                    displayAnchor = mid,
                    arrow = if (triggered) "▶" else if (cur < low) "↗" else "↘",
                )
            }
            PriceTriggerType.UNKNOWN -> BranchStatus(
                branch = branch,
                triggered = false,
                distancePct = 0.0,
                displayAnchor = 0.0,
                arrow = "?",
            )
        }
    }

    /**
     * Visual fill ratio (0.0..1.0) for the branch's progress bar.
     *   - For above: 1.0 when triggered; else cur/value clamped 0..1
     *   - For below: 1.0 when triggered; else value/cur clamped 0..1 (we get closer
     *     to the trigger as cur falls toward value)
     *   - For range: 1.0 when triggered; else 1 - abs(distance)/10 clamped (the
     *     closer to the range, the fuller the bar; 10% away = empty)
     */
    fun fillRatio(s: BranchStatus, cur: Double): Double {
        if (s.triggered) return 1.0
        return when (s.branch.priceTriggerType) {
            PriceTriggerType.ABOVE -> {
                val v = s.branch.priceTriggerValue ?: return 0.0
                if (v <= 0 || cur <= 0) 0.0 else (cur / v).coerceIn(0.0, 1.0)
            }
            PriceTriggerType.BELOW -> {
                val v = s.branch.priceTriggerValue ?: return 0.0
                if (v <= 0 || cur <= 0) 0.0 else (v / cur).coerceIn(0.0, 1.0)
            }
            PriceTriggerType.RANGE -> {
                val d = abs(s.distancePct)
                (1.0 - d / 10.0).coerceIn(0.0, 1.0)
            }
            PriceTriggerType.UNKNOWN -> 0.0
        }
    }
}
