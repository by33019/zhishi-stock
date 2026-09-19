# CHANGELOG.md — 知势平台变更记录

> 本文件记录所有值得关注的变更。
> 格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)；
> 提交信息约定见 `AGENTS.md`（`类型：中文描述`）。
> 条目分类：新增 / 变更 / 修复 / 移除 / 文档 / 工程。
>
> 项目当前处于 V1 开发期，**尚未发布正式版本**（无 git tag），因此按日期分段记录。
> 任务编号（如 M1-10）对应 `TASKS.md`。

---

## [未发布]

### 移除

- 移除未使用的 `element-plus` 依赖：全仓库零引用，且与自建设计系统定位冲突（M1-10）

### 文档

- 补建根 `README.md`、`backend/README.md`、`frontend/.env.example`、本文件（M1-09 / M1-13）
- 统一文档中的 Compose 调用方式说明（M1-15）

---

## 2026-09-19 — M2-03 成交趋势（MKT-04）

### 新增

- **MKT-04 接口** `GET /api/v1/markets/{marketCode}/turnover-trend`（PUBLIC，参数 `range`（默认 `TODAY`）、`interval`（仅分钟档可用，默认 `1m`）），返回 `marketCode`、`range`、`interval`、`unit`、`dataCutoffAt`、`points[]`
- **粒度枚举** `TurnoverRange`（`TODAY` 1 个交易日 / 分钟粒度、`5D` 5 个交易日 / 日粒度、`20D` 20 个交易日 / 日粒度；`fromCode` 大小写不敏感）
- **响应体** `TurnoverTrend`：`Unit{tradeAmount:"CNY", tradeVolume:"SHARE"}` 显式声明单位，`Point{time, tradeAmount, tradeVolume}` 数值一律字符串（避免前端精度丢失）
- **领域端口** `TurnoverTrendProvider` 与应用服务 `TurnoverTrendQueryService`（`range` / `interval` 校验在用例层，非法值抛 `InvalidTurnoverParameterException` → 400 `INVALID_REQUEST`）
- **模拟数据源** `SimulatedTurnoverTrendProvider`：按交易日历确定性生成分钟 / 日序列，**全程整数运算**（避开 `Math.sin` / `Math.exp` 的跨平台 1 ulp 差异）
- `TradingCalendarDay.lastSessionEnd()`（与既有 `firstSessionStart()` 对称），供生成器取收盘时刻而不硬编码 15:00
- 设计文档 `docs/superpowers/specs/2026-09-19-turnover-trend.md`

### 变更

- `MarketController` 构造函数新增 `TurnoverTrendQueryService` 依赖；`range` / `interval` 声明为 `String`，避免 Spring 把"取值不在白名单"转成 `MethodArgumentTypeMismatchException` 而混淆语义
- `GlobalExceptionHandler` 新增 `InvalidTurnoverParameterException` → 400
- `BackendConfiguration` 新增 `TurnoverTrendProvider` / `TurnoverTrendQueryService` Bean
- 前端 `src/types/domain.ts` 新增 `TurnoverRange` / `TurnoverTrendUnit` / `TurnoverTrendPoint` / `TurnoverTrend`

### 说明

- **点位 `time` 取「区间结束时刻」**：首个点为 `09:31`（`1m` 档），因此 `dataCutoffAt` 天然等于最后一个点的时间，无需额外推导
- **只返回已走完的区间**：`interval=5m` 在 `10:02` 只返回 6 个点（截至 `10:00`），不返回半截区间
- **`range=TODAY` 点位是累计值**：从开盘累计到该时刻，曲线单调不减；累计值按 `全天总量 × 累计权重 / 总权重` 计算而非逐项相加，避免截断误差累积
- **往返一致性**：今日曲线终点 == 同一天在 `5D` / `20D` 日粒度上的点（单测断言），保证分钟与日两套视图不会互相矛盾
- 分钟网格为连续竞价两段 `09:30–11:30`、`13:00–15:00`（共 240 分钟），集合竞价并入相邻点
- **日粒度起点排除仍在运行的当日**（盘中查 `5D`，最后一天是上一交易日），避免出现"半天数据"的伪日线
- 日粒度档位传 `interval` → 400（不静默忽略），避免调用方误以为参数生效
- **不落库**：`stock_minute_bar` / `stock_kline_day` 是证券级表，市场级聚合代价过大；个股级落库归 M2-05

---

## 2026-09-19 — M2-02 市场广度（MKT-03）

### 新增

- **MKT-03 接口** `GET /api/v1/markets/{marketCode}/breadth`（PUBLIC，可选 `snapshotTime`），返回 `marketCode`、`riseCount`、`fallCount`、`flatCount`、`suspendedCount`、`limitUpCount`、`limitDownCount`、`totalCount`、`dataTime`、`dataStatus`、`lastSuccessfulSyncAt`、`snapshotVersion`
- **限幅规则领域模型** `LimitRule`（镜像 `stock_limit_rule` 表，自带 `limitUpPrice` / `limitDownPrice`，四舍五入到分）与端口 `LimitRuleProvider`
- **规则匹配器** `LimitRuleMatcher`：静态属性全等 → 生效窗口 → 上市天数窗口 → `priorityNo` 最小 → `ruleCode` 字典序，保证结果确定性
- **广度计数器** `BreadthCalculator`：六类归类优先级（停牌 → 涨停 → 跌停 → 上涨 → 下跌 → 平盘），`limitUpCount ⊆ riseCount`、`limitDownCount ⊆ fallCount`；涨跌停**按价格而非比例**判定
- **个股行情领域模型** `SecurityQuote` 与端口 `SecurityQuoteProvider`
- **响应体** `MarketBreadth`（`totalCount` 派生自四态之和）
- **应用服务** `MarketBreadthQueryService`
- **模拟数据源** `SimulatedLimitRuleProvider`（10 条可核实的稳定规则：主板 ±10% / ST ±5%、创业板与科创板 ±20%、北交所 ±30%）、`SimulatedSecurityQuoteProvider`（5149 只确定性个股行情，按目标状态反推价格，与计数器构成往返一致性）
- 设计文档 `docs/superpowers/specs/2026-09-19-market-breadth.md`

### 变更

- **市场广度不再是硬编码数字**：`SimulatedQuoteProvider` 改为按限幅规则对整批个股行情计数（原 `2876 / 1924 / 164 / 82 / 7` → 实测 `2976 / 1915 / 209 / 49 / 46 / 43`）
- `MarketOverview.BreadthData` 新增 `suspendedCount`，并派生 `totalCount()`（标 `@JsonIgnore`：快照需持久化往返，派生字段不落盘，避免归档里出现第二个真相）
- `MarketOverviewArchive` 新增 `findAt(marketCode, snapshotTime)` 默认方法；`JdbcMarketOverviewArchive` 实现之，走已有索引 `idx_market_overview_latest`
- `MarketOverviewQueryService` 新增带 `snapshotTime` 的重载（时间回溯）；原 `getOverview(marketCode)` 行为逐字不变
- `MarketController` 构造函数新增 `MarketBreadthQueryService` 依赖
- `BackendConfiguration` 新增 `MarketBreadthQueryService` / `LimitRuleProvider` Bean，`QuoteProvider` 改为注入 `LimitRuleProvider`
- 前端 `src/types/domain.ts` 的 `BreadthData` 补 `suspendedCount`（同步 MKT-01 响应新增字段），`mockApi.ts` 与 `MarketOverview.test.ts` 的固定数据同步补齐

### 说明

- **不编码"新股上市首日不设涨跌幅"**：该条款随板块与时期变化且各板块表述不一致，无法核实到可写进代码的程度。模型与匹配器保留了 `noPriceLimit` 与上市天数窗口能力并用合成规则单测覆盖，待规则真正入库时无需改动匹配逻辑
- 规则缺失时**不计入涨跌停**，但仍按价格计入涨/跌/平——把"无规则"当成"不限幅"会把一只 10% 上涨的普通股算成涨停，是更严重的错误

---

## 2026-09-19 — M2-01 交易日历与市场状态（MKT-02）

### 新增

- **MKT-02 接口** `GET /api/v1/markets/{marketCode}/status`（PUBLIC，可选 `date` 查询参数），返回 `marketCode`、`tradeDate`、`isTradingDay`、`sessionStatus`、`currentSession`、`nextSessionAt`、`calendarSourceTime`
- **交易日历端口** `TradingCalendarProvider`（`stock-market/domain`）与模拟实现 `SimulatedTradingCalendarProvider`（`stock-integration/market`）：周一至周五为交易日，节假日集合由配置项 `stock.market.holidays` 注入（不硬编码未经核实的法定节假日日期），支持前后交易日推导
- **时段模型** `MarketSessionStatus`（5 值粗粒度：`PRE_OPEN` / `CALL_AUCTION` / `TRADING` / `BREAK` / `CLOSED`）与 `TradingSession`（7 值细粒度，含 `OPENING_CALL_AUCTION`、`MORNING_CONTINUOUS`、`LUNCH_BREAK`、`AFTERNOON_CONTINUOUS`、`CLOSING_CALL_AUCTION`），映射关系只在 `TradingSession` 枚举维护一处
- 领域记录 `TradingCalendarDay`（含嵌套 `Window`）、`MarketStatus`；应用服务 `MarketStatusQueryService` 与异常 `MarketNotFoundException`
- 配置项 `stock.market.holidays`（`MARKET_HOLIDAYS` 环境变量）
- 设计文档 `docs/superpowers/specs/2026-09-19-market-status-and-calendar.md`

### 变更

- **枚举统一**：删除 `MarketOverview.SessionStatus`，MKT-01 与 MKT-02 共用 `MarketSessionStatus`。**MKT-01 的 JSON 输出逐字不变**（仍为 `TRADING` / `CLOSED` / `BREAK`），前端类型仅放宽取值范围
- `MarketController` 构造函数新增 `MarketStatusQueryService` 依赖
- `GlobalExceptionHandler` 新增 `MarketNotFoundException` → 404 `MARKET_NOT_FOUND`、`MethodArgumentTypeMismatchException` → 400 `INVALID_REQUEST`
- 前端 `src/types/domain.ts` 新增 `MarketSessionStatus` / `TradingSession` 类型，`MarketOverview.marketStatus` 改用前者

### 修复

- **契约测试日期序列化失真**：独立 `MockMvc` 的默认 `ObjectMapper` 未注册 `JavaTimeModule`，且 `Jackson2ObjectMapperBuilder` 默认不关闭 `WRITE_DATES_AS_TIMESTAMPS`（该开关由 Spring Boot 自动配置打开），导致 `LocalDate` 被序列化成 `[2026,9,11]`。契约测试改为显式构造关闭该开关的 mapper，使断言对齐真实线上格式

### 工程

- **分支收拢**：`auth-market-vertical-slice` 已完全合入 `main`，本地与远端分支一并删除，远端仅保留 `main`；`.worktrees/` 工作树清理完毕
- 后端测试 45 → **71**（新增 `MarketStatusQueryServiceTest` 16 项、`SimulatedTradingCalendarProviderTest` 7 项、契约测试 3 项），全绿；前端 typecheck 0 错误、35 测试全绿

---

## 2026-09-19 — 工程地基与全栈端到端验收

### 新增

- **CI 工作流**（`.github/workflows/ci.yml`）：3 个作业（backend / frontend / sql），push 与 PR 触发，并发运行自动取消旧任务
- **旧库升级路径**：`compose.legacy.yaml`（initdb 导入旧库 + Flyway baseline）与 `sql/tools/slim_legacy_dump.py`（可复现裁剪工具，仅标准库、幂等）
- **任务载体**：`TASKS.md`（任务唯一真相源）、`PROJECT_STATUS.md`（状态快照）
- **交付路线图**：`docs/superpowers/plans/2026-09-19-mvp-delivery-roadmap.md`（M1 / M2 / M3 共 32 个任务）
- 迁移后校验脚本 `sql/checks/post_migration_validation.sql` 补入 V8 的 `market_overview_snapshot`（期望表 39 → 40，修复契约漂移）
- Maven 国内镜像配置 `backend/settings.xml`（阿里云公共仓库）

### 变更

- 旧库样本 `sql/stock_db.sql` 由 **24,243,860 字节裁剪为 149,480 字节（0.6%）**：保留全部 DDL 与 RBAC / 日志数据，仅对 `stock_rt_info`、`stock_market_index_info`、`stock_block_rt_info` 三张大表采样

### 修复

- **容器内依赖下载中断导致镜像构建随机失败**。根因：容器网络在高并发 HTTPS 下载下存在约 **3% 偶发连接中断**（已排除 MTU 与链路本身，单文件可完整下载）。一次构建需拉取数百个构件，累积失败率接近必然。
  - 后端：阿里云镜像 + Maven wagon 重试 `count=5`（`backend/Dockerfile`）
  - 前端：国内 npm 镜像 + 限制并发 `--maxsockets=5` + 拉长超时 + 3 次重试，且失败时不清理 npm 缓存以支持增量续传（`frontend/Dockerfile`）
- 完善 MySQL 连接参数（`allowPublicKeyRetrieval=true`）与市场总览验收选择器

### 验收

- 后端 **45 测试全绿**（Testcontainers 真实 MySQL 8.4 + Redis）；前端 **35 测试全绿**
- 数据库**空库与旧库升级两条路径均通过**：旧库路径 `baseline v1` → 应用 V2–V8 → `now at version v8`，`foreign_key_count = 0`，41 张表
- **全栈 Compose 端到端验收通过**：`npm run e2e:real` 覆盖市场 API、登录、Cookie 恢复、退出与路由保护

---

## 2026-09-13 ~ 09-14 — 首个纵向切片（认证 + 市场总览）

### 新增

- 后端基础工程：Maven 多模块 6 个（`stock-common` / `stock-system` / `stock-market` / `stock-integration` / `stock-backend` / `stock-job`）
- 认证会话闭环：JWT access token（前端内存）+ opaque refresh token（httpOnly / SameSite=Strict cookie，Redis 轮换）
- 市场总览纵向闭环：`SimulatedQuoteProvider` 确定性模拟行情 → Redis 缓存 → MySQL 快照
- 本地基础设施编排：`compose.yaml`（mysql → flyway → stock-api / stock-job → frontend nginx 反代 `/api/`）
- 前端登录页与市场总览**接入真实 API**（其余 9 个页面仍走 `mockApi`）
- 数据库迁移 Flyway **V1–V8**

### 修复

- 加固认证与纵向闭环契约：统一业务 ID 为 Snowflake 字符串（前端全程 string，避免精度丢失）、完善全局异常处理与统一返回壳、收紧安全配置

---

## 2026-09-09 ~ 09-10 — 前端设计系统与登录页

### 新增

- 前端项目基线（Vue 3.5 / TypeScript 6 / Vite 8 / Vue Router / Pinia / ECharts）
- 全局字体层级体系与可读性浏览器回归
- 登录页一体化重构：统一内容结构、协议语义与移动端边界

### 文档

- 前端字体层级优化规范、任务拆解与验证记录
- 登录页一体化重构方案与实施步骤

---

## 2026-09-09 — 项目基线

### 新增

- 初始化知势股票平台项目基线
