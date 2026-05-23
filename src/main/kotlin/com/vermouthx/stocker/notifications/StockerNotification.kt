package com.vermouthx.stocker.notifications

import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.vermouthx.stocker.StockerMeta
import com.vermouthx.stocker.settings.StockerSetting
import org.intellij.lang.annotations.Language
import java.util.*

object StockerNotification {

    private object Colors {
        const val PRIMARY = "#4CAF50"
        const val BACKGROUND = "rgba(33, 150, 243, 0.08)"
        const val BORDER = "#2196F3"
    }

    private object Styles {
        const val CONTAINER = "margin: 8px 0; line-height: 1.4;"
        const val HEADING = "margin: 0 0 8px 0; color: ${Colors.PRIMARY}; font-size: 14px; font-weight: 600;"
        const val PARAGRAPH = "margin: 0 0 12px 0; font-size: 13px;"
        const val SMALL_TEXT = "margin: 12px 0 0 0; font-size: 12px; font-style: italic; opacity: 0.7;"
        const val LIST_ITEM = "margin: 6px 0;"
        const val SUB_LIST = "margin: 4px 0 0 0; padding-left: 18px; font-size: 12px;"
        const val INFO_BOX = "background: ${Colors.BACKGROUND}; border-left: 3px solid ${Colors.BORDER}; padding: 10px 12px; margin: 12px 0; border-radius: 3px;"
    }

    private val version get() = StockerMeta.currentVersion

    private fun isChinese(): Boolean {
        val override = try { StockerSetting.instance.languageOverride } catch (_: Exception) { "" }
        if (override == "zh_CN") return true
        if (override == "en") return false
        return Locale.getDefault().language == "zh"
    }

    @Language("HTML")
    private fun buildReleaseNote(): String {
        val v = version
        return if (isChinese()) """
            <div style="${Styles.CONTAINER}">
                <p style="${Styles.PARAGRAPH}">🎉 <strong>欢迎使用 Stocker v${v}！本次更新对齐 finance v2.4–v2.11：</strong></p>
                <h4 style="${Styles.HEADING}">✨ v${v} 新功能 — 7 个新面板 + 状态栏 widget</h4>
                <ul style="margin: 0; padding-left: 18px;">
                    <li style="${Styles.LIST_ITEM}">📢 <strong>消息雷达 Tab</strong> — news-radar.md v2.4 二维矩阵（高置信表 + 价量对账 + 政策推演 + 小作文）</li>
                    <li style="${Styles.LIST_ITEM}">🗞️ <strong>小作文台账 Tab</strong> — rumors.jsonl 状态机（pending/watching/confirmed/refuted）；scope 命中持仓的 🎯 高亮</li>
                    <li style="${Styles.LIST_ITEM}">📅 <strong>今日调度横幅</strong> — daily-coordinator.md 调度清单（✅/⏳/⏭️）；待跑标黄</li>
                    <li style="${Styles.LIST_ITEM}">⚠️ <strong>主线命名漂移监测</strong> — CLAUDE.md 红线 #3 闭环；跨报告 alias 自动告警</li>
                    <li style="${Styles.LIST_ITEM}">🎯 <strong>证伪信号实时盘</strong> — 昨日 failure_signals 配实时行情打 ✅/❌/⏸</li>
                    <li style="${Styles.LIST_ITEM}">🌡️ <strong>板块温度 Tab</strong> — sector-tracker.json 30+ 板块强弱榜 + 异动 Top</li>
                    <li style="${Styles.LIST_ITEM}">📚 <strong>深度研究 Tab</strong> — sessions/ 按分类分组的 stock-deep-dive / industry-mapping 浏览器</li>
                </ul>
                <h4 style="${Styles.HEADING}">🔧 主线 Header / 状态栏增强</h4>
                <ul style="margin: 0; padding-left: 18px;">
                    <li style="${Styles.LIST_ITEM}">💧 流动性环境徽章（macro-radar 提取，宽松🟢/中性⚪/紧缩🔴）</li>
                    <li style="${Styles.LIST_ITEM}">▁▂▃▄▅▆▇█ 健康度 sparkline（最近 3 天走势）</li>
                    <li style="${Styles.LIST_ITEM}">IDE 状态栏 widget 常驻显示 "🧭 主线 · phase · D{age} · H{score} · 💧"</li>
                </ul>
                <h4 style="${Styles.HEADING}">📦 其他</h4>
                <ul style="margin: 0; padding-left: 18px;">
                    <li style="${Styles.LIST_ITEM}">持仓 DISTANCE 列接入（无 watchlist/entry-timing 计划也显示成本和浮盈）</li>
                    <li style="${Styles.LIST_ITEM}">右键菜单新增"查看多空辩论 / 风格投票"</li>
                    <li style="${Styles.LIST_ITEM}">FinanceFileWatcher 加 500ms 去抖（/复盘 期间不再刷屏）</li>
                </ul>
                <p style="${Styles.SMALL_TEXT}">💖 如果您觉得这个插件有帮助，请考虑点击下方的 <strong>Donate</strong> 按钮以支持开发。谢谢！📈</p>
            </div>
        """.trimIndent() else """
            <div style="${Styles.CONTAINER}">
                <p style="${Styles.PARAGRAPH}">🎉 <strong>Welcome to Stocker v${v}! Aligned with finance v2.4–v2.11:</strong></p>
                <h4 style="${Styles.HEADING}">✨ New in v${v} — 7 new panels + status bar widget</h4>
                <ul style="margin: 0; padding-left: 18px;">
                    <li style="${Styles.LIST_ITEM}">📢 <strong>News Radar Tab</strong> — news-radar.md v2.4 2D-matrix view</li>
                    <li style="${Styles.LIST_ITEM}">🗞️ <strong>Rumor Ledger Tab</strong> — rumors.jsonl state machine with scope-hit 🎯 highlight</li>
                    <li style="${Styles.LIST_ITEM}">📅 <strong>Coordinator Banner</strong> — daily-coordinator schedule with ✅/⏳/⏭️ status</li>
                    <li style="${Styles.LIST_ITEM}">⚠️ <strong>Thread name drift watchdog</strong> — CLAUDE.md red line #3 protection</li>
                    <li style="${Styles.LIST_ITEM}">🎯 <strong>Live failure-signal monitor</strong> — yesterday's failure_signals × live quotes</li>
                    <li style="${Styles.LIST_ITEM}">🌡️ <strong>Sector Thermometer Tab</strong> — sector-tracker.json strength ranking</li>
                    <li style="${Styles.LIST_ITEM}">📚 <strong>Sessions Browser Tab</strong> — themed deep-dive markdown by category</li>
                </ul>
                <h4 style="${Styles.HEADING}">🔧 Main-thread Header / Status Bar</h4>
                <ul style="margin: 0; padding-left: 18px;">
                    <li style="${Styles.LIST_ITEM}">💧 Liquidity environment badge from macro-radar</li>
                    <li style="${Styles.LIST_ITEM}">▁▂▃▄▅▆▇█ Health sparkline (last 3 days)</li>
                    <li style="${Styles.LIST_ITEM}">IDE status bar widget showing main_thread / phase / age / health</li>
                </ul>
                <h4 style="${Styles.HEADING}">📦 Other</h4>
                <ul style="margin: 0; padding-left: 18px;">
                    <li style="${Styles.LIST_ITEM}">Portfolio fallback for DISTANCE column (cost-basis P&amp;L + health color)</li>
                    <li style="${Styles.LIST_ITEM}">Right-click menu: "View bull-bear" / "View style-jury"</li>
                    <li style="${Styles.LIST_ITEM}">FinanceFileWatcher 500ms debounce — no more flicker during /复盘 bursts</li>
                </ul>
                <p style="${Styles.SMALL_TEXT}">💖 If you find this plugin helpful, please consider clicking the <strong>Donate</strong> button below. Thank you! 📈</p>
            </div>
        """.trimIndent()
    }

    @Language("HTML")
    private fun buildWelcomeMessage(): String {
        return if (isChinese()) """
            <div style="${Styles.CONTAINER}">
                <p style="${Styles.PARAGRAPH}">🎉 <strong>欢迎使用 Stocker！</strong>您的投资仪表板已安装完成，可以开始跟踪您喜爱的股票了。</p>
                <div style="${Styles.INFO_BOX}">
                    <p style="margin: 0 0 8px 0; font-size: 12px;">💡 <strong>快速设置：</strong></p>
                    <ul style="margin: 0; padding-left: 16px; font-size: 12px;">
                        <li style="margin: 4px 0;">从左侧面板打开 <strong>Stocker</strong> 工具窗口</li>
                        <li style="margin: 4px 0;">点击<strong>添加自选股</strong>来搜索和添加股票</li>
                        <li style="margin: 4px 0;">在<strong>设置 → 工具 → Stocker</strong> 中配置选项</li>
                        <li style="margin: 4px 0;">开始实时跟踪您的投资！</li>
                    </ul>
                </div>
                <p style="${Styles.SMALL_TEXT}">💖 如果您觉得这个插件有帮助，请考虑点击下方的 <strong>Donate</strong> 按钮以支持开发。谢谢！📊</p>
            </div>
        """.trimIndent() else """
            <div style="${Styles.CONTAINER}">
                <p style="${Styles.PARAGRAPH}">🎉 <strong>Welcome to Stocker!</strong> Your investment dashboard is now installed and ready to track your favorite stocks.</p>
                <div style="${Styles.INFO_BOX}">
                    <p style="margin: 0 0 8px 0; font-size: 12px;">💡 <strong>Quick Setup:</strong></p>
                    <ul style="margin: 0; padding-left: 16px; font-size: 12px;">
                        <li style="margin: 4px 0;">Open the <strong>Stocker</strong> tool window from the left panel</li>
                        <li style="margin: 4px 0;">Click <strong>Add Favorite Stocks</strong> to search and add stocks</li>
                        <li style="margin: 4px 0;">Configure settings at <strong>Settings → Tools → Stocker</strong></li>
                        <li style="margin: 4px 0;">Start tracking your investments in real-time!</li>
                    </ul>
                </div>
                <p style="${Styles.SMALL_TEXT}">💖 If you find this plugin helpful, please consider clicking the <strong>Donate</strong> button below to support its development. Thank you! 📊</p>
            </div>
        """.trimIndent()
    }

    private const val NOTIFICATION_GROUP_ID = "Stocker"

    @JvmField
    val notificationIcon = IconLoader.getIcon("/icons/logo.png", javaClass)

    private const val GITHUB_LINK = "https://github.com/WhiteVermouth/intellij-investor-dashboard"
    private const val DONATE_LINK = "https://www.buymeacoffee.com/nszihan"

    fun notifyReleaseNote(project: Project) {
        val title = if (isChinese()) "Stocker v${version} - 版本说明" else "Stocker v${version} - Release Notes"
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, buildReleaseNote(), NotificationType.INFORMATION)
        addNotificationActions(notification)
        notification.icon = notificationIcon
        notification.notify(project)
    }

    fun notifyWelcome(project: Project) {
        val title = if (isChinese()) "Stocker 安装成功" else "Stocker Successfully Installed"
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, buildWelcomeMessage(), NotificationType.INFORMATION)
        addNotificationActions(notification)
        notification.icon = notificationIcon
        notification.notify(project)
    }

    private fun addNotificationActions(notification: Notification) {
        val github = NotificationAction.createSimple("📖 GitHub") {
            BrowserUtil.browse(GITHUB_LINK)
        }
        val actionDonate = NotificationAction.createSimple("☕ Donate") {
            BrowserUtil.browse(DONATE_LINK)
        }
        notification.addAction(github)
        notification.addAction(actionDonate)
    }
}
