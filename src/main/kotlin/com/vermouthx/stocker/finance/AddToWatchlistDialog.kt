package com.vermouthx.stocker.finance

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Dialog used by "Add to Claude watchlist" action.
 *
 * Pre-populates symbol/name from the table row; the user fills in thesis/trigger/
 * target/invalidation. Submitting calls [FinanceWatchlistWriter.upsert] and then
 * triggers a state reload so all panels pick up the change immediately.
 */
internal class AddToWatchlistDialog(
    project: Project?,
    private val initialSymbol: String,
    private val initialName: String,
    private val initialRefPrice: Double?,
) : DialogWrapper(project) {

    private val symbolField = JBTextField(initialSymbol)
    private val nameField = JBTextField(initialName)
    private val sectorField = JBTextField()
    private val thesisField = JBTextArea(3, 40)
    private val triggerField = JBTextArea(2, 40)
    private val targetLowField = JBTextField()
    private val targetHighField = JBTextField()
    private val invalidationField = JBTextArea(2, 40)
    private val refPriceField = JBTextField(initialRefPrice?.toString() ?: "")

    init {
        title = "Add to Claude watchlist"
        setOKButtonText("写入 watchlist.json")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel()
        root.layout = BoxLayout(root, BoxLayout.Y_AXIS)
        root.border = JBUI.Borders.empty(8)

        root.add(row("代码 (symbol)", symbolField, "例如 688981.SH"))
        root.add(row("名称", nameField))
        root.add(row("行业", sectorField, "例如 半导体制造"))
        root.add(textAreaRow("Thesis (买点逻辑)", thesisField))
        root.add(textAreaRow("Trigger (触发条件)", triggerField))
        root.add(row("Target 下限", targetLowField, "数字，如 150"))
        root.add(row("Target 上限", targetHighField, "数字，如 180"))
        root.add(textAreaRow("Invalidation (证伪)", invalidationField, hint = "例：跌破 110 关键支撑"))
        root.add(row("Ref price", refPriceField, "当前价（可选）"))

        return JScrollPane(root)
    }

    fun toEntry(): FinanceWatchlistWriter.NewEntry {
        return FinanceWatchlistWriter.NewEntry(
            symbol = symbolField.text.trim(),
            name = nameField.text.trim(),
            sector = sectorField.text.trim().takeIf { it.isNotEmpty() },
            thesis = thesisField.text.trim().takeIf { it.isNotEmpty() },
            trigger = triggerField.text.trim().takeIf { it.isNotEmpty() },
            targetLow = targetLowField.text.trim().toDoubleOrNull(),
            targetHigh = targetHighField.text.trim().toDoubleOrNull(),
            invalidation = invalidationField.text.trim().takeIf { it.isNotEmpty() },
            refPrice = refPriceField.text.trim().toDoubleOrNull(),
        )
    }

    private fun row(label: String, field: JComponent, hint: String? = null): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)
        panel.border = JBUI.Borders.emptyBottom(6)
        val lbl = JBLabel(label).apply { preferredSize = java.awt.Dimension(120, preferredSize.height) }
        panel.add(lbl)
        panel.add(field)
        if (hint != null) {
            panel.add(JBLabel("  $hint").apply { foreground = java.awt.Color.GRAY })
        }
        return panel
    }

    private fun textAreaRow(label: String, area: JBTextArea, hint: String? = null): JComponent {
        area.lineWrap = true
        area.wrapStyleWord = true
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.emptyBottom(6)
        panel.add(JBLabel(label + (hint?.let { "  · $it" } ?: "")))
        panel.add(JScrollPane(area))
        return panel
    }
}
