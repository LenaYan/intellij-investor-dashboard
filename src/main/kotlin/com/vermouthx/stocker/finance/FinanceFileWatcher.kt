package com.vermouthx.stocker.finance

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Watches the finance/ working directory for changes to:
 *   - watchlist.json
 *   - portfolio.json
 *   - reports/<today>/position-risk-monitor.md
 *   - reports/<today>/market-research.md
 *
 * Coarse-grained: any change in the watched directories triggers a full re-read
 * of [FinanceState]. The reload is cheap (small JSON + one YAML block extraction)
 * and the file change rate is human-scale, so debouncing is intentionally minimal.
 */
internal class FinanceFileWatcher(
    private val financeDir: Path,
    private val onReload: () -> Unit,
) {
    private val log = Logger.getInstance(FinanceFileWatcher::class.java)
    private val running = AtomicBoolean(false)
    @Volatile private var watcher: WatchService? = null
    @Volatile private var lastReportsDate: String? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (!Files.isDirectory(financeDir)) {
            log.info("FinanceFileWatcher: $financeDir does not exist; bridge will sit idle.")
            running.set(false)
            return
        }
        // Initial read
        onReload()

        thread(name = "Stocker-FinanceFileWatcher", isDaemon = true) {
            try {
                runWatchLoop()
            } catch (t: Throwable) {
                log.warn("FinanceFileWatcher loop terminated: ${t.message}")
            } finally {
                running.set(false)
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            watcher?.close()
        } catch (_: Exception) {
            // ignore
        }
        watcher = null
    }

    private fun runWatchLoop() {
        val ws = FileSystems.getDefault().newWatchService()
        watcher = ws
        // We register the root finance/ dir + today's reports dir (if exists).
        financeDir.register(
            ws,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE,
        )
        registerTodayReports(ws)

        while (running.get()) {
            val key = try {
                ws.take()
            } catch (_: InterruptedException) {
                break
            } catch (_: java.nio.file.ClosedWatchServiceException) {
                break
            }

            // Drain & coalesce: we don't care which file changed, only that *something* did.
            var changed = false
            for (event in key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue
                changed = true
            }
            val valid = key.reset()
            if (changed) {
                // refresh today's reports registration in case calendar rolled over
                if (todayString() != lastReportsDate) {
                    registerTodayReports(ws)
                }
                try {
                    onReload()
                } catch (t: Throwable) {
                    log.warn("FinanceFileWatcher reload threw: ${t.message}")
                }
            }
            if (!valid) {
                // The watch key is no longer valid (e.g. dir was deleted); exit cleanly.
                break
            }
        }
    }

    private fun registerTodayReports(ws: WatchService) {
        val day = todayString()
        lastReportsDate = day
        val dir = financeDir.resolve("reports").resolve(day)
        if (Files.isDirectory(dir)) {
            try {
                dir.register(
                    ws,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE,
                )
            } catch (_: Exception) {
                // dir may have been removed between isDirectory and register; ignore
            }
        }
        // Also try to register the parent reports/ so that today's dir creation is observed
        val reportsRoot = financeDir.resolve("reports")
        if (Files.isDirectory(reportsRoot)) {
            try {
                reportsRoot.register(ws, StandardWatchEventKinds.ENTRY_CREATE)
            } catch (_: Exception) {
                // already registered or unreachable; ignore
            }
        }
    }

    private fun todayString(): String =
        LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
}
