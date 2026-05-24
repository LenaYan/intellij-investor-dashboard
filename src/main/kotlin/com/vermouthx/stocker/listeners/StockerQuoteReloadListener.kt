package com.vermouthx.stocker.listeners

import com.vermouthx.stocker.views.StockerTableView

class StockerQuoteReloadListener(private val myTableView: StockerTableView) : StockerQuoteReloadNotifier {

    override fun clear() {
        val tableModel = myTableView.tableModel
        synchronized(tableModel) {
            tableModel.rowCount = 0
            myTableView.clearSortState()
        }
    }
}
