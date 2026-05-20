package com.vermouthx.stocker.finance

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager

/**
 * Convenience entry-point for the table popup menu. Java callers invoke
 * [addToWatchlist] which is responsible for the entire flow:
 *
 *   1. show the dialog pre-filled from the table row
 *   2. on OK, write watchlist.json
 *   3. tell the bridge to reload so the dot/tooltip refresh
 *   4. emit a Stocker notification with the result
 */
object FinanceWatchlistActions {

    @JvmStatic
    fun addToWatchlist(symbol: String, name: String?, refPrice: Double?) {
        val project: Project? = ProjectManager.getInstance().openProjects.firstOrNull()
        val dlg = AddToWatchlistDialog(project, symbol, name ?: symbol, refPrice)
        if (!dlg.showAndGet()) return
        val entry = dlg.toEntry()
        val dir = FinanceBridgeService.instance.financeDir()
        val ok = FinanceWatchlistWriter.upsert(dir, entry)
        if (ok) {
            FinanceBridgeService.instance.reloadNow()
            notify(
                project,
                "✅ 已写入 watchlist",
                "${entry.name} (${entry.symbol}) 已加入 ~/Claude/finance/watchlist.json\n" +
                "记得运行 `cp ~/Claude/finance/watchlist.json ~/Claude/finance-mcp/data/watchlist.json` 同步到 MCP server",
                NotificationType.INFORMATION
            )
        } else {
            notify(
                project,
                "❌ 写入 watchlist 失败",
                "无法写入 ${dir}/watchlist.json — 检查目录权限或路径配置",
                NotificationType.ERROR
            )
        }
    }

    private fun notify(project: Project?, title: String, body: String, type: NotificationType) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Stocker") ?: return
        val n = group.createNotification(title, body, type)
        if (project != null) n.notify(project) else n.notify(null)
    }
}
