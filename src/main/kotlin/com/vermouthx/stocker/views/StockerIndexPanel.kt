package com.vermouthx.stocker.views

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.vermouthx.stocker.entities.StockerQuote
import com.vermouthx.stocker.settings.StockerSetting
import com.vermouthx.stocker.utils.StockerPinyinUtil
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Market-index strip below the quote table: index selector combobox plus
 * value / change / percent labels, extracted from StockerTableView. All methods
 * must be called on the EDT.
 */
internal class StockerIndexPanel(private val colors: StockerQuoteColors) {

    private val cbIndex = ComboBox<String>()
    private val lbIndexValue = JBLabel("", SwingConstants.CENTER)
    private val lbIndexExtent = JBLabel("", SwingConstants.CENTER)
    private val lbIndexPercent = JBLabel("", SwingConstants.CENTER)
    private var indices: List<StockerQuote> = ArrayList()

    val component: JPanel = JPanel(GridLayout(1, 4, 8, 0)).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
            BorderFactory.createEmptyBorder(8, 12, 8, 12),
        )
        val indexFont = lbIndexValue.font.deriveFont(Font.BOLD, lbIndexValue.font.size + 1f)
        lbIndexValue.font = indexFont
        lbIndexExtent.font = indexFont
        lbIndexPercent.font = indexFont
        add(cbIndex)
        add(lbIndexValue)
        add(lbIndexExtent)
        add(lbIndexPercent)
        cbIndex.addItemListener { refreshIndexDisplay() }
    }

    /** Replace the index list, preserving the user's selection where possible. EDT only. */
    fun applyIndices(indices: List<StockerQuote>) {
        this.indices = indices
        val setting = StockerSetting.instance

        var shouldRefresh = cbIndex.itemCount != indices.size
        if (!shouldRefresh) {
            for (i in indices.indices) {
                val displayName = setting.getDisplayName(indices[i].code, indices[i].name)
                if (displayName != cbIndex.getItemAt(i)) {
                    shouldRefresh = true
                    break
                }
            }
        }

        if (shouldRefresh && indices.isNotEmpty()) {
            val selectedDisplayName = cbIndex.selectedItem?.toString()
            val selectedCode = findIndexCodeByDisplayName(selectedDisplayName, setting)
            cbIndex.removeAllItems()
            indices.forEach { cbIndex.addItem(setting.getDisplayName(it.code, it.name)) }
            if (selectedCode != null) {
                for (i in indices.indices) {
                    if (indices[i].code == selectedCode) {
                        cbIndex.selectedIndex = i
                        break
                    }
                }
            } else if (indices.isNotEmpty()) {
                cbIndex.selectedIndex = 0
            }
        }
        refreshIndexDisplay()
    }

    /** Re-render the selected index labels (also picks up color-pattern changes). */
    fun refreshIndexDisplay() {
        if (cbIndex.selectedIndex == -1 || cbIndex.selectedItem == null) return
        val selectedDisplayName = cbIndex.selectedItem.toString()
        val setting = StockerSetting.instance
        val selectedCode = findIndexCodeByDisplayName(selectedDisplayName, setting)

        for (index in indices) {
            val displayName = setting.getDisplayName(index.code, index.name)
            val isSelected = if (selectedCode != null) index.code == selectedCode else displayName == selectedDisplayName
            if (!isSelected) continue
            lbIndexValue.text = index.current.toString()
            lbIndexExtent.text = index.change.toString()
            lbIndexPercent.text = "${index.percentage}%"
            val color = colors.signColor(index.percentage, JBColor.foreground())
            lbIndexValue.foreground = color
            lbIndexExtent.foreground = color
            lbIndexPercent.foreground = color
            return
        }
    }

    fun dispose() {
        (indices as? MutableList<*>)?.clear()
        indices = emptyList()
    }

    private fun findIndexCodeByDisplayName(displayName: String?, setting: StockerSetting): String? {
        if (displayName.isNullOrEmpty()) return null
        for (index in indices) {
            val code = index.code
            val customName = setting.getCustomName(code)
            if (customName != null && customName == displayName) return code
            val originalName = index.name
            if (displayName == originalName) return code
            if (displayName == StockerPinyinUtil.toPinyin(originalName)) return code
        }
        return null
    }
}
