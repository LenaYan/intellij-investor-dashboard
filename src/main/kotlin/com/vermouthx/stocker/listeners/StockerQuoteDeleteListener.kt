package com.vermouthx.stocker.listeners

import com.vermouthx.stocker.utils.StockerTableModelUtil
import com.vermouthx.stocker.views.StockerTableView
import javax.swing.SwingUtilities

class StockerQuoteDeleteListener(private val myTableView: StockerTableView) : StockerQuoteDeleteNotifier {

    override fun after(code: String) {
        SwingUtilities.invokeLater {
            val tableModel = myTableView.tableModel
            synchronized(tableModel) {
                val rowIndex = StockerTableModelUtil.existAt(tableModel, code)
                if (rowIndex != -1) {
                    tableModel.removeRow(rowIndex)
                    myTableView.clearSortState()
                }
            }
        }
    }
}
