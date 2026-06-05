package com.vermouthx.stocker.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.vermouthx.stocker.StockerAppManager
import com.vermouthx.stocker.StockerBundle
import com.vermouthx.stocker.finance.FinanceBridgeService
import com.vermouthx.stocker.services.StockerCloudSyncService
import com.vermouthx.stocker.settings.StockerSetting

class StockerCloudUploadAction : AnAction() {
    override fun update(e: AnActionEvent) {
        val setting = StockerSetting.instance
        e.presentation.text = StockerBundle.message("action.cloud.sync.upload")
        e.presentation.description = StockerBundle.message("action.cloud.sync.upload.description")
        e.presentation.isEnabled = setting.cloudSyncEnabled && setting.cloudSyncApiKey.isNotBlank()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val syncService = StockerCloudSyncService.instance
        val setting = StockerSetting.instance
        syncService.baseUrl = setting.cloudSyncBaseUrl
        syncService.apiKey = setting.cloudSyncApiKey

        ApplicationManager.getApplication().executeOnPooledThread {
            // Upload favorites
            val favResult = syncService.uploadFavorites()

            // Upload watchlist from finance bridge
            val watchlistResult = uploadWatchlistFromBridge(syncService)

            ApplicationManager.getApplication().invokeLater {
                if (favResult.isSuccess && watchlistResult.isSuccess) {
                    Messages.showInfoMessage(
                        e.project,
                        "Uploaded ${favResult.getOrDefault(0)} favorites and ${watchlistResult.getOrDefault(0)} watchlist entries.",
                        "Cloud Sync"
                    )
                } else {
                    val error = favResult.exceptionOrNull()?.message
                        ?: watchlistResult.exceptionOrNull()?.message ?: "Unknown error"
                    Messages.showErrorDialog(e.project, "Sync failed: $error", "Cloud Sync Error")
                }
            }
        }
    }

    private fun uploadWatchlistFromBridge(syncService: StockerCloudSyncService): Result<Int> {
        val snapshot = FinanceBridgeService.instance.snapshot()
        val items = snapshot.watchlistBySymbol.values.map { entry ->
            StockerCloudSyncService.WatchlistItem(
                symbol = entry.symbol,
                normalizedKey = entry.normalizedKey,
                name = entry.name,
                sector = entry.sector,
                thesis = entry.thesis,
                trigger = entry.trigger,
                targetZoneLow = entry.targetZoneLow,
                targetZoneHigh = entry.targetZoneHigh,
                invalidation = entry.invalidation,
                refPrice = entry.refPrice,
            )
        }
        return syncService.uploadWatchlist(items)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

class StockerCloudDownloadAction : AnAction() {
    override fun update(e: AnActionEvent) {
        val setting = StockerSetting.instance
        e.presentation.text = StockerBundle.message("action.cloud.sync.download")
        e.presentation.description = StockerBundle.message("action.cloud.sync.download.description")
        e.presentation.isEnabled = setting.cloudSyncEnabled && setting.cloudSyncApiKey.isNotBlank()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val syncService = StockerCloudSyncService.instance
        val setting = StockerSetting.instance
        syncService.baseUrl = setting.cloudSyncBaseUrl
        syncService.apiKey = setting.cloudSyncApiKey

        ApplicationManager.getApplication().executeOnPooledThread {
            val myApplication = StockerAppManager.myApplication(e.project)
            myApplication?.shutdownThenClear()

            val favResult = syncService.downloadFavorites()

            myApplication?.schedule()

            ApplicationManager.getApplication().invokeLater {
                if (favResult.isSuccess) {
                    Messages.showInfoMessage(
                        e.project,
                        "Downloaded ${favResult.getOrDefault(0)} favorites from cloud.",
                        "Cloud Sync"
                    )
                } else {
                    Messages.showErrorDialog(
                        e.project,
                        "Download failed: ${favResult.exceptionOrNull()?.message}",
                        "Cloud Sync Error"
                    )
                }
            }
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
