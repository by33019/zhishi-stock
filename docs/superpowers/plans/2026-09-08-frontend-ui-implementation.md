# AI 智能股票分析平台前端 UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `frontend/` 中建立与 PRD、架构和 REST API 契约一致的 Vue3 高保真可运行界面。

**Architecture:** 使用 Vue3 + Vite + TypeScript 构建单页应用，Vue Router 负责公开、用户和管理路由，Pinia 保存跨页面会话状态，Mock API 模拟统一返回格式。ECharts 封装在独立组件中，AI 流和行情推送以可替换的前端状态适配器呈现。

**Tech Stack:** Vue3、Vite、TypeScript、Pinia、Vue Router、ECharts、Lucide Vue、Vitest、Vue Test Utils、Playwright。Element Plus 保留为可选依赖，但当前高保真界面未做全量运行时引入，以控制首屏体积并保持视觉语言独立。

**实施说明：** 本计划已按最终落地状态回填。仅在两个及以上页面复用的视觉结构才抽取为共享组件；单页专属的指标卡、行情表格和资讯列表保留在页面内组合，避免为原型阶段引入无收益抽象。登录页已完成界面和交互验证，真实 JWT 会话、权限菜单和接口鉴权在后端联调阶段接入。

---

### Task 1: 工程和测试基线

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/vite.config.ts`
- Create: `frontend/tsconfig.json`
- Create: `frontend/index.html`
- Create: `frontend/src/main.ts`
- Create: `frontend/src/App.vue`
- Test: `frontend/src/router/router.test.ts`

- [x] 先编写路由失败测试，断言核心公开、用户和管理路由存在并携带布局元数据。
- [x] 安装依赖并运行 `npm run test -- --run`，确认因路由模块不存在而失败。
- [x] 创建 Vite、Vue 入口和路由配置，使路由测试通过。
- [x] 运行 `npm run test -- --run`，确认测试恢复为绿色。

### Task 2: 领域类型、Mock API 与格式化

**Files:**
- Create: `frontend/src/types/domain.ts`
- Create: `frontend/src/services/mockApi.ts`
- Create: `frontend/src/utils/format.ts`
- Test: `frontend/src/services/mockApi.test.ts`
- Test: `frontend/src/utils/format.test.ts`

- [x] 先测试 Snowflake ID 保持字符串、涨跌值格式、行情数据状态和统一响应外壳。
- [x] 运行目标测试并确认因实现缺失而失败。
- [x] 实现最小领域类型、格式化函数和异步 Mock API。
- [x] 运行测试确认通过，并保持页面只通过服务层获取数据。

### Task 3: 视觉 Token 与应用壳

**Files:**
- Create: `frontend/src/styles/tokens.css`
- Create: `frontend/src/styles/base.css`
- Create: `frontend/src/layouts/AppShell.vue`
- Create: `frontend/src/components/BrandMark.vue`
- Create: `frontend/src/components/GlobalSearch.vue`
- Create: `frontend/src/stores/ui.ts`
- Test: `frontend/src/layouts/AppShell.test.ts`

- [x] 先测试导航名称、市场状态、搜索入口和 AI 侧栏开关。
- [x] 运行测试确认 AppShell 缺失导致失败。
- [x] 实现桌面壳、移动导航、设计 Token 和键盘焦点样式。
- [x] 运行布局测试和类型检查。

### Task 4: 市场首页与共享数据组件

**Files:**
- Create: `frontend/src/pages/MarketOverview.vue`
- Create: `frontend/src/components/PageHeader.vue`
- Create: `frontend/src/components/BaseChart.vue`
- Test: `frontend/src/pages/MarketOverview.test.ts`

- [x] 先测试指数、市场广度、热点板块、榜单、资讯和数据截止时间是否出现。
- [x] 运行测试并确认页面缺失导致失败。
- [x] 实现市场研究画布和 ECharts 图表，加入加载、降级与空状态。
- [x] 运行页面测试、类型检查和生产构建。

### Task 5: 行情、板块和个股页面

**Files:**
- Create: `frontend/src/pages/RankingsPage.vue`
- Create: `frontend/src/pages/SectorsPage.vue`
- Create: `frontend/src/pages/SectorDetailPage.vue`
- Create: `frontend/src/pages/StockDetailPage.vue`
- Create: `frontend/src/components/AiResearchPanel.vue`
- Test: `frontend/src/pages/StockDetailPage.test.ts`

- [x] 先测试个股页行情、周期切换、业务资料、关联资讯和 AI 入口。
- [x] 运行测试并确认页面缺失导致失败。
- [x] 实现榜单筛选、板块视图、K 线图和个股研究布局。
- [x] 运行目标测试和全量测试。

### Task 6: 资讯、自选、AI 与历史页面

**Files:**
- Create: `frontend/src/pages/NewsPage.vue`
- Create: `frontend/src/pages/WatchlistPage.vue`
- Create: `frontend/src/pages/AiWorkspacePage.vue`
- Create: `frontend/src/pages/HistoryPage.vue`
- Test: `frontend/src/pages/AiWorkspacePage.test.ts`

- [x] 先测试 AI 场景选择、多标的上限、生成状态、证据和免责声明。
- [x] 运行测试并确认失败原因来自功能缺失。
- [x] 实现资讯筛选、自选分组、AI 工作台和历史报告界面。
- [x] 运行目标测试和全量测试。

### Task 7: 登录与管理端

**Files:**
- Create: `frontend/src/pages/LoginPage.vue`
- Create: `frontend/src/pages/AdminOverviewPage.vue`
- Test: `frontend/src/pages/LoginPage.test.ts`

- [x] 先测试登录字段、游客价值说明和返回路由提示。
- [x] 运行测试确认缺失实现导致失败。
- [x] 实现登录页、前端状态反馈、后台运行状态与用户权限概览；真实会话接入延后至后端联调。
- [x] 运行全量测试和类型检查。

### Task 8: 浏览器验证与 Figma 同步

**Files:**
- Create: `frontend/output/playwright/market-desktop.png`
- Create: `frontend/output/playwright/stock-desktop.png`
- Create: `frontend/output/playwright/market-mobile.png`

- [x] 运行 `npm run test -- --run`、`npm run typecheck` 和 `npm run build`。
- [x] 启动 Vite，通过 Playwright 检查导航、个股 AI 侧栏、登录页和移动布局。
- [x] 修复所有控制台错误、水平溢出、遮挡和对比度问题，并重新验证。
- [x] 新建 Figma Design 文件，将设计基础与核心市场首页捕获为视觉评审基线。
- [x] 更新本计划复选框并记录验证结果；当前目录不是 Git 仓库，因此不执行提交步骤。

## 验证记录

- 单元测试：8 个测试文件、13 个测试用例全部通过。
- 类型检查：`vue-tsc` 与 Node 配置检查通过。
- 生产构建：Vite 构建通过，ECharts 独立于路由懒加载分包。
- 浏览器回归：桌面市场页、个股页、AI 侧栏、AI 报告、登录页与移动市场页均已截图核验。
- 质量检查：目标页面无水平溢出、无浏览器控制台错误、无 ECharts 诊断信息。
- 设计同步：Figma 已建立 `00 · Design Foundations` 与 `01 · Product Screens` 两个页面。
