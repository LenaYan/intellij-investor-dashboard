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
                        StockerBundle.message(
                            "cloud.sync.upload.success",
                            favResult.getOrDefault(0),
                            watchlistResult.getOrDefault(0),
                        ),
                        StockerBundle.message("cloud.sync.title")
                    )
                } else {
                    val error = favResult.exceptionOrNull()?.message
                        ?: watchlistResult.exceptionOrNull()?.message
                        ?: StockerBundle.message("cloud.sync.unknown.error")
                    Messages.showErrorDialog(
                        e.project,
                        StockerBundle.message("cloud.sync.upload.failed", error),
                        StockerBundle.message("cloud.sync.error.title")
                    )
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
                        StockerBundle.message("cloud.sync.download.success", favResult.getOrDefault(0)),
                        StockerBundle.message("cloud.sync.title")
                    )
                } else {
                    Messages.showErrorDialog(
                        e.project,
                        StockerBundle.message(
                            "cloud.sync.download.failed",
                            favResult.exceptionOrNull()?.message
                                ?: StockerBundle.message("cloud.sync.unknown.error"),
                        ),
                        StockerBundle.message("cloud.sync.error.title")
                    )
                }
            }
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
