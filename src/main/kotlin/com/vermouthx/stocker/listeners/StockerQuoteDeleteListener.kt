package com.vermouthx.stocker.listeners

import com.vermouthx.stocker.utils.StockerTableModelUtil
import com.vermouthx.stocker.views.StockerTableView

class StockerQuoteDeleteListener(private val myTableView: StockerTableView) : StockerQuoteDeleteNotifier {

    override fun after(code: String) {
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
