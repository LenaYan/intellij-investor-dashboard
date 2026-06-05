package com.vermouthx.stocker.views.dialogs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.vermouthx.stocker.StockerAppManager
import com.vermouthx.stocker.entities.StockerQuote
import com.vermouthx.stocker.enums.StockerMarketType
import com.vermouthx.stocker.settings.StockerSetting
import com.vermouthx.stocker.utils.StockerNumberFormat
import com.vermouthx.stocker.utils.StockerPinyinUtil
import com.vermouthx.stocker.utils.StockerQuoteHttpUtil
import com.vermouthx.stocker.views.StockerTableView
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.util.concurrent.CompletableFuture
import javax.swing.*

class StockerManagementDialog(val project: Project?) : DialogWrapper(project) {

    private val log = Logger.getInstance(StockerManagementDialog::class.java)
    private val setting = StockerSetting.instance

    private val tabMap: MutableMap<StockerMarketType, JPanel> = mutableMapOf()

    private val currentSymbols: MutableMap<StockerMarketType, DefaultListModel<StockerQuote>> = mutableMapOf()

    private var currentMarketSelection: StockerMarketType = StockerMarketType.AShare

    init {
        title = "Manage Favorite Stocks"
        init()
    }

    override fun createCenterPanel(): DialogPanel {
        val tabbedPane = JBTabbedPane()
        tabbedPane.add("CN", createTabContent(StockerMarketType.AShare))
        tabbedPane.add("HK", createTabContent(StockerMarketType.HKStocks))
        tabbedPane.add("US", createTabContent(StockerMarketType.USStocks))
        tabbedPane.add("Crypto", createTabContent(StockerMarketType.Crypto))
        tabbedPane.add("Futures", createTabContent(StockerMarketType.Futures))

        tabbedPane.addChangeListener {
            currentMarketSelection = when (tabbedPane.selectedIndex) {
                0 -> StockerMarketType.AShare
                1 -> StockerMarketType.HKStocks
                2 -> StockerMarketType.USStocks
                3 -> StockerMarketType.Crypto
                4 -> StockerMarketType.Futures
                else -> return@addChangeListener
            }
        }

        // Load data asynchronously for each market type
        loadMarketData(StockerMarketType.AShare, setting.aShareList)
        loadMarketData(StockerMarketType.HKStocks, setting.hkStocksList)
        loadMarketData(StockerMarketType.USStocks, setting.usStocksList)
        loadMarketData(StockerMarketType.Crypto, setting.cryptoList)
        loadMarketData(StockerMarketType.Futures, setting.futuresList)

        tabbedPane.selectedIndex = 0
        return panel {
            row {
                cell(tabbedPane).align(AlignX.FILL)
            }
        }.withPreferredWidth(600).withPreferredHeight(400)
    }
    
    private fun loadMarketData(marketType: StockerMarketType, codes: List<String>) {
        val listModel = DefaultListModel<StockerQuote>()
        currentSymbols[marketType] = listModel
        
        // Show loading state
        tabMap[marketType]?.let { pane ->
            showLoadingState(pane)
        }
        
        CompletableFuture.supplyAsync {
            try {
                // Use cryptoQuoteProvider for crypto, quoteProvider for stocks, SINA for futures
                val provider = when (marketType) {
                    StockerMarketType.Crypto -> setting.cryptoQuoteProvider
                    StockerMarketType.Futures -> com.vermouthx.stocker.enums.StockerQuoteProvider.SINA
                    else -> setting.quoteProvider
                }
                StockerQuoteHttpUtil.get(marketType, provider, codes)
            } catch (e: Exception) {
                log.warn("Failed to load quotes for market type $marketType", e)
                emptyList()
            }
        }.thenAccept { quotes ->
            SwingUtilities.invokeLater {
                listModel.addAll(quotes)
                tabMap[marketType]?.let { pane ->
                    renderTabPane(pane, listModel)
                }
            }
        }
    }
    
    private fun showLoadingState(pane: JPanel) {
        pane.removeAll()
        pane.add(
            panel {
                row {
                    label("Loading...").align(AlignX.CENTER)
                }
            }, BorderLayout.CENTER
        )
        pane.revalidate()
        pane.repaint()
    }

    override fun createActions(): Array<Action> {
        return arrayOf(
            object : OkAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    val myApplication = StockerAppManager.myApplication(project)
                    if (myApplication != null) {
                        myApplication.shutdownThenClear()
                        currentSymbols[StockerMarketType.AShare]?.let { symbols ->
                            setting.aShareList = symbols.elements().asSequence().map { it.code }.toMutableList()
                        }
                        currentSymbols[StockerMarketType.HKStocks]?.let { symbols ->
                            setting.hkStocksList = symbols.elements().asSequence().map { it.code }.toMutableList()
                        }
                        currentSymbols[StockerMarketType.USStocks]?.let { symbols ->
                            setting.usStocksList = symbols.elements().asSequence().map { it.code }.toMutableList()
                        }
                        currentSymbols[StockerMarketType.Crypto]?.let { symbols ->
                            setting.cryptoList = symbols.elements().asSequence().map { it.code }.toMutableList()
                        }
                        currentSymbols[StockerMarketType.Futures]?.let { symbols ->
                            setting.futuresList = symbols.elements().asSequence().map { it.code }.toMutableList()
                        }
                        myApplication.schedule()
                    }
                    super.actionPerformed(e)
                }
            }, cancelAction
        )
    }

    private fun createTabContent(marketType: StockerMarketType): JComponent {
        val pane = JPanel(BorderLayout())
        tabMap[marketType] = pane
        return panel {
            row {
                cell(pane).align(AlignX.FILL).align(AlignY.FILL)
            }
        }
    }

    private fun renderTabPane(pane: JPanel, listModel: DefaultListModel<StockerQuote>) {
        // Clear existing components to prevent stacking
        pane.removeAll()
        
        val list = JBList(listModel)
        list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        list.installCellRenderer { symbol ->
            // Get original name with Pinyin if enabled
            val originalName = if (setting.displayNameWithPinyin) {
                StockerPinyinUtil.toPinyin(symbol.name)
            } else {
                symbol.name
            }
            
            // Get custom name if exists
            val customName = setting.getCustomName(symbol.code)
            val costPrice = setting.getCostPrice(symbol.code)
            val holdings = setting.getHoldings(symbol.code)
            
            panel {
                row {
                    label(symbol.code)
                        .applyToComponent { 
                            minimumSize = java.awt.Dimension(80, 0)
                            preferredSize = java.awt.Dimension(80, preferredSize.height)
                        }
                    label(
                        if (originalName.length <= 25) {
                            originalName
                        } else {
                            "${originalName.substring(0, 25)}..."
                        }
                    ).applyToComponent {
                        minimumSize = java.awt.Dimension(150, 0)
                        preferredSize = java.awt.Dimension(150, preferredSize.height)
                    }
                    label(
                        customName?.let {
                            if (it.length <= 15) {
                                it
                            } else {
                                "${it.substring(0, 15)}..."
                            }
                        } ?: "-"
                    ).applyToComponent {
                        minimumSize = java.awt.Dimension(120, 0)
                        preferredSize = java.awt.Dimension(120, preferredSize.height)
                    }
                    label(
                        StockerNumberFormat.formatPrice(costPrice)
                    ).applyToComponent {
                        minimumSize = java.awt.Dimension(80, 0)
                        preferredSize = java.awt.Dimension(80, preferredSize.height)
                    }
                    label(
                        holdings?.toString() ?: "-"
                    ).applyToComponent {
                        minimumSize = java.awt.Dimension(80, 0)
                        preferredSize = java.awt.Dimension(80, preferredSize.height)
                    }
                }
            }.withBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8))
        }
        
        // Create header panel
        val headerPanel = panel {
            row {
                label("Code").bold()
                    .applyToComponent {
                        minimumSize = java.awt.Dimension(80, 0)
                        preferredSize = java.awt.Dimension(80, preferredSize.height)
                    }
                label("Original Name").bold()
                    .applyToComponent {
                        minimumSize = java.awt.Dimension(150, 0)
                        preferredSize = java.awt.Dimension(150, preferredSize.height)
                    }
                label("Custom Name").bold()
                    .applyToComponent {
                        minimumSize = java.awt.Dimension(120, 0)
                        preferredSize = java.awt.Dimension(120, preferredSize.height)
                    }
                label("Cost").bold()
                    .applyToComponent {
                        minimumSize = java.awt.Dimension(80, 0)
                        preferredSize = java.awt.Dimension(80, preferredSize.height)
                    }
                label("Holdings").bold()
                    .applyToComponent {
                        minimumSize = java.awt.Dimension(80, 0)
                        preferredSize = java.awt.Dimension(80, preferredSize.height)
                    }
            }
        }.withBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, javax.swing.UIManager.getColor("Separator.foreground")),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)
        ))
        
        // ToolbarDecorator with remove action for batch delete (supports multi-select).
        // No confirmation prompt — single/multi-select delete commits immediately; only
        // "Delete All" (below) keeps a confirmation since it's an unscoped wipe.
        val decorator = ToolbarDecorator.createDecorator(list)
            .setRemoveAction {
                val selectedIndices = list.selectedIndices
                if (selectedIndices.isNotEmpty()) {
                    // Remove from end to start to preserve indices
                    for (i in selectedIndices.reversed()) {
                        listModel.removeElementAt(i)
                    }
                }
            }
            .setRemoveActionUpdater { list.selectedIndex >= 0 }
            .setEditAction { button ->
                val selectedIndex = list.selectedIndex
                if (selectedIndex >= 0) {
                    val selectedQuote = listModel.getElementAt(selectedIndex)
                    val currentCustomName = setting.getCustomName(selectedQuote.code)
                    val currentCostPrice = setting.getCostPrice(selectedQuote.code)
                    val currentHoldings = setting.getHoldings(selectedQuote.code)

                    val nameField = javax.swing.JTextField(currentCustomName ?: "", 20)
                    val costPriceField = javax.swing.JTextField(
                        currentCostPrice?.let { String.format("%.3f", it) } ?: "", 20
                    )
                    val holdingsField = javax.swing.JTextField(
                        currentHoldings?.toString() ?: "", 20
                    )

                    val editPanel = panel {
                        row {
                            label("Custom name:")
                                .widthGroup("editLabels")
                            cell(nameField)
                        }.layout(RowLayout.LABEL_ALIGNED)
                        row {
                            label("Cost price:")
                                .widthGroup("editLabels")
                            cell(costPriceField)
                        }.layout(RowLayout.LABEL_ALIGNED)
                        row {
                            label("Holdings:")
                                .widthGroup("editLabels")
                            cell(holdingsField)
                        }.layout(RowLayout.LABEL_ALIGNED)
                    }

                    val result = JOptionPane.showConfirmDialog(
                        pane,
                        editPanel,
                        "Edit ${selectedQuote.code}",
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE
                    )

                    if (result == JOptionPane.OK_OPTION) {
                        // Handle custom name
                        val newName = nameField.text.trim()
                        if (newName.isNotBlank()) {
                            setting.setCustomName(selectedQuote.code, newName)
                        } else if (currentCustomName != null) {
                            setting.removeCustomName(selectedQuote.code)
                        }

                        // Handle cost price
                        val costPriceText = costPriceField.text.trim()
                        if (costPriceText.isNotBlank()) {
                            try {
                                val costPrice = costPriceText.toDouble()
                                setting.setCostPrice(selectedQuote.code, costPrice)
                            } catch (e: NumberFormatException) {
                                // Ignore invalid input
                            }
                        } else if (currentCostPrice != null) {
                            setting.removeCostPrice(selectedQuote.code)
                        }

                        // Handle holdings
                        val holdingsText = holdingsField.text.trim()
                        if (holdingsText.isNotBlank()) {
                            try {
                                val holdings = holdingsText.toInt()
                                setting.setHoldings(selectedQuote.code, holdings)
                            } catch (e: NumberFormatException) {
                                // Ignore invalid input
                            }
                        } else if (currentHoldings != null) {
                            setting.removeHoldings(selectedQuote.code)
                        }

                        StockerTableView.refreshAllFinancialColumns()
                        list.repaint()
                    }
                }
            }
            .setEditActionUpdater { list.selectedIndex >= 0 }
        
        val decoratedPanel = decorator.createPanel()
        
        // Bottom action panel with "Move to Top", "Move to Bottom", and "Delete All" buttons
        val actionPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        
        val moveToTopButton = JButton("Move to Top")
        moveToTopButton.toolTipText = "Move selected item(s) to the top of the list"
        moveToTopButton.addActionListener {
            val selectedIndices = list.selectedIndices
            if (selectedIndices.isEmpty()) return@addActionListener
            // Collect selected items in order
            val selectedItems = selectedIndices.map { listModel.getElementAt(it) }
            // Remove from end to start to preserve indices
            for (i in selectedIndices.reversed()) {
                listModel.removeElementAt(i)
            }
            // Insert at top in original order
            for ((insertIdx, item) in selectedItems.withIndex()) {
                listModel.insertElementAt(item, insertIdx)
            }
            // Re-select moved items
            list.selectionModel.clearSelection()
            list.addSelectionInterval(0, selectedItems.size - 1)
        }
        actionPanel.add(moveToTopButton)
        
        val moveToBottomButton = JButton("Move to Bottom")
        moveToBottomButton.toolTipText = "Move selected item(s) to the bottom of the list"
        moveToBottomButton.addActionListener {
            val selectedIndices = list.selectedIndices
            if (selectedIndices.isEmpty()) return@addActionListener
            // Collect selected items in order
            val selectedItems = selectedIndices.map { listModel.getElementAt(it) }
            // Remove from end to start to preserve indices
            for (i in selectedIndices.reversed()) {
                listModel.removeElementAt(i)
            }
            // Append at bottom in original order
            for (item in selectedItems) {
                listModel.addElement(item)
            }
            // Re-select moved items
            val newStart = listModel.size() - selectedItems.size
            list.selectionModel.clearSelection()
            list.addSelectionInterval(newStart, listModel.size() - 1)
        }
        actionPanel.add(moveToBottomButton)
        
        val deleteAllButton = JButton("Delete All")
        deleteAllButton.toolTipText = "Remove all stocks in this tab"
        deleteAllButton.addActionListener {
            if (listModel.isEmpty) return@addActionListener
            val result = Messages.showYesNoDialog(
                pane,
                "Remove all ${listModel.size()} item(s) from this tab?",
                "Confirm Delete All",
                Messages.getWarningIcon()
            )
            if (result == Messages.YES) {
                listModel.removeAllElements()
            }
        }
        actionPanel.add(deleteAllButton)
        
        // Add header at top, decorated panel in center, action panel at bottom
        pane.add(headerPanel, BorderLayout.NORTH)
        pane.add(decoratedPanel, BorderLayout.CENTER)
        pane.add(actionPanel, BorderLayout.SOUTH)
        
        // Refresh the UI to show new components
        pane.revalidate()
        pane.repaint()
    }

}
