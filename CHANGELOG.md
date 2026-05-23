# Changelog

## 1.25.0

### ✨ New Features / 新功能

- **📢 消息雷达 Tab** — renders today's `news-radar.md` (v2.4 schema) with high-confidence event table, Step 0.5 价量对账段, 政策推演, and 新增小作文 excerpt. Backed by new `FinanceNewsRadarLoader` that parses `judgment_snapshot.high_confidence_events / rumor_ledger_today / policy_deductions / price_volume_reconciliation`. `WELL_KNOWN_REPORTS` expanded to include `news-radar` + `news-radar-thematic` (and the rest of v2.10 agent suite) / 新增📢消息雷达标签，渲染 `news-radar.md` v2.4 二维矩阵 schema：高置信消息表 + 价量对账段 + 政策推演 + 小作文摘选

- **🗞️ 小作文台账 Tab** — consumes `judgments/rumors.jsonl` (v2.4 二维矩阵台账)，grouped by status (pending / watching / confirmed / refuted) with scope-hit highlighting (🎯 prefix when rumor's scope mentions a watchlist/portfolio symbol). Resolution notes shown for confirmed/refuted entries for post-mortem reading / 新增🗞️小作文台账标签：consume rumors.jsonl 状态机；scope 命中 watchlist/持仓的 🎯 高亮；confirmed/refuted 的复盘记录段

- **📅 今日调度横幅** — pinned banner at top of Finance tool window summarising `daily-coordinator.md` (v2.9 SessionStart hook). Shows date + trading-day flag + 持仓数 + watchlist 主线数 + ✅/⏳/⏭️ counts. Background tints amber when pending agents exist, green when all done. Tooltip lists every agent and group / 顶部新增今日调度横幅，机械版 daily-coordinator 调度清单一目了然：✅ 已跑 / ⏳ 待跑 / ⏭️ 跳过；待跑时背景标黄

- **⚠️ 主线命名漂移监测** — new `FinanceCanonicalThreads` scans every report's `judgment_snapshot.main_thread`; when ≥2 distinct spellings normalize to the same key (e.g. "AI 算力" vs "AI算力" vs "算力链"), MainThreadHeader shows ⚠️漂移 badge and fires `THREAD_NAME_DRIFT` notification once per day per drift-set. Hardens CLAUDE.md red line #3 from the 5/22 14-alias incident / 新增主线命名漂移监测，红线 #3 闭环：跨报告对比 `main_thread` 拼写，发现 alias 立即弹气泡 + Header 标黄

- **🎯 证伪信号实时盘 Tab** — `FinanceFailureSignalsLoader` extracts yesterday's `failure_signals[]` from market-research / overnight-brief YAML and heuristically parses three pattern families (index thresholds, 北向资金 magnitude, 6-digit code + percent). Live quote topic subscription rates each as ✅ HIT / ❌ NOT_HIT / ⏸ PENDING. Unparseable signals stay PENDING (left for daily-review manual calibration) / 新增🎯证伪信号实时盘：自动解析昨日 `failure_signals[]`，配合实时行情打 ✅/❌/⏸ 标签

- **持仓 DISTANCE 列接入** — `FinanceDistanceAnnotator` adds a 3rd fallback path: when a symbol has no entry-timing recommendation AND no watchlist entry but IS held in portfolio.json, show cost-basis P&L + health-tinted background (🔴 RED / 🟡 YELLOW / 🟢 GREEN from position-risk-monitor) / DISTANCE 列对纯持仓（无 watchlist/entry-timing 计划）新增 fallback：显示成本和浮盈，颜色按 position-risk-monitor 健康度

- **💧 流动性环境徽章** — MainThreadHeader and status bar widget now show `liquidity_env` from `macro-radar.md` (宽松 🟢 / 中性 ⚪ / 紧缩 🔴 / mixed e.g. 中性偏紧). Multi-tier fallback: YAML direct → title regex → markdown body regex / 主线 Header + 状态栏新增流动性环境徽章，从 macro-radar 提取（支持"中性偏紧"这类复合表达）

- **🌡️ 板块温度 Tab** — new panel renders `sector-tracker.json` as 3 sections: today's anomaly leaderboard (sortable), sector strength ranking (by computed avg change%), and per-sector stock breakdown. Uses 🔥/🟢/⚪/🔴/🔻 glyphs to visualise relative strength / 新增🌡️板块温度标签：sector-tracker.json 30+ 板块强弱榜 + 异动 Top + 成分股明细

- **健康度 sparkline** — MainThreadHeader's `健康度 X → Y` now also renders a unicode block sparkline (▁▂▃▄▅▆▇█) showing the actual 3-day trajectory shape, not just endpoints / Header 健康度行新增 unicode 火柴图，显示完整 3 天走势形状

- **IDE 状态栏 widget** — `FinanceStatusBarWidget` registered via `statusBarWidgetFactory` extension; always-visible "🧭 主线 · phase · D{age} · H{score}  💧流动性 ⚠️" in IDE bottom bar. Click opens Stocker tool window. Tooltip shows full snapshot (leader, health series, drift groups) / 注册 IDE 状态栏 widget，常驻显示主线 + phase + 健康度 + 流动性 + 漂移告警

- **📚 深度研究 Tab** — browser for `~/Claude/finance/sessions/{NN-分类}/` themed deep-dive markdown. Left list groups files by category with bold 📂 headers (`03-个股分析` / `04-板块主题` / etc.), right pane renders selected document / 新增📚深度研究标签：浏览 sessions/ 按分类分组的 stock-deep-dive / industry-mapping 等深度研究产出

- **多空辩论 / 风格投票右键链接** — new context menu items "查看多空辩论 (bull-bear)" and "查看风格投票 (style-jury)" look up `reports/<date>/{bull-bear,style-jury}-<symbol>.md` with 7-day fallback and pop the markdown in a dialog. Shows info dialog when report is absent / 右键菜单新增"查看多空辩论 / 查看风格投票"，自动查找当日及前 7 天的 `bull-bear-{symbol}.md` / `style-jury-{symbol}.md`

### 🔧 Infrastructure

- **FinanceFileWatcher 500ms 去抖** — coalesces a burst of file-change events (common during `/复盘` agent runs writing many MD files in 200-300ms) into a single trailing-edge reload. Eliminates UI flicker and reduces panel rebuild count by ~5x / FinanceFileWatcher 加 500ms trailing-edge 去抖：`/复盘` 期间 5 份 MD 连续写入只触发 1 次 reload，UI 不再频繁刷新

- **`reportsMatchingPrefix` 工具方法** — locates `prefix-*.md` files (bull-bear / style-jury / industry-mapping themed variants) without each one being hard-coded in WELL_KNOWN_REPORTS / 新增 `reportsMatchingPrefix` 工具：定位 `prefix-*.md` 变体报告，避免每个 symbol 都写死

- **新 `THREAD_NAME_DRIFT` 通知 Kind** — registered alongside existing `THREAD_BRANCH_FLIP` / `THREAD_OUT_OF_SCOPE` in `FinanceNotifier.Kind` enum

## 1.24.0

### ✨ New Features / 新功能

- **Multi-source ScenarioPanel** — the Thread Tracker tab's state-machine panel now consumes scenario trees from up to four agent sources (v2.3 schema): `market-research` primary tree, `thread-tracker` per-active-thread trees, `theme-incubator` candidate themes (synthetic 2-branch A 点火 / B 证伪), and `position-risk-monitor` per-position scenarios (synthetic 3-branch A 持有 / B 减半 / C 清仓). When more than one tree is available, a dropdown selector at the top of the panel lets the user switch between them. Labels are emoji-prefixed: active threads have no prefix, incubator candidates use 🔥, positions use 💼 / 主线追踪标签的状态机面板现在从最多 4 个 agent 来源消费分支树（v2.3 schema）：market-research 主树、thread-tracker 每条活跃主线、theme-incubator 候选主题（synthetic 2 分支 A 点火 / B 证伪）、position-risk-monitor 持仓预案（synthetic 3 分支 A 持有 / B 减半 / C 清仓）。≥2 条主线时面板顶部出现下拉切换器。emoji 前缀区分：活跃主线无前缀、候选主题 🔥、持仓 💼

- **Structured entry-timing triggers** (B3) — DISTANCE column now consumes the v2.3 `recommendations[].triggers_struct` / `invalidations_struct` fields when present, replacing the previous regex-from-free-text extraction. Range-based pullback triggers (e.g. "回踩 67-69") are now correctly checked as `cur ∈ [low × 0.985, high × 1.015]` instead of the old single-point ±1.5% which missed the upper half of wider zones. Free-text fallback preserved for pre-v2.3 reports / DISTANCE 列现在优先消费 v2.3 `triggers_struct` / `invalidations_struct` 结构化字段，替代之前的正则抽数字。区间式 pullback 触发（如"回踩 67-69"）现在正确检查 `cur ∈ [low × 0.985, high × 1.015]`，而非旧逻辑只能锚单点 ±1.5%（宽度 >3% 的区间上半部分会漏判）。pre-v2.3 报告通过正则保留兜底

### 🔧 Infrastructure

- Flip-notification state in ScenarioPanel is now keyed by the selected tree label so switching threads in the dropdown does not fire a spurious branch-flip balloon for the newly-selected thread / ScenarioPanel 的分支切换通知现按选中的 tree label 分别记账，切换下拉选项不会为新选中的主线误触发分支切换通知

## 1.23.0

### ✨ New Features / 新功能

- Thread Tracker tab upgraded from a static markdown viewer into a live **scenario state-machine panel**. Today's `thread_scenario_tree` from `market-research.md` is parsed and rendered as branch rows (A 乐观 / B 基准 / C 悲观), each with a progress bar tracking how close the leader's live price is to that branch's trigger. The branch matching the current price is highlighted; if the leader breaches `out_of_scope_threshold.upper / .lower` a red banner appears with "agent 需重审主线". The historical markdown narrative is preserved in a resizable bottom pane (default 65:35 split) / 主线追踪标签从静态 markdown 升级为**实时分支状态机面板**。今日 `market-research.md` 的 `thread_scenario_tree` 被解析为分支行（A 乐观 / B 基准 / C 悲观），每行带进度条显示龙头实时价距该分支触发价的距离；当前匹配的分支高亮显示；若龙头跌穿 `out_of_scope_threshold` 上下沿，顶部出现"agent 需重审主线"红色横幅。原 markdown 长文在可调整的下方面板中保留（默认 65:35 分割）
- New balloon notifications for thread-level state changes: `THREAD_BRANCH_FLIP` (when the active branch transitions, e.g. B 横盘 → A 主升) and `THREAD_OUT_OF_SCOPE` (when leader exits all branches). Throttled per-day per-kind so a price oscillating around a threshold doesn't spam / 新增两个主线级气泡通知：`THREAD_BRANCH_FLIP`（活跃分支切换，如 B 横盘 → A 主升）和 `THREAD_OUT_OF_SCOPE`（龙头脱离所有预案）。按"每日 × 每类型 × 每标的"节流，价格在阈值附近震荡不会刷屏

## 1.22.0

### ✨ New Features / 新功能

- Added a dedicated **Watchlist** tab next to the market tabs (CN / HK / US / Crypto). It shows the symbols in `~/Claude/finance/watchlist.json` as a read-only consolidated view across markets, with the Trigger Distance column highlighting price proximity to agent triggers and invalidations. The consolidated quote fetch in StockerApp folds watchlist codes into the per-market HTTP calls (deduped), so no extra network roundtrips are added / 在市场标签（CN / HK / US / Crypto）旁新增专用的"盯盘"标签，跨市场只读展示 `~/Claude/finance/watchlist.json` 里的标的，配合"距阈值"列高亮 agent 触发价/失效价的接近程度。行情拉取在 StockerApp 中合并 watchlist 代码到各市场 HTTP 请求里（去重），不增加额外网络往返
- Watchlist tab updates automatically when `~/Claude/finance/watchlist.json` changes (file watcher triggers a table reset; next refresh cycle re-populates) / watchlist.json 改动时盯盘标签自动重置（file watcher 触发清空，下个刷新周期重新填入）

## 1.21.1

### 🐛 Bug Fixes / 错误修复

- Fixed Settings → Visible columns panel missing the Health and Trigger Distance checkboxes. The settings UI was hardcoded with a fixed column list that fell behind the enum, so users could not toggle these two columns from the UI even though the columns existed. Both checkboxes are now registered alongside the others / 修复 设置 → 可见列 面板缺失"健康度"和"距阈值"复选框的问题。设置 UI 用的是硬编码列表而非 enum 遍历，导致这两列即使在数据层已存在，用户也无法从设置里勾选；现已补齐两个复选框

## 1.21.0

### ✨ New Features / 新功能

- Added a Trigger Distance column that displays the live distance between current price and finance/ watchlist or entry-timing trigger / invalidation prices, with amber background when entering the trigger ±1.5% zone and red background when invalidation is breached / 新增"距阈值"列，实时显示当前价距 finance/ watchlist 与 entry-timing trigger / invalidation 价位的距离；进入 trigger ±1.5% 触发区变琥珀色、跌破 invalidation 变红色背景

### 🔧 Behavior Change / 行为变更

- Watchlist trigger / invalidation hits and entry-timing buy-point hits no longer surface as IDE balloon popups — they appear inline in the new Trigger Distance column instead. Market anomaly (±5% / ±7%) and A-share limit-up / limit-down notifications still fire as before / watchlist trigger / invalidation 命中、entry-timing 买点命中不再弹 IDE 气泡通知，改为在"距阈值"列内联展示。市场异动（±5% / ±7%）和 A 股涨停跌停通知保持不变

## 1.20.1

### 🐛 Bug Fixes / 错误修复

- Fixed the Windows right-click delete race in the table popup menu so stock removal works reliably under the new UI / 修复 Windows 下表格右键删除菜单的竞态问题，确保在新版 UI 中稳定删除股票

## 1.20.0

### ✨ New Features / 新功能

- Added a Net Profit column computed from current price, cost price, and holdings, with sorting and color coding support / 新增净收益额列，基于现价、成本价和持仓自动计算，并支持排序与颜色编码

### 🔧 Maintenance / 维护

- Upgraded the IntelliJ Platform Gradle plugin to 2.12.0 / 升级 IntelliJ Platform Gradle 插件到 2.12.0

## 1.19.0

### ✨ Improvements / 改进

- Restored the toolbar "Stop Refresh" action so scheduled quote updates can be paused without clearing the current table data / 恢复工具栏“停止刷新”操作，可在不清空当前表格数据的情况下暂停定时行情更新

## 1.18.2

### 🐛 Bug Fixes / 错误修复

- Fixed right-click delete sometimes failing because the popup action could lose the selected row before deletion / 修复右键删除偶发失效的问题：弹出菜单执行前表格选中行可能已丢失
- Removed duplicate table row deletion notifications during stock removal to avoid inconsistent refresh behavior / 移除删除股票时重复触发表格行删除通知的问题，避免刷新行为不一致

## 1.18.1

### 🌐 i18n / 国际化

- Fixed action text/description localization in Tools menu and tool window actions; labels now follow the selected plugin language immediately / 修复工具菜单与工具窗口操作项的文本和描述本地化问题；标签现在会立即跟随插件语言设置
- Aligned action naming style for better consistency across English and Chinese labels / 统一中英文操作项命名风格，提升一致性

### 🐛 Bug Fixes / 错误修复

- Fixed "Clear Favorites" to clear all markets including Crypto and trigger proper view refresh / 修复“清空自选”未覆盖加密货币的问题，并确保触发正确的视图刷新
- Fixed crypto symbol validation to use crypto quote provider instead of stock quote provider / 修复加密货币代码校验使用错误数据源的问题（改为使用加密行情源）

## 1.18.0

### 🌐 i18n / 国际化

- Fixed language switching: the plugin language setting now works correctly and applies immediately to the table view / 修复语言切换：插件语言设置现已正常工作，更改后立即应用到表格视图
- Notifications now follow the plugin language setting instead of showing dual-language text / 通知消息现在遵循插件语言设置，不再同时显示中英文

### 🐛 Bug Fixes / 错误修复

- Fixed settings reverting when clicking Apply then OK in the settings dialog / 修复在设置中先点击应用再点击确定时设置被还原的问题
- Fixed table column visibility breaking after language switch (now stored as locale-independent identifiers with automatic migration) / 修复语言切换后表格列可见性失效的问题（现以语言无关的标识符存储，并自动迁移旧配置）

### 🎨 UI Improvements / 界面改进

- Reorganized settings layout into three focused groups: General, Data Provider, and Table Display / 重新整理设置页面为三个分组：通用、数据提供商和表格显示

## 1.17.0

### ✨ New Features / 新功能

- Added settings button to tool window action bar (right-aligned) for quick access to Stocker settings / 在工具窗口操作栏添加设置按钮（右对齐），快速访问 Stocker 设置

## 1.16.2

### ✨ Improvements / 改进

- Enhanced Cost column color coding: displays up color when cost is below current price (profit) and down color when cost is above current price (loss) / 增强成本列颜色编码：成本低于当前价格时显示上涨颜色（盈利），成本高于当前价格时显示下跌颜色（亏损）

## 1.16.1

### ✨ Improvements / 改进

- Added right-click row popup menu in table view with one-click stock deletion / 在表格视图中添加右键行弹出菜单，支持一键删除股票
- Improved popup delete menu hover styling for better theme consistency and visibility / 改进删除菜单悬浮样式，提升主题一致性与可见性

## 1.16.0

### ✨ New Features / 新功能

- Added cost price and holdings columns with visibility toggling for enhanced portfolio tracking / 添加成本价和持仓列，支持显示切换，增强投资组合跟踪

### 🎨 UI Improvements / 界面改进

- Refined table rendering with improved padding and border styling / 优化表格渲染，改进内边距和边框样式
- Adopted IDE theme colors for table selection to ensure better visual consistency / 采用 IDE 主题颜色用于表格选中状态，确保更好的视觉一致性

## 1.15.0

### ✨ New Features / 新功能

- Added cryptocurrency support (crypt support) / 添加加密货币支持
- Added more table columns for enhanced data display / 添加更多表格列以增强数据显示

## 1.14.1

### 🐛 Bug Fixes / 错误修复

- Fixed table sorting not restoring original order when switching back to unsorted state / 修复表格排序在切换回未排序状态时无法恢复原始顺序的问题
- Fixed color pattern not immediately reflecting in tables when clicking Apply in settings (now updates instantly without data refetch) / 修复在设置中点击应用时颜色模式未立即在表格中反映的问题（现在无需重新获取数据即可立即更新）
- Improved settings granularity: color pattern changes no longer trigger unnecessary data refetching / 改进设置粒度：颜色模式更改不再触发不必要的数据重新获取

## 1.14.0

### 🚀 Performance & Memory Optimizations / 性能和内存优化

- **Critical Memory Leak Fixes / 关键内存泄漏修复:**
  - Fixed message bus connection leaks in tool window (15+ connections per window now properly disposed) / 修复工具窗口消息总线连接泄漏（每个窗口15+连接现已正确释放）
  - Fixed project map memory leak (StockerApp instances now cleaned up on project close) / 修复项目映射内存泄漏（项目关闭时清理StockerApp实例）
  - Fixed HTTP response leaks (all responses now properly closed with automatic resource management) / 修复HTTP响应泄漏（所有响应现通过自动资源管理正确关闭）
  - Fixed table view disposal leak (static registry now properly cleaned up) / 修复表格视图释放泄漏（静态注册表现已正确清理）

- **HTTP & Network Improvements / HTTP和网络改进:**
  - Added connection timeouts (10s connect, 15s socket, 5s pool request) to prevent hanging threads / 添加连接超时（10秒连接，15秒套接字，5秒池请求）防止线程挂起
  - Properly close all HTTP connections with `.use{}` pattern / 使用`.use{}`模式正确关闭所有HTTP连接
  - Enhanced connection pool configuration / 增强连接池配置

- **Performance Optimizations / 性能优化:**
  - Consolidated scheduled tasks: reduced from 4 to 1 task (50% reduction in HTTP requests) / 合并计划任务：从4个减少到1个（HTTP请求减少50%）
  - Optimized table sorting: removed data duplication (50% memory reduction during sorting) / 优化表格排序：移除数据复制（排序时内存减少50%）
  - Reduced thread pool size from 4 to 1 threads (75% reduction) / 线程池大小从4减少到1（减少75%）
  - Implemented proper Disposable pattern for resource cleanup / 实现适当的Disposable模式进行资源清理

- **Architectural Improvements / 架构改进:**
  - Added ProjectManagerListener for automatic cleanup on project close / 添加ProjectManagerListener在项目关闭时自动清理
  - Improved encapsulation in StockerAppManager with proper public API / 改进StockerAppManager的封装与适当的公共API
  - Enhanced tool window lifecycle management / 增强工具窗口生命周期管理

## 1.13.1

- Add sortable table columns with three-state sorting (ascending, descending, unsorted) / 添加可排序的表格列，支持三态排序（升序、降序、不排序）

## 1.13.0

- Add customizable table column display settings / 添加可自定义的表格列显示设置

## 1.12.3

- Improve table selection clearing behavior

## 1.12.2

- Fix index names not obeying Pinyin display mode
- Add Hang Seng Tech Index (恒生科技指数, HSTECH)

## 1.12.1

- Add custom stock name feature with edit functionality in management dialog (custom names take highest priority)
- Enhanced management dialog UI with three-column layout (Code, Original Name, Custom Name)
- Enhanced suggestion dialog UI with improved search results layout

## 1.12.0

- Add Pinyin support for stock names with display settings
- Enhanced welcome and release note notifications
- Various technical improvements and dependency updates

## 1.11.1

- Fix IntelliJ 2024.2 series compatibility issues

## 1.11.0

- Fix IntelliJ 2023.3 series compatibility issues

## 1.10.2

- Fix compiler warnings

## 1.10.1

- Add A-Share Convertible Bond support

## 1.10.0

- Bring back SINA provider support

## 1.9.1

- Fix three digits price accuracy issue

## 1.9.0

- New management dialog: batch delete & reorder symbols

## 1.8.1

- Fix compatibility issue

## 1.8.0

- Support JetBrains 2022 EAP

## 1.7.0

- Replace Sina API with Tencent API due to Sina API is closed
- Crypto support is temporary removed since Sina API is no longer available

## 1.6.1

- Support JetBrains 2021.3 series

## 1.6.0

- Enhanced setting window UI
- Enhanced search dialog UI
- Enhanced management dialog UI

## 1.5.3

- Fixed multiple projects compatibility [#12](https://github.com/WhiteVermouth/intellij-investor-dashboard/issues/12)
- Fixed API compatibility

## 1.5.2

- Support IntelliJ 2021.2 EAP

## 1.5.1

- Fixed price accuracy [#11](https://github.com/WhiteVermouth/intellij-investor-dashboard/issues/11)

## 1.5.0

- New action: Stop refresh
- New pane: Crypto
- Deprecated: Tencent API

## 1.4.4

- Fix Long stock name wrapping
- Fix search bar text change event

## 1.4.3

- Fixed Android Studio compatibility
- Fixed missed ETF in search results

## 1.4.2

- Fix compatibility issue

## 1.4.1

- Enhanced stock management dialogs

## 1.4.0

- New Stock Add Dialog
- New Stock Delete Dialog
- Some enhancement and bug fix

## 1.3.7

- Support JetBrains 2019 series

## 1.3.6

- Add backward compatibility until 2020.1

## 1.3.5

- Fixed compatibility issue

## 1.3.4

- Support disable Red/Green color pattern

## 1.3.3

- Bug fix

## 1.3.2

- Bug fix

## 1.3.1

- Add right-click popup menu to delete code(s)

## 1.3.0

- Add index view

## 1.2.1

- Enhanced UI
- Bug fix

## 1.2.0

- Add a tab: ALL
- Enhanced UI

## 1.1.0

- Adopt more distinct colors
- Improve Last Update At datetime
- Add a new quote provider: Tencent

## 1.0.0

- Stocker: a stock quote dashboard
