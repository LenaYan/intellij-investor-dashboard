package com.vermouthx.stocker.views.windows

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.*
import com.vermouthx.stocker.StockerAppManager
import com.vermouthx.stocker.StockerBundle
import com.vermouthx.stocker.enums.StockerQuoteColorPattern
import com.vermouthx.stocker.enums.StockerQuoteProvider
import com.vermouthx.stocker.enums.StockerTableColumn
import com.vermouthx.stocker.settings.StockerSetting
import com.vermouthx.stocker.views.StockerTableView
import javax.swing.JCheckBox
import javax.swing.JLabel

class StockerSettingWindow : BoundConfigurable(StockerBundle.message("plugin.name")) {

    private val setting = StockerSetting.instance

    private var colorPattern: StockerQuoteColorPattern = setting.quoteColorPattern
    private var selectedProvider: StockerQuoteProvider = setting.quoteProvider
    private var selectedCryptoProvider: StockerQuoteProvider = setting.cryptoQuoteProvider
    private var displayNameWithPinyin: Boolean = setting.displayNameWithPinyin
    private var languageOverride: String = setting.languageOverride

    /**
     * Per-column visibility state. Driven by [StockerTableColumn.entries] so any new
     * enum value automatically appears in the settings panel — no need to register
     * a checkbox field by hand. Insertion order = enum order, preserved via LinkedHashMap.
     */
    private val columnVisibility: MutableMap<StockerTableColumn, Boolean> =
        linkedMapOf<StockerTableColumn, Boolean>().apply {
            StockerTableColumn.entries.forEach { col -> this[col] = setting.isTableColumnVisible(col) }
        }

    /** Lookup of constructed JCheckBox by column — populated during panel build, used by [handleColumnToggle]. */
    private val columnCheckBoxes: MutableMap<StockerTableColumn, JCheckBox> = linkedMapOf()
    private var columnWarningLabel: JLabel? = null

    // Finance bridge settings (mirrored on apply)
    private var financeBridgeEnabled: Boolean = setting.financeBridgeEnabled
    private var financeBaseDir: String = setting.financeBaseDir
    private var financeNotifyTriggers: Boolean = setting.financeNotifyTriggers
    private var financeNotifyEntryTiming: Boolean = setting.financeNotifyEntryTiming
    private var financeShowEntryTimingTab: Boolean = setting.financeShowEntryTimingTab
    private var financeShowCalibrationTab: Boolean = setting.financeShowCalibrationTab
    private var financeHighlightThreadChange: Boolean = setting.financeHighlightThreadChange

    companion object {
        private val LANGUAGE_CODES = listOf("", "en", "zh_CN")

        private fun languageDisplayName(code: String): String = when (code) {
            "" -> StockerBundle.message("settings.language.system")
            "en" -> StockerBundle.message("settings.language.english")
            "zh_CN" -> StockerBundle.message("settings.language.chinese")
            else -> code
        }
    }

    override fun createPanel(): DialogPanel {
        val providerRenderer = SimpleListCellRenderer.create<StockerQuoteProvider>("") { it.title }
        val languageRenderer = SimpleListCellRenderer.create<String>("") { languageDisplayName(it) }

        // Reset the checkbox lookup on each panel rebuild so we don't keep references
        // to stale Swing components from a previously disposed panel.
        columnCheckBoxes.clear()

        return panel {
            group(StockerBundle.message("settings.group.general")) {
                row {
                    label(StockerBundle.message("settings.language"))
                        .widthGroup("labels")
                    comboBox(LANGUAGE_CODES, languageRenderer)
                        .bindItem(
                            { languageOverride },
                            { languageOverride = it ?: "" }
                        )
                        .widthGroup("comboboxes")
                        .comment(StockerBundle.message("settings.language.comment"))
                }.layout(RowLayout.LABEL_ALIGNED)
            }

            group(StockerBundle.message("settings.group.data.provider")) {
                row {
                    label(StockerBundle.message("settings.stock.quote.source"))
                        .widthGroup("labels")
                    comboBox(StockerQuoteProvider.entries.toList(), providerRenderer)
                        .bindItem(::selectedProvider.toNullableProperty())
                        .widthGroup("comboboxes")
                        .comment(StockerBundle.message("settings.stock.quote.source.comment"))
                }.layout(RowLayout.LABEL_ALIGNED)

                row {
                    label(StockerBundle.message("settings.crypto.quote.source"))
                        .widthGroup("labels")
                    comboBox(listOf(StockerQuoteProvider.SINA), providerRenderer)
                        .bindItem(::selectedCryptoProvider.toNullableProperty())
                        .widthGroup("comboboxes")
                        .comment(StockerBundle.message("settings.crypto.quote.source.comment"))
                }.layout(RowLayout.LABEL_ALIGNED)
            }

            group(StockerBundle.message("settings.group.table.display")) {
                buttonsGroup {
                    row {
                        label(StockerBundle.message("settings.color.pattern"))
                            .widthGroup("labels")
                    }
                    indent {
                        row {
                            radioButton(StockerBundle.message("settings.color.pattern.red.up"), StockerQuoteColorPattern.RED_UP_GREEN_DOWN)
                                .comment(StockerBundle.message("settings.color.pattern.red.up.comment"))
                        }
                        row {
                            radioButton(StockerBundle.message("settings.color.pattern.green.up"), StockerQuoteColorPattern.GREEN_UP_RED_DOWN)
                                .comment(StockerBundle.message("settings.color.pattern.green.up.comment"))
                        }
                        row {
                            radioButton(StockerBundle.message("settings.color.pattern.none"), StockerQuoteColorPattern.NONE)
                                .comment(StockerBundle.message("settings.color.pattern.none.comment"))
                        }
                    }
                }.bind(::colorPattern.toMutableProperty(), StockerQuoteColorPattern::class.java)

                row {
                    label(StockerBundle.message("settings.name.format"))
                        .widthGroup("labels")
                }.layout(RowLayout.LABEL_ALIGNED)

                indent {
                    row {
                        checkBox(StockerBundle.message("settings.name.format.pinyin"))
                            .bindSelected(::displayNameWithPinyin.toMutableProperty())
                    }.rowComment(StockerBundle.message("settings.name.format.pinyin.comment"))
                }

                row {
                    label(StockerBundle.message("settings.table.columns"))
                        .widthGroup("labels")
                }.layout(RowLayout.LABEL_ALIGNED)

                indent {
                    // One checkbox per enum value, in declaration order.
                    // Adding a new column to StockerTableColumn automatically adds a
                    // checkbox here — no manual registration required.
                    StockerTableColumn.entries.forEach { col ->
                        row {
                            val cb = checkBox(col.title)
                                .bindSelected(
                                    { columnVisibility[col] ?: false },
                                    { columnVisibility[col] = it }
                                )
                                .applyToComponent {
                                    addItemListener { handleColumnToggle(this) }
                                }
                                .component
                            columnCheckBoxes[col] = cb
                        }
                    }
                    row {
                        columnWarningLabel = label(StockerBundle.message("settings.table.columns.warning"))
                            .applyToComponent {
                                foreground = JBColor.RED
                                isVisible = false
                            }
                            .component
                    }
                }
            }

            group(StockerBundle.message("settings.group.finance")) {
                row {
                    checkBox(StockerBundle.message("settings.finance.bridge.enabled"))
                        .bindSelected(::financeBridgeEnabled.toMutableProperty())
                        .comment(StockerBundle.message("settings.finance.bridge.enabled.comment"))
                }

                row {
                    label(StockerBundle.message("settings.finance.base.dir")).widthGroup("labels")
                    textField()
                        .bindText(::financeBaseDir.toMutableProperty())
                        .widthGroup("comboboxes")
                        .comment(StockerBundle.message("settings.finance.base.dir.comment"))
                }.layout(RowLayout.LABEL_ALIGNED)

                row {
                    checkBox(StockerBundle.message("settings.finance.notify.triggers"))
                        .bindSelected(::financeNotifyTriggers.toMutableProperty())
                }
                row {
                    checkBox(StockerBundle.message("settings.finance.notify.entry.timing"))
                        .bindSelected(::financeNotifyEntryTiming.toMutableProperty())
                        .comment(StockerBundle.message("settings.finance.notify.entry.timing.comment"))
                }
                row {
                    checkBox(StockerBundle.message("settings.finance.tab.entry.timing"))
                        .bindSelected(::financeShowEntryTimingTab.toMutableProperty())
                }
                row {
                    checkBox(StockerBundle.message("settings.finance.tab.calibration"))
                        .bindSelected(::financeShowCalibrationTab.toMutableProperty())
                }
                row {
                    checkBox(StockerBundle.message("settings.finance.highlight.thread.change"))
                        .bindSelected(::financeHighlightThreadChange.toMutableProperty())
                        .comment(StockerBundle.message("settings.finance.highlight.thread.change.comment"))
                }
            }

            onApply {
                val visibleColumns = buildVisibleColumns()
                val columnsModified = visibleColumns != setting.visibleTableColumns
                val colorPatternModified = colorPattern != setting.quoteColorPattern
                val providerModified = selectedProvider != setting.quoteProvider
                val cryptoProviderModified = selectedCryptoProvider != setting.cryptoQuoteProvider
                val pinyinModified = displayNameWithPinyin != setting.displayNameWithPinyin
                val languageModified = languageOverride != setting.languageOverride
                val financeDirChanged = financeBaseDir != setting.financeBaseDir
                val financeEnabledChanged = financeBridgeEnabled != setting.financeBridgeEnabled

                setting.quoteProvider = selectedProvider
                setting.cryptoQuoteProvider = selectedCryptoProvider
                setting.quoteColorPattern = colorPattern
                setting.displayNameWithPinyin = displayNameWithPinyin
                setting.visibleTableColumns = visibleColumns
                setting.languageOverride = languageOverride

                setting.financeBridgeEnabled = financeBridgeEnabled
                setting.financeBaseDir = financeBaseDir
                setting.financeNotifyTriggers = financeNotifyTriggers
                setting.financeNotifyEntryTiming = financeNotifyEntryTiming
                setting.financeShowEntryTimingTab = financeShowEntryTimingTab
                setting.financeShowCalibrationTab = financeShowCalibrationTab
                setting.financeHighlightThreadChange = financeHighlightThreadChange

                if (columnsModified || languageModified) {
                    StockerTableView.refreshAllColumnVisibility()
                }
                if (colorPatternModified) {
                    StockerTableView.refreshAllColorPatterns()
                }
                if (providerModified || cryptoProviderModified || pinyinModified || languageModified) {
                    refreshAllWindows()
                }
                if (financeDirChanged || financeEnabledChanged) {
                    com.vermouthx.stocker.finance.FinanceBridgeService.instance.reloadNow()
                }
            }
            onIsModified {
                selectedProvider != setting.quoteProvider ||
                        selectedCryptoProvider != setting.cryptoQuoteProvider ||
                        colorPattern != setting.quoteColorPattern ||
                        displayNameWithPinyin != setting.displayNameWithPinyin ||
                        languageOverride != setting.languageOverride ||
                        buildVisibleColumns() != setting.visibleTableColumns ||
                        financeBridgeEnabled != setting.financeBridgeEnabled ||
                        financeBaseDir != setting.financeBaseDir ||
                        financeNotifyTriggers != setting.financeNotifyTriggers ||
                        financeNotifyEntryTiming != setting.financeNotifyEntryTiming ||
                        financeShowEntryTimingTab != setting.financeShowEntryTimingTab ||
                        financeShowCalibrationTab != setting.financeShowCalibrationTab ||
                        financeHighlightThreadChange != setting.financeHighlightThreadChange
            }
            onReset {
                selectedProvider = setting.quoteProvider
                selectedCryptoProvider = setting.cryptoQuoteProvider
                colorPattern = setting.quoteColorPattern
                displayNameWithPinyin = setting.displayNameWithPinyin
                languageOverride = setting.languageOverride
                StockerTableColumn.entries.forEach { col ->
                    columnVisibility[col] = setting.isTableColumnVisible(col)
                }
                financeBridgeEnabled = setting.financeBridgeEnabled
                financeBaseDir = setting.financeBaseDir
                financeNotifyTriggers = setting.financeNotifyTriggers
                financeNotifyEntryTiming = setting.financeNotifyEntryTiming
                financeShowEntryTimingTab = setting.financeShowEntryTimingTab
                financeShowCalibrationTab = setting.financeShowCalibrationTab
                financeHighlightThreadChange = setting.financeHighlightThreadChange
                columnWarningLabel?.isVisible = false
            }
        }
    }

    private fun buildVisibleColumns(): MutableList<String> =
        StockerTableColumn.entries
            .filter { columnVisibility[it] == true }
            .map { it.name }
            .toMutableList()

    /**
     * Enforces "at least one column visible". If the user is about to uncheck the last
     * remaining column, we force it back on and show a warning label.
     */
    private fun handleColumnToggle(changed: JCheckBox) {
        val selectedCount = columnCheckBoxes.values.count { it.isSelected }
        if (selectedCount == 0) {
            changed.isSelected = true
            columnWarningLabel?.isVisible = true
        } else {
            columnWarningLabel?.isVisible = false
        }
    }

    private fun refreshAllWindows() {
        StockerAppManager.getAllApplications().forEach { app ->
            app.shutdownThenClear()
            app.schedule()
        }
    }

}
