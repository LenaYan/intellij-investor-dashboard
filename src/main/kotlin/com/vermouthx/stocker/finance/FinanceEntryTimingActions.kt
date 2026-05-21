package com.vermouthx.stocker.finance

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Right-click "View entry-timing buy plan" — pops up a non-modal dialog showing
 * the recommendation for the clicked symbol if it appears in today's entry-timing.md.
 *
 * If the symbol is absent from the report, shows an informational dialog instead.
 */
object FinanceEntryTimingActions {

    @JvmStatic
    fun showPopup(parent: Component, symbol: String, displayName: String?) {
        val rec = FinanceBridgeService.instance.snapshot()
            .entryTimingBySymbol[FinanceSymbol.normalize(symbol)]
        if (rec == null) {
            Messages.showInfoMessage(
                parent,
                "${displayName ?: symbol} (${symbol}) 未出现在今日 entry-timing.md。\n\n" +
                    "在 Claude 终端运行 /entry 后，符合条件的标的会被纳入 ranker → entry-timing 链路。",
                "Entry-Timing 买点计划"
            )
            return
        }
        EntryTimingPlanDialog(rec, displayName ?: rec.name ?: symbol).show()
    }
}

private class EntryTimingPlanDialog(
    private val rec: EntryTimingRecommendation,
    private val displayName: String,
) : DialogWrapper(null, true) {

    init {
        title = "Entry-Timing 买点  ·  $displayName (${rec.symbol})"
        setOKButtonText("关闭")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))
        panel.preferredSize = Dimension(560, 520)
        panel.border = BorderFactory.createEmptyBorder(12, 16, 12, 16)

        val header = JBLabel(headerHtml())
        header.border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
        panel.add(header, BorderLayout.NORTH)

        val body = JTextArea(bodyText()).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            margin = java.awt.Insets(8, 8, 8, 8)
            background = JBColor.background()
        }
        panel.add(JBScrollPane(body), BorderLayout.CENTER)
        return panel
    }

    override fun createActions() = arrayOf(okAction)

    private fun headerHtml(): String {
        val grade = rec.gradeGlyph ?: rec.grade ?: "?"
        val type = rec.entryType ?: "?"
        val score = rec.totalScore?.let { " · 总分 $it" } ?: ""
        val resonance = rec.resonanceScore?.let { " · 共振 $it/10" } ?: ""
        val pos = rec.positionScore?.let { " · 位置 $it/100" } ?: ""
        val event = rec.eventState?.let { " · $it" } ?: ""
        val thread = rec.alignedThread?.let { "<br><b>主线</b>: $it (${rec.threadPhase ?: "?"})" } ?: ""
        return """
            <html><div style='font-size:11pt; padding:4px 0;'>
              <b>$grade · $type</b>$score$resonance$pos$event
              $thread
            </div></html>
        """.trimIndent()
    }

    private fun bodyText(): String = buildString {
        appendLine("==== 仓位计划 ====")
        appendLine("首仓比例 : ${rec.firstPositionPct?.let { "$it%" } ?: "—"}")
        appendLine("加仓节奏 : ${rec.addSchedule ?: "—"}")
        appendLine()
        appendLine("==== 触发条件 ====")
        if (rec.triggers.isEmpty()) appendLine("（无）") else rec.triggers.forEach { appendLine("• $it") }
        rec.triggerPrice?.let {
            appendLine()
            appendLine("→ 解析触发价（首数字）: ¥${"%.2f".format(it)}")
            appendLine("→ 盘中价格进入 ±1.5% 区间会推 ENTRY_TRIGGER 通知")
        }
        appendLine()
        appendLine("==== 失效条件 ====")
        if (rec.invalidations.isEmpty()) appendLine("（无）") else rec.invalidations.forEach { appendLine("• $it") }
        rec.invalidationPrice?.let {
            appendLine()
            appendLine("→ 解析失效价（首数字）: ¥${"%.2f".format(it)}")
            appendLine("→ 盘中跌破会推 ENTRY_INVALIDATION 通知（建议立即终止建仓）")
        }
        if (rec.grade?.trim() == "C" || rec.grade?.trim() == "不买") {
            appendLine()
            appendLine("==== 提示 ====")
            appendLine("⛔ 当前 grade 为 ${rec.grade}，entry-timing 不建议建仓。盘中通知已禁用。")
        }
    }
}
