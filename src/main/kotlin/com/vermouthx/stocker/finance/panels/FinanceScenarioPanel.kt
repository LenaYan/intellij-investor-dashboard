package com.vermouthx.stocker.finance.panels

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.messages.MessageBusConnection
import com.vermouthx.stocker.entities.StockerQuote
import com.vermouthx.stocker.finance.FinanceBridgeService
import com.vermouthx.stocker.finance.FinanceNotifier
import com.vermouthx.stocker.finance.FinanceScenarioEvaluator
import com.vermouthx.stocker.finance.FinanceSymbol
import com.vermouthx.stocker.finance.PriceTriggerType
import com.vermouthx.stocker.finance.ScenarioBranch
import com.vermouthx.stocker.finance.ThreadScenarioTree
import com.vermouthx.stocker.listeners.StockerQuoteUpdateNotifier
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Scenario state-machine panel that consumes `thread_scenario_tree` from today's
 * market-research.md and pairs it with the leader's live quote to show:
 *
 *   - Current thread name / phase / age / leader header
 *   - Per-branch progress bar + triggered / awaiting status
 *   - Out-of-scope banner when leader breaches upper/lower thresholds
 *
 * Notifications: when the active branch flips between renders, a single
 * THREAD_BRANCH_FLIP balloon is fired (rate-limited by FinanceNotifier's per-symbol-
 * per-kind-per-day throttle). When leader enters out-of-scope for the first time
 * in the day, a THREAD_OUT_OF_SCOPE error balloon fires.
 *
 * Wiring:
 *   - subscribes to FinanceBridgeService refresh listener (file watcher detected
 *     a market-research.md change → re-read scenario_tree)
 *   - subscribes to STOCK_ALL_QUOTE_UPDATE_TOPIC and filters for the leader symbol
 */
internal class FinanceScenarioPanel : JPanel(BorderLayout()) {

    private val refreshHook: () -> Unit = { SwingUtilities.invokeLater { rebuild() } }
    private var messageBusConnection: MessageBusConnection? = null

    // Cached "what was the active branch last render" so we only fire a flip
    // notification on actual transitions, not on every quote tick. Keyed by tree
    // label so switching trees doesn't fire a false flip.
    private val lastActiveBranchByLabel: MutableMap<String, String?> = HashMap()
    private val lastOosByLabel: MutableMap<String, FinanceScenarioEvaluator.OutOfScopeKind?> = HashMap()

    /** Latest evaluation (or null if no tree + no quote). */
    private var latestEval: FinanceScenarioEvaluator.Evaluation? = null

    /** User-selected scenario tree label; null = use snapshot's default (first). */
    private var selectedLabel: String? = null

    /** Top-of-panel selector. Visible only when scenarioTrees.size > 1. */
    private val selector = ComboBox<String>().apply {
        isVisible = false
        addActionListener {
            val choice = selectedItem as? String ?: return@addActionListener
            if (choice == selectedLabel) return@addActionListener
            selectedLabel = choice
            rebuild()
        }
    }

    init {
        background = JBColor.background()
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        FinanceBridgeService.instance.addRefreshListener(refreshHook)
        messageBusConnection = ApplicationManager.getApplication().messageBus.connect().apply {
            subscribe(StockerQuoteUpdateNotifier.STOCK_ALL_QUOTE_UPDATE_TOPIC,
                object : StockerQuoteUpdateNotifier {
                    override fun syncQuotes(quotes: List<StockerQuote>, size: Int) {
                        onQuotes(quotes)
                    }

                    override fun syncIndices(indices: List<StockerQuote>) { /* unused */ }
                })
        }
        rebuild()
    }

    fun dispose() {
        FinanceBridgeService.instance.removeRefreshListener(refreshHook)
        messageBusConnection?.disconnect()
        messageBusConnection = null
    }

    /** Resolve which tree to show: user selection (if still present), else snapshot default. */
    private fun resolveActiveTree(): Pair<String, ThreadScenarioTree>? {
        val snap = FinanceBridgeService.instance.snapshot()
        val trees = snap.scenarioTrees
        if (trees.isEmpty()) return null
        val choice = selectedLabel
        if (choice != null && trees.containsKey(choice)) {
            return choice to trees.getValue(choice)
        }
        val firstEntry = trees.entries.first()
        return firstEntry.key to firstEntry.value
    }

    private fun syncSelectorOptions() {
        val snap = FinanceBridgeService.instance.snapshot()
        val labels = snap.scenarioTrees.keys.toList()
        selector.removeAllItems()
        labels.forEach { selector.addItem(it) }
        // Visible only when multi-thread
        selector.isVisible = labels.size > 1
        if (labels.isEmpty()) return
        val chosen = selectedLabel?.takeIf { it in labels } ?: labels.first()
        selectedLabel = chosen
        if (selector.selectedItem != chosen) selector.selectedItem = chosen
    }

    private fun onQuotes(quotes: List<StockerQuote>) {
        val (_, tree) = resolveActiveTree() ?: return
        if (quotes.isEmpty()) return
        val leaderKey = tree.leaderKey
        val q = quotes.firstOrNull { FinanceSymbol.normalize(it.code) == leaderKey } ?: return
        val price = q.current
        if (price <= 0) return
        SwingUtilities.invokeLater {
            renderEvaluation(FinanceScenarioEvaluator.evaluate(tree, price))
        }
    }

    /**
     * Full rebuild — called when scenario_trees changes (file watcher), panel first
     * mounts, or user picks a different tree from the selector.
     */
    private fun rebuild() {
        syncSelectorOptions()
        val resolved = resolveActiveTree()
        if (resolved == null) {
            renderEmptyState()
            return
        }
        val (_, tree) = resolved
        // No live price yet — render static header with awaiting state
        val priorEval = latestEval
        if (priorEval != null && priorEval.tree.leaderKey == tree.leaderKey) {
            renderEvaluation(FinanceScenarioEvaluator.evaluate(tree, priorEval.leaderPrice))
        } else {
            renderStaticHeader(tree)
        }
    }

    // ── render paths ──────────────────────────────────────────────────────────

    private fun renderEmptyState() {
        latestEval = null
        removeAll()
        val msg = JBLabel(
            "<html><center>暂无 <code>thread_scenario_tree</code> 数据。<br>" +
                "今日 market-research 或 thread-tracker 跑出 v2.x schema YAML 后会自动出现。</center></html>",
            SwingConstants.CENTER
        ).apply {
            foreground = JBColor.GRAY
            verticalAlignment = SwingConstants.CENTER
        }
        add(msg, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun renderStaticHeader(tree: ThreadScenarioTree) {
        removeAll()
        add(wrapTopWithSelector(buildHeader(tree, null, null, null)), BorderLayout.NORTH)
        add(buildBranchList(tree, statuses = emptyList(), activeId = null), BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun renderEvaluation(eval: FinanceScenarioEvaluator.Evaluation) {
        latestEval = eval
        maybeFireFlipNotification(eval)
        removeAll()
        add(
            wrapTopWithSelector(buildHeader(eval.tree, eval.leaderPrice, eval.leaderChangePct, eval.outOfScope)),
            BorderLayout.NORTH
        )
        add(buildBranchList(eval.tree, eval.branchStatuses, eval.activeBranchId), BorderLayout.CENTER)
        // OOS footer banner (only when triggered)
        if (eval.outOfScope != null) {
            add(buildOosBanner(eval), BorderLayout.SOUTH)
        }
        revalidate()
        repaint()
    }

    /** Stack the multi-thread selector (visible only when > 1 tree) above the header. */
    private fun wrapTopWithSelector(header: Component): JPanel {
        val box = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        if (selector.isVisible) {
            val selectorRow = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(2, 8, 4, 8)
                add(JBLabel("查看主线: ").apply { foreground = JBColor.GRAY }, BorderLayout.WEST)
                add(selector, BorderLayout.CENTER)
            }
            box.add(selectorRow)
        }
        box.add(header)
        return box
    }

    private fun maybeFireFlipNotification(eval: FinanceScenarioEvaluator.Evaluation) {
        val notifier = FinanceBridgeService.instance.getNotifier() ?: return
        val tree = eval.tree
        val curBranch = eval.activeBranchId
        val curOos = eval.outOfScope
        // Key flip state by tree label so switching threads in the dropdown
        // doesn't fire a spurious flip for the newly-selected thread.
        val label = selectedLabel ?: (tree.leaderName ?: tree.leaderSymbol)
        val priorBranch = lastActiveBranchByLabel[label]
        val priorOos = lastOosByLabel[label]

        if (curOos != null && curOos != priorOos) {
            val threshold = eval.breachedThreshold ?: 0.0
            val dir = if (curOos == FinanceScenarioEvaluator.OutOfScopeKind.UP) "上沿" else "下沿"
            val mainThreadName = FinanceBridgeService.instance.snapshot().mainThread ?: label
            val detail = "走势超出${dir} ¥${"%.2f".format(threshold)}\n主线 \"$mainThreadName\" 已不在任何预案分支内 — 次日需重审主线"
            notifier.fire(
                tree.leaderSymbol, tree.leaderName ?: tree.leaderSymbol,
                FinanceNotifier.Kind.THREAD_OUT_OF_SCOPE,
                eval.leaderPrice, eval.leaderChangePct, detail
            )
        } else if (curBranch != null && curBranch != priorBranch && curOos == null) {
            val branch = tree.branches.firstOrNull { it.id == curBranch }
            val detail = buildString {
                append("[${label}] 当前进入分支: $curBranch")
                if (!branch?.nextPhase.isNullOrBlank()) append(" → ${branch?.nextPhase}")
                if (!branch?.condition.isNullOrBlank()) append("\n触发: ${branch?.condition}")
                if (!branch?.action.isNullOrBlank()) append("\n建议: ${branch?.action}")
            }
            notifier.fire(
                tree.leaderSymbol, tree.leaderName ?: tree.leaderSymbol,
                FinanceNotifier.Kind.THREAD_BRANCH_FLIP,
                eval.leaderPrice, eval.leaderChangePct, detail
            )
        }

        lastActiveBranchByLabel[label] = curBranch
        lastOosByLabel[label] = curOos
    }

    // ── widget builders ──────────────────────────────────────────────────────

    private fun buildHeader(
        tree: ThreadScenarioTree,
        currentPrice: Double?,
        changePct: Double?,
        oosKind: FinanceScenarioEvaluator.OutOfScopeKind?,
    ): JPanel {
        val snap = FinanceBridgeService.instance.snapshot()
        val pane = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                BorderFactory.createEmptyBorder(6, 8, 8, 8)
            )
        }

        // Thread title row
        val mainThread = snap.mainThread ?: tree.leaderName ?: tree.leaderSymbol
        val phase = snap.threadPhase ?: "?"
        val age = snap.threadAgeDays?.let { "D$it" } ?: "D?"
        val title = JBLabel("🧭 $mainThread  ·  $phase  ·  $age").apply {
            font = font.deriveFont(Font.BOLD, font.size + 1f)
        }
        pane.add(title)

        // Leader row
        val refStr = "¥${"%.2f".format(tree.leaderRefPrice)}" +
            (tree.refPriceDate?.let { " ($it)" } ?: "")
        val liveStr = if (currentPrice != null) {
            val arrow = if ((changePct ?: 0.0) >= 0) "+" else ""
            "实时 ¥${"%.2f".format(currentPrice)}  $arrow${"%.2f".format(changePct ?: 0.0)}%"
        } else "等待行情..."
        val leaderColor = priceColorFor(changePct, oosKind)
        val leaderText = "龙头：${tree.leaderName ?: tree.leaderSymbol} (${tree.leaderSymbol})    $refStr  →  $liveStr"
        val leaderLabel = JBLabel(leaderText).apply {
            foreground = leaderColor
        }
        pane.add(Box.createVerticalStrut(2))
        pane.add(leaderLabel)
        return pane
    }

    private fun priceColorFor(changePct: Double?, oos: FinanceScenarioEvaluator.OutOfScopeKind?): Color {
        if (oos != null) return ALERT_FG
        val pct = changePct ?: return JBColor.foreground()
        return when {
            pct > 0.5 -> UP_FG
            pct < -0.5 -> DOWN_FG
            else -> JBColor.foreground()
        }
    }

    private fun buildBranchList(
        tree: ThreadScenarioTree,
        statuses: List<FinanceScenarioEvaluator.BranchStatus>,
        activeId: String?,
    ): Component {
        val list = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        }
        if (statuses.isEmpty()) {
            // No live evaluation yet — show branches statically
            tree.branches.forEach { branch ->
                list.add(buildBranchRow(branch, status = null, active = false))
                list.add(Box.createVerticalStrut(4))
            }
        } else {
            statuses.forEach { s ->
                val active = activeId != null && s.branch.id == activeId
                list.add(buildBranchRow(s.branch, s, active))
                list.add(Box.createVerticalStrut(4))
            }
        }
        list.add(Box.createVerticalGlue())
        val scroll = JBScrollPane(list).apply {
            border = BorderFactory.createEmptyBorder()
            viewport.isOpaque = false
            isOpaque = false
            verticalScrollBar.unitIncrement = 16
        }
        return scroll
    }

    private fun buildBranchRow(
        branch: ScenarioBranch,
        status: FinanceScenarioEvaluator.BranchStatus?,
        active: Boolean,
    ): JPanel {
        val row = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = if (active) ACTIVE_BG else JBColor.background()
            isOpaque = active
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                    0, if (active) 3 else 1, 0, 0,
                    if (active) ACTIVE_BORDER else JBColor.border()
                ),
                BorderFactory.createEmptyBorder(6, 10, 6, 8)
            )
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }

        // Title: "A_扩散主升   P=25%   → 主升   ◀ 当前分支"
        val pct = (branch.confidence * 100).toInt()
        val nextPhase = branch.nextPhase?.let { "  →  $it" } ?: ""
        val activeBadge = if (active) "    ◀ 当前分支" else ""
        val titleText = "${branch.displayLabel}    P=${pct}%${nextPhase}${activeBadge}"
        val title = JBLabel(titleText).apply {
            font = font.deriveFont(Font.BOLD)
            if (active) foreground = ACTIVE_FG
        }
        row.add(title)

        // Condition line
        branch.condition?.takeIf { it.isNotBlank() }?.let { c ->
            row.add(Box.createVerticalStrut(2))
            row.add(JBLabel("触发: $c").apply {
                foreground = if (active) ACTIVE_FG else JBColor.foreground().darker()
            })
        }

        // Distance / progress bar line
        if (status != null) {
            row.add(Box.createVerticalStrut(2))
            row.add(JBLabel(buildDistanceLine(branch, status)).apply {
                font = monoFont()
                foreground = if (active) ACTIVE_FG else JBColor.foreground()
            })
        }

        // Action line
        branch.action?.takeIf { it.isNotBlank() }?.let { a ->
            row.add(Box.createVerticalStrut(2))
            row.add(JBLabel("<html>操作: <i>$a</i></html>").apply {
                foreground = if (active) ACTIVE_FG else JBColor.GRAY
            })
        }

        return row
    }

    private fun buildDistanceLine(branch: ScenarioBranch, s: FinanceScenarioEvaluator.BranchStatus): String {
        val anchor = s.displayAnchor
        val anchorStr = when (branch.priceTriggerType) {
            PriceTriggerType.RANGE -> {
                val l = branch.priceTriggerLow ?: 0.0
                val h = branch.priceTriggerHigh ?: 0.0
                "¥${"%.2f".format(l)}~¥${"%.2f".format(h)}"
            }
            else -> "¥${"%.2f".format(anchor)}"
        }
        val ratio = FinanceScenarioEvaluator.fillRatio(s, latestEval?.leaderPrice ?: anchor)
        val bar = buildBar(ratio)
        val distLabel = if (s.triggered) "命中" else "${"%+.1f%%".format(s.distancePct)}"
        return "${s.arrow} $anchorStr   $bar   $distLabel"
    }

    private fun buildBar(ratio: Double): String {
        val total = 16
        val filled = (ratio.coerceIn(0.0, 1.0) * total).toInt()
        val empty = total - filled
        return "█".repeat(filled) + "░".repeat(empty)
    }

    private fun buildOosBanner(eval: FinanceScenarioEvaluator.Evaluation): JPanel {
        val kind = eval.outOfScope!!
        val threshold = eval.breachedThreshold ?: 0.0
        val dir = if (kind == FinanceScenarioEvaluator.OutOfScopeKind.UP) "上沿" else "下沿"
        val curStr = "¥${"%.2f".format(eval.leaderPrice)}"
        val text = "🚨 走势超出${dir} ¥${"%.2f".format(threshold)}  (实时 $curStr, ${"%+.2f%%".format(eval.leaderChangePct)})  ——  agent 需重审主线"
        val pane = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            background = OOS_BG
            isOpaque = true
            border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
        }
        val l = JBLabel(text, SwingConstants.LEFT).apply {
            font = font.deriveFont(Font.BOLD)
            foreground = OOS_FG
        }
        pane.add(l, BorderLayout.CENTER)
        eval.tree.outOfScopeNote?.takeIf { it.isNotBlank() }?.let {
            val n = JBLabel(it, SwingConstants.LEFT).apply {
                foreground = OOS_FG
                font = font.deriveFont(font.size - 1f)
            }
            pane.add(n, BorderLayout.SOUTH)
        }
        return pane
    }

    private fun monoFont(): Font {
        val base = JBLabel().font
        return Font(Font.MONOSPACED, base.style, base.size)
    }

    companion object {
        private val ACTIVE_BG = JBColor(Color(0xFFF1B8), Color(0x4A3D14))
        private val ACTIVE_BORDER = JBColor(Color(0xCC9900), Color(0xE6B800))
        private val ACTIVE_FG = JBColor(Color(0x5C4400), Color(0xFFE082))
        private val UP_FG = JBColor(Color(0xC62828), Color(0xEF5350))
        private val DOWN_FG = JBColor(Color(0x2E7D32), Color(0x66BB6A))
        private val OOS_BG = JBColor(Color(0xFFCDD2), Color(0x5A1A1F))
        private val OOS_FG = JBColor(Color(0x8B0000), Color(0xFFA8A8))
        private val ALERT_FG = JBColor(Color(0xC62828), Color(0xEF5350))
    }
}
