package com.vermouthx.stocker.views.dialogs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.vermouthx.stocker.StockerAppManager
import com.vermouthx.stocker.entities.StockerSuggestion
import com.vermouthx.stocker.enums.StockerMarketType
import com.vermouthx.stocker.settings.StockerSetting
import com.vermouthx.stocker.utils.StockerActionUtil
import java.awt.Dimension
import javax.swing.JTextArea

class StockerBatchAddDialog(val project: Project?) : DialogWrapper(project) {

    private val log = Logger.getInstance(StockerBatchAddDialog::class.java)
    private val setting = StockerSetting.instance

    private val marketComboBox = ComboBox(arrayOf("CN (A-Share)", "HK", "US", "Crypto"))
    private val codesTextArea = JTextArea(8, 40)

    init {
        title = "Batch Add Stocks"
        init()
    }

    override fun createCenterPanel(): DialogPanel {
        codesTextArea.lineWrap = true
        codesTextArea.wrapStyleWord = true
        codesTextArea.toolTipText = "Enter stock codes separated by spaces or commas"

        val dialogPanel = panel {
            row {
                label("Market:")
                cell(marketComboBox)
            }.layout(RowLayout.LABEL_ALIGNED)
            row {
                label("Codes:")
                scrollCell(codesTextArea)
                    .align(AlignX.FILL)
                    .comment("Enter stock codes separated by spaces or commas.<br>Example: 600519 000001 601398")
            }.layout(RowLayout.LABEL_ALIGNED)
        }
        dialogPanel.preferredSize = Dimension(500, 300)
        return dialogPanel
    }

    override fun doOKAction() {
        val marketType = when (marketComboBox.selectedIndex) {
            0 -> StockerMarketType.AShare
            1 -> StockerMarketType.HKStocks
            2 -> StockerMarketType.USStocks
            3 -> StockerMarketType.Crypto
            else -> StockerMarketType.AShare
        }

        val input = codesTextArea.text.trim()
        if (input.isEmpty()) {
            Messages.showWarningDialog(project, "Please enter at least one stock code.", "No Input")
            return
        }

        // Split by comma, space, tab, or newline
        val codes = input.split("[,\\s]+".toRegex())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (codes.isEmpty()) {
            Messages.showWarningDialog(project, "No valid stock codes found.", "No Input")
            return
        }

        val myApplication = StockerAppManager.myApplication(project)
        myApplication?.shutdownThenClear()

        val addedCodes = mutableListOf<String>()
        val failedCodes = mutableListOf<String>()
        val duplicateCodes = mutableListOf<String>()

        for (code in codes) {
            if (setting.containsCode(code)) {
                duplicateCodes.add(code)
                continue
            }
            val suggestion = StockerSuggestion(code, code, marketType)
            val success = StockerActionUtil.addStock(marketType, suggestion, project)
            if (success) {
                addedCodes.add(code)
            } else {
                failedCodes.add(code)
            }
        }

        myApplication?.schedule()

        // Show result summary
        val sb = StringBuilder()
        if (addedCodes.isNotEmpty()) {
            sb.append("Successfully added ${addedCodes.size} code(s): ${addedCodes.joinToString(", ")}\n")
        }
        if (duplicateCodes.isNotEmpty()) {
            sb.append("Skipped ${duplicateCodes.size} duplicate(s): ${duplicateCodes.joinToString(", ")}\n")
        }
        if (failedCodes.isNotEmpty()) {
            sb.append("Failed to add ${failedCodes.size} code(s): ${failedCodes.joinToString(", ")}\n")
        }

        if (failedCodes.isNotEmpty()) {
            Messages.showWarningDialog(project, sb.toString().trim(), "Batch Add Result")
        } else if (addedCodes.isNotEmpty()) {
            Messages.showInfoMessage(project, sb.toString().trim(), "Batch Add Result")
        }

        super.doOKAction()
    }
}
