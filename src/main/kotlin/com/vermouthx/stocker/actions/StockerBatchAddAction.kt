package com.vermouthx.stocker.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.vermouthx.stocker.StockerBundle
import com.vermouthx.stocker.views.dialogs.StockerBatchAddDialog

class StockerBatchAddAction : AnAction() {
    override fun update(e: AnActionEvent) {
        val project = e.project
        val presentation = e.presentation
        presentation.text = StockerBundle.message("action.batch.add.stocks")
        presentation.description = StockerBundle.message("action.batch.add.stocks.description")
        presentation.isEnabled = project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        StockerBatchAddDialog(e.project).show()
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}
