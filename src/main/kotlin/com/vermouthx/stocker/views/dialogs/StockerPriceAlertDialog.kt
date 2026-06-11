package com.vermouthx.stocker.views.dialogs

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import com.vermouthx.stocker.StockerBundle
import com.vermouthx.stocker.settings.StockerSetting
import javax.swing.JComponent

/**
 * One-shot price-alert thresholds for a single symbol. Empty field = no alert in
 * that direction; pre-filled with the currently stored thresholds. Alerts clear
 * themselves after firing (see StockerApp.checkPriceAlerts).
 */
class StockerPriceAlertDialog(code: String, name: String?) : DialogWrapper(true) {

    private val aboveField = JBTextField(
        StockerSetting.instance.getAlertAbove(code)?.toString() ?: "", 12
    )
    private val belowField = JBTextField(
        StockerSetting.instance.getAlertBelow(code)?.toString() ?: "", 12
    )

    val aboveValue: Double?
        get() = aboveField.text.trim().toDoubleOrNull()

    val belowValue: Double?
        get() = belowField.text.trim().toDoubleOrNull()

    init {
        title = StockerBundle.message("alert.dialog.title", name ?: code)
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(StockerBundle.message("alert.dialog.above")) {
            cell(aboveField)
        }
        row(StockerBundle.message("alert.dialog.below")) {
            cell(belowField)
        }
        row {
            comment(StockerBundle.message("alert.dialog.comment"))
        }
    }

    override fun doValidate(): ValidationInfo? {
        for (field in listOf(aboveField, belowField)) {
            val text = field.text.trim()
            if (text.isNotEmpty() && text.toDoubleOrNull() == null) {
                return ValidationInfo(StockerBundle.message("alert.dialog.invalid.number"), field)
            }
        }
        return null
    }

    override fun getPreferredFocusedComponent(): JComponent = aboveField
}
