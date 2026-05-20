package com.vermouthx.stocker.finance

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Fires one-shot notifications when a new daily report appears for the first time:
 *   - overnight-brief.md  → "盘前简报已生成"  with the top-3 watch items section
 *   - flow-monitor.md     → "龙虎榜更新"  with seats lines that mention watchlist/portfolio symbols
 *
 * State is per-(agent, date), kept in memory; reopening the IDE resets it so users
 * see the brief again on the first launch of each day.
 */
internal class FinanceReportNotifier(
    private val project: Project,
    private val state: FinanceState,
) {
    private val notified = ConcurrentHashMap<String, Boolean>()

    fun onReload(financeDir: Path) {
        val today = FinanceReportLocator.today()
        // 1) overnight-brief
        val brief = financeDir.resolve("reports").resolve(today.toString()).resolve("overnight-brief.md")
        if (Files.isRegularFile(brief)) {
            val key = "overnight-brief|$today"
            if (notified.putIfAbsent(key, true) == null) {
                fireOvernightBrief(brief, today)
            }
        }
        // 2) flow-monitor (longhubang section)
        val flow = financeDir.resolve("reports").resolve(today.toString()).resolve("flow-monitor.md")
        if (Files.isRegularFile(flow)) {
            val key = "flow-monitor|$today"
            if (notified.putIfAbsent(key, true) == null) {
                fireLongHuBang(flow, today)
            }
        }
    }

    fun reset() = notified.clear()

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun fireOvernightBrief(p: Path, date: LocalDate) {
        val md = try { Files.readString(p) } catch (_: Exception) { return }
        val section = FinanceReportLocator.extractSection(
            md,
            "今日 3 件最值得关注", "三件最值得关注", "最值得关注的事", "今日重点", "3 件"
        ) ?: FinanceReportLocator.extractFrontMatterAndTitle(md)
        val body = section.lineSequence().take(20).joinToString("\n").trim()
        if (body.isBlank()) return
        notify("☀️ $date 盘前简报已生成", body, NotificationType.INFORMATION)
    }

    private fun fireLongHuBang(p: Path, date: LocalDate) {
        val md = try { Files.readString(p) } catch (_: Exception) { return }
        val section = FinanceReportLocator.extractSection(md, "龙虎榜", "longhubang", "席位") ?: return
        val snap = state.get()
        val tracked = snap.watchlistBySymbol.keys + snap.portfolioBySymbol.keys
        if (tracked.isEmpty()) return
        // Pick lines whose first 6-digit code is in our tracked set
        val codeRe = Regex("""\b(\d{6})\b""")
        val hits = section.lines().filter { line ->
            codeRe.findAll(line).any { m ->
                FinanceSymbol.normalize(m.groupValues[1]) in tracked
            }
        }
        if (hits.isEmpty()) return
        val body = hits.take(15).joinToString("\n")
        notify("🐯 $date 龙虎榜命中 ${hits.size} 条 watchlist/持仓", body, NotificationType.WARNING)
    }

    private fun notify(title: String, body: String, type: NotificationType) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Stocker") ?: return
        group.createNotification(title, body, type).notify(project)
    }
}
