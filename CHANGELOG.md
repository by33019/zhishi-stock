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

## 2026-09-20 — M3-06 AI Provider 抽象 + 确定性模拟实现（第 8 个模块 `stock-ai`）

### 新增

- **`backend/stock-ai`**（后端第 8 个模块）：AI 域从「V6 九张表零行零引用」推进到「接口可用、上下文可固化、引用可核对」。
  - `domain/`：`AiSceneCatalog`（5 个场景的静态规则表）、`AiContextBuilder`（一次取数固化成快照与证据候选）、
    `LlmProviderPort` / `AiContentHasher` 两个端口、8 个枚举与 13 个值类型。
  - `application/`：`AiContextPreviewService`（AI-02 用例：场景 → 区间 → 目标矩阵 → 取数 → 预览）。
  - **`LlmEvidence` 类型上不含 URL**——把「模型不生成可信 URL」做成类型保证，而不是事后校验。
- **`stock-integration/ai/SimulatedLlmProvider`**：确定性模拟 LLM。同一请求逐位相同；六章节按序切片产出；
  引用编号只取任务固化的证据候选集合内；用量按字符数估算；
  故障注入 `NONE` / `INVALID_CITATION` / `TIMEOUT` / `RATE_LIMIT`。
- **`stock-integration/ai/SimulatedContentHasher`**：确定性内容哈希
  （`hashOf(Map)` 用 `TreeMap` 保证与迭代顺序无关）。
- **`stock-news`：`NewsEvidenceProvider` 端口 + `NewsQueryService` 实现**——关闭 M3-04 留下的
  `news_source.allow_ai_analysis` 消费侧欠账；复用既有 `visible()` 过滤链，AI 侧只叠一层来源授权；
  `limit` 在**过滤之后**截断。
- **`stock-backend/web/AiController`**：AI-01 `GET /api/v1/ai/scenes`、AI-02 `POST /api/v1/ai/context-previews`，
  均 `USER` 权限。
- 测试：`stock-ai` 71 项、`NewsEvidenceQueryTest` 14 项、`SimulatedLlmProviderTest` 16 项、
  `AiControllerContractTest` 8 项、`SecurityConfigurationTest` +1 项。

### 变更

- `BackendConfiguration` 新增 5 个 Bean；`NewsEvidenceProvider` **复用**已有的 `newsQueryService` Bean
  （不新声明，避免「哪些资讯可见」出现两份实现）。
- `application.yml` 新增 `stock.ai.provider-code` / `model-code` / `news-evidence-limit` 三项。
- `stock-backend` 与 `stock-integration` 的 `pom.xml` 新增 `stock-ai` 依赖；
  根 `pom.xml` 的 `<modules>` 注册 `stock-ai`。
- `AiContextBuilder` 的市场代码常量删除，改用资讯域 `NewsMarketTargets.CN`（同一事实只允许一处定义）。

### 修复

- **上下文哈希对数据时间不敏感**：`contextData` 漏了 `dataTime`，两个不同批次的同一只证券会被判成「同一份事实」。
- **`InvalidAiContextQueryException` 业务码错误**：区间/场景不合法曾被报成 `AI_TARGET_INVALID`，改为 `INVALID_REQUEST`。
- 三处「空结果也能通过」的断言补了对照组或数量前置（详见 `TASKS.md` 的 M3-06 交付详情）。

### 文档

- 新增 `docs/superpowers/specs/2026-09-20-ai-provider.md`（设计文档，含改动清单与验收结果回填）。

---

## 2026-09-20 — M3-05 前端 `/news` 接真实资讯接口（mock 通路清零）

### 新增

- **`frontend/src/services/newsApi.ts`**：`getNews(query)` → NEWS-01（列表）、`getNewsOptions()` → NEWS-04（受控筛选项）。
- **`frontend/src/pages/NewsPage.test.ts`**：18 项用例——首屏请求数、筛选走服务端、关键字显式提交、
  翻页保留条件、原文链接的三种授权状态、关联标签跳转、空列表的两种语义、侧栏降级、错误态。
- **`frontend/src/types/domain.ts`**：新增资讯域契约类型 `NewsType` / `NewsTargetType` /
  `NewsOriginalAccessStatus` / `NewsRelationMethod` / `NewsRelationSummary` / `NewsSummary` /
  `NewsPage` / `NewsTimeRange` / `NewsOptions` / `NewsQuery`。
- **`frontend/e2e/news.real.mjs`**：真实端到端核对脚本（Playwright + Docker 全栈，手动跑）。

### 变更

- **`frontend/src/pages/NewsPage.vue`** 从 `mockApi.getNews()` 改为真实接口：
  - 筛选与分页**全部走服务端**（`newsTypes` / `keyword` / `page` / `size`），不再用 `computed` 客户端过滤。
  - **类型标签由 NEWS-04 的 `newsTypes` 动态生成**，前端只保留「枚举值 → 中文名」的显示映射，
    未知取值回退为原值——服务端新增一种资讯类型时前端会自动多一个标签。
  - 关键字改为**显式提交**（回车 / 点搜索），与 M3-03 的选股面板同一套约定。
  - 原文链接按 `originalAccessStatus` 渲染：`UNAVAILABLE` 不给链接，`AVAILABLE` 与 `UNKNOWN` 都给
    （后者标注「（原文状态未知）」）——理由见下方「修复」。
  - 关联标的渲染为可跳转标签：`SECURITY` → `/stocks/:id`、`SECTOR` → `/sectors/:id`，
    `MARKET` 与空 `targetId` 只渲染文本。
  - `data-time` 展示真实 `lastSuccessfulSyncAt`；`DELAYED` 单独给「最近有效快照」提示条，
    `UNAVAILABLE` **不挂**该提示（那会编造一次并不存在的快照）。
  - 侧栏「今日事件密度」「高频主题」两块**降级为「尚未实现」**，删掉原型里的 `286` / `72%` / `42` 等编造数字。
  - 时间筛选按钮置 `disabled` 并在 `title` 写明归属（NEWS-01 已支持 `startAt` / `endAt`，但原型无设计稿）。
- **`frontend/src/types/domain.ts`**：`NewsItem` 保留并补注释——它是 MKT-01 首页快讯
  （`relatedSymbols: string[]`），与资讯域的 `NewsSummary`（`relations[]`）不是同一形状，
  合并不掉（合并会让「总览页的资讯从哪来」看不出来）。

### 移除

- **`frontend/src/services/mockApi.ts`** 与 **`mockApi.test.ts`** 整体删除——`/news` 是它最后一个消费者。
  仓库里从此不再有 mock 通路。

### 修复

- **`originalAccessStatus` 一律是 `UNKNOWN`，导致「查看原文」全部消失**（e2e 发现，本轮已修）：
  模拟源没有真实原文，`NewsIngestionService` 把该列硬编码为 `UNKNOWN`。初稿的规则是「只有 `AVAILABLE`
  才渲染链接」，于是真实环境下 8 条资讯**一个链接都没有**——原型里的外链功能完全不可见。
  按契约 §4.3 分开理解：`originalUrl` 是「经协议和安全校验的原文地址」，`originalAccessStatus`
  描述的是**内容**可访问性。改为 `UNKNOWN` 也给链接并标注状态未知；复验后 8 条全部可点。

### 已知问题

- **新增 #21**：`GET /news/options` 的 `availableTimeRange` 多出契约外的 `empty` 字段
  （`NewsTimeRange.isEmpty()` 是**无参** `isXxx()`，被 Jackson 当 getter）。修法是加 `@JsonIgnore`（一行），
  本轮不改（M3-05 是前端里程碑，前端类型不认该字段、功能无影响）。
- **#20 的前端处置已定**：`latestNewsCount` 为 `null` 时**不渲染**该字段，不渲染成「0 条」；
  后端语义留到有真实资讯源之后决定。

### 验证

- `npm run typecheck` 通过；`npm run test` **20 文件 / 150 项通过**（新增 18 项、删除 1 项）；`npm run build` 通过。
- **真实端到端联调**（Docker 全栈 + Playwright 打开真实 `/news` 页，脚本 `frontend/e2e/news.real.mjs`）：
  首屏**恰好 2 个请求**；类型标签 5 个全部来自 NEWS-04；切换类型**只新增 1 个请求**且带 `newsTypes`
  （options 累计仍 1 次）；关键字搜索**保留当前筛选条件**（`newsTypes=ANNOUNCEMENT&keyword=银行`）；
  关联标签里 `CN` 无链接、`002343` → `/stocks/sim-002343`；侧栏渲染「尚未实现」；**原文链接 8 条全部可点**。

---

## 2026-09-20 — M3-04 资讯域（第 7 个模块）+ 定时采集落库

### 新增

- **新模块 `backend/stock-news`**（50 个主代码 + 7 个测试文件）：
  - `domain` 36：7 个枚举（`NewsType` / `NewsContentStatus` / `NewsOriginalAccessStatus` /
    `NewsDedupStatus` / `NewsRelationStatus` / `NewsRelationMethod` / `NewsTargetType`）、
    `NewsSource` / `NewsArticle` / `NewsRelation` / `NewsDetail` / `NewsRecord`、
    `NewsSummary` / `NewsRelationSummary` / `NewsPage` / `NewsOptions` / `NewsSyncStatus`、
    三个纯函数（`NewsFingerprint` / `NewsDeduplicator` / `NewsRelationResolver`）、
    六个端口（`NewsProvider` / `NewsArticleStore` / `NewsSourceStore` / `NewsRelationStore` /
    `RelationCatalogProvider` / `NewsCountProvider`）
  - `application` 5：`NewsIngestionService`（采集 + 去重 + 关联 + 落库）、
    `NewsQueryService`（六个查询接口的业务规则，**兼作** `NewsCountProvider`）
  - `infrastructure` 9：三套 `Mapper` + `Row` + `MyBatis*Store`
- **六个契约接口**（全部 PUBLIC）：NEWS-01 `GET /news`、NEWS-02 `GET /news/{newsId}`、
  NEWS-03 `GET /news/sync-status`、NEWS-04 `GET /news/options`、
  STK-10 `GET /securities/{securityId}/news`、SEC-07 `GET /sectors/{sectorId}/news`。
  **六个端点写在同一个 `NewsController` 里**：STK-10 / SEC-07 的路径虽挂在 `securities` / `sectors` 下，
  但资源属资讯域，写进行情侧控制器会让 Web 层反向依赖资讯域
- `integration/news/SimulatedNewsProvider`、`SimulatedRelationCatalogProvider`（确定性模拟源 + 关联目录）
- `market/domain/SectorIdentity(Provider)`、`integration/market/SimulatedSectorIds`、
  `SimulatedSectorIdentityProvider`：板块侧身份桥接（关联表 `target_id` 统一为 bigint，与证券侧同因同形）
- `stock-job/ScheduledNewsCollector`：`@Scheduled` fixedDelay 2 分钟（`stock.news.collect-delay-ms`）

### 变更

- `backend/pom.xml` 加第 7 个模块 `stock-news`；`stock-integration` / `stock-backend` / `stock-job` /
  `stock-system` 各加 `stock-news` 依赖；`stock-job` 另显式声明 `mybatis-plus-spring-boot3-starter`
- **`stock-job` 新增 `@MapperScan(basePackages = "cn.zhishi.stock.news", annotationClass = Mapper.class)`**：
  漏掉它整个应用起不来（`No qualifying bean of type ...Mapper available`），
  而报错指向配置类、不指向扫描范围；`annotationClass` 也不能省，否则资讯域的三十多个领域端口接口
  会被 MyBatis 注册成 Mapper
- `integration/market/SimulatedHashing` 的 `SplitMix64` 放开为 `public`（资讯 Provider 复用，**不新写第二份哈希**）
- `integration/market/SimulatedSectorProvider` 改为投影自 `SecurityMasterProvider`
- `WatchlistItemService.overview(...)` 新增 `newsSince` 参数并接 `NewsCountProvider`；
  `WatchlistOverviewController` 删掉 `newsSince` 的占位 400 分支改为透传
- `NewsSyncStatus` 新增 `dataStatus()`：`OK→REALTIME / DEGRADED→DELAYED / UNAVAILABLE→UNAVAILABLE`。
  **刻意不产出 `STALE`**——资讯没有定义"多久算陈旧"的阈值，没有阈值就不编造

### 修复

- **`NewsQueryService.validateKeyword` 此前从未被调用**：keyword 长度上限 50 形同虚设，
  超长关键词被当成正常筛选条件照常执行，且没有任何测试会变红。已在唯一出口 `page(NewsQuery)` 接上
  （e2e 实测：51 字符关键词返回 400）
- `WatchlistItemService` 的 `NEWS_NOT_IMPLEMENTED` 占位说明（`limitations` 里的假条目）已删除
- `NewsCountProvider` 与 `WatchlistItemService` 的注释此前声称"0 条"与"不知道"可区分，
  但实现里**没有任何代码路径产生"未知"**——注释改为陈述事实并指向已知问题 #20（**行为不变**）

### 已知问题

- **新增 #20**：WAT-11 的 `latestNewsCount` 无法表达"0 条"（M3-04 e2e 实测，本轮记录不修）
- **#8 部分关闭**：SEC-07 板块资讯已交付，仅剩 SEC-05 板块走势
- **#10 更新**：M3-03 自选页已不再依赖 STK-05

### 验证

- `mvn verify` 与 `TZ=UTC mvn test` 均 **BUILD SUCCESS**，8 个模块全绿
- 后端 **639** 用例（`stock-news` 89 / `stock-backend` 181 / `stock-job` 12，由 2 扩到 12）；
  前端 133 用例未回归（本轮未改前端）
- `InfrastructureIntegrationTest` 新增 `@Nested News` 7 项，**本机 Docker 真实 MySQL 8.4 实测通过**
- **真实端到端联调**：Docker 起 MySQL 8.4 + Redis 8.2 + `stock-api` + `stock-job`，空库 Flyway V1→V8，
  库内数据**全部由真实定时任务采集而来**；第二轮采集零新增行（来源 ID 幂等生效）、
  停用来源的 `last_success_at` 始终 `NULL`、跨来源重复稿被指纹判为 `DUPLICATE` 并折叠到主记录、
  **只有 CANDIDATE 关联的稿件在列表与板块视图中都不可见**

---

## 2026-09-20 — M3-03 前端 `/watchlist` 接真实 API（自选闭环端到端可用）

### 新增

- **`frontend/src/services/watchlistApi.ts`**：WAT-02/03/04/05/07/08/09/10/11 共 9 个端点。
  只拼参，不筛选、不格式化、不做本地排序；`Idempotency-Key` **由调用方传入**
  （键的语义是"一次用户意图"，只有页面知道"重试复用、换 body 换新键"）
- **`frontend/src/services/watchlistApi.test.ts`**：9 项，断言每个端点的 method / 路径 /
  `Idempotency-Key` / `If-Match` / body 形状
- `frontend/src/types/domain.ts` 新增 9 个契约类型：`WatchlistGroup`、`WatchlistItem`、
  `WatchlistOverview`、`CreatedWatchlistItem`、`MovedWatchlistItem`、`WatchlistItemOrder`、
  `DeletedWatchlistItem`、`CreatedWatchlistGroup`、`DeletedWatchlistGroup`

### 变更

- **`frontend/src/pages/WatchlistPage.vue` 全量重写**（约 440 行）：
  - 首屏**只发一次** `GET /watchlists/overview`，分组 + 自选行情 + 市场状态 + 数据状态来自**同一批**快照
  - 切换分组**不产生新请求**，在完整响应内按 `groupId` 选择（侧栏 `itemCount` 与列表行数因此必然自洽）
  - 写操作后**重新拉取**而不是乐观更新：服务端会改写 `sortNo` / `version`，WAT-09 还可能删掉源行
  - 分组重排 / 组内重排用 HTML5 拖放，**用组件状态传下标而非 `dataTransfer`**（jsdom 里不存在，
    靠它传数据会让排序无法被测试）
  - 导语的涨 / 跌 / 平只数由真实行情算出；**停牌单独计数**且不计入涨跌分母
  - 选股面板走 STK-01，显式提交而非输入即搜
- `frontend/src/pages/WatchlistPage.test.ts` 重写为 **25 项**（原 19 项里 2 个断言写错已改，另新增 6 项）
- `frontend/src/styles/business.css`：新增分组菜单 / 分组名表单 / 卡片操作组 / 提示条样式
- `frontend/README.md`：路由表的数据来源列与实际对齐（此前只标了 `/market` 与 `/login`，
  漏掉 M2-08~M2-11 接入的 4 个页面）

### 修复

- **空自选时页面在编造一次不存在的快照**：后端在没有任何自选项时**不发起整批取数**，
  返回 `dataStatus = 'UNAVAILABLE'` / `snapshotVersion = ''` / `dataTime = null`；
  旧逻辑 `dataStatus !== 'REALTIME'` 为真，于是在空列表上挂出"当前展示最近有效快照（数据截止 --）"。
  **新注册用户的第一屏就是这个**。改为提示条只在"当前分组真的有卡片"时出现
- **停牌股被算成"平盘"**：真实数据里 `sim-300750` 是 `isSuspended: true` 但 `quote` **非空**、
  `changeRate` 为 `"0.0000"`，按数值算会落进"平盘"，而它今天根本没有价格发现。
  判据改为 `security.isSuspended`，并把"快照里没有涨跌幅"一并归入"无有效行情"一档，
  使 `共 N = 上涨 + 下跌 + 平盘 + 无有效行情` 恒成立
- 移除 `WatchlistPage.vue` 里未使用的 `Search` 图标导入（`vue-tsc` 报 TS6133）

### 移除

- **`frontend/src/services/mockApi.ts` 移除 `rankingRows`**（`/watchlist` 是它最后一个消费者）
  与**已无消费者的 `getMarketOverview()`**（最后一个调用方在 M2-08 总览页接上 MKT-01 时消失，
  此后只剩自己的测试在读）。该文件现在只剩 `getNews`，归 M3-05 整体删除
- `business.css` 删除 `.watch-sparkline`；自选卡片不再渲染分时 sparkline 与 `latestNewsCount`
  （契约没有批量 K 线接口、资讯 Provider 未就位，画出来就是编造）

### 文档

- 新增 `docs/superpowers/specs/2026-09-20-watchlist-page.md`（含 §8 验收结果与 e2e 实测发现）

---

## 2026-09-20 — M3-02 自选项 CRUD + 排序 + 行情概览（自选中心后端 12 接口全通）

### 新增

- **WAT-06~WAT-12 自选与聚合接口**：`/api/v1/watchlist-groups/{groupId}/items` 的列表 / 新增 / 删除 / 移动 /
  重排，以及 `/api/v1/watchlists/overview`（聚合）与 `/api/v1/watchlists/membership`（单值 Map）
- `stock-market/domain` 新增 `SecurityIdentity`、`SecurityIdentityProvider`：
  契约与前端用字符串 `securityId`（`sim-600519`），库里 `user_watchlist_item.security_id` 是 `bigint`，
  两者的映射收在一个端口里而不是散在自选模块
- `stock-integration/market` 新增 `SimulatedSecurityIds`（**唯一**的构词规则定义）与
  `SimulatedSecurityIdentityProvider`（投影自 `SecurityMasterProvider`，5149 只全量往返有测试）
- `stock-system/watchlist` 新增 `WatchlistItem` / `WatchlistItemRow` / `WatchlistItemRepository` /
  `MyBatisWatchlistItemRepository` / `WatchlistItemMapper` / `WatchlistItemService` /
  `WatchlistEntry` / `WatchlistOverview` / `WatchlistMembership` / `MovedItem`
- `stock-backend` 新增 `WatchlistItemController`、`WatchlistOverviewController`
- 测试：`SimulatedSecurityIdentityProviderTest`(5)、`WatchlistItemServiceTest`(32)、
  `MyBatisWatchlistItemRepositoryTest`(6)、`WatchlistItemControllerContractTest`(19)、
  `WatchlistOverviewControllerContractTest`(10)、`InfrastructureIntegrationTest#WatchlistItems`(9)
- 设计文档 `docs/superpowers/specs/2026-09-20-watchlist-items.md`

### 变更

- `SimulatedSecurityQuoteProvider` / `SimulatedSectorProvider` 里两处手工拼接 `"sim-" + code`
  改为调用 `SimulatedSecurityIds.securityIdOf(...)`，构词规则不再靠注释维持"两处一致"
- `stock-system` 新增对 `stock-market` 的编译依赖（只用到 `domain` 包，无环）
- `WatchlistGroupController` 的 `includeItems=true` 由"显式 400"改为返回真实自选项，
  并用 `@JsonInclude(NON_NULL)` 保证 `includeItems=false` 时响应与 M3-01 **逐字节一致**
- `WatchlistItemService` 的 `createdAt` 改为**应用显式写入**（`LocalDateTime.now(clock)`）并按同一个
  `Clock` 的时区回读——与 M3-01 "干脆不读 `created_at`"的取舍相反，因为 WAT-06 要求回显

### 修复

- 关闭已知问题 #15（`includeItems=true` 曾返回 400，因为返回 `items: []` 会编造"这个分组里没有股票"）
- 契约测试补上 `Jackson2ObjectMapperBuilder + featuresToDisable(WRITE_DATES_AS_TIMESTAMPS)` 的
  消息转换器：独立 `MockMvc` 的默认 Jackson 会把 `OffsetDateTime` 序列化成 epoch 数字
  （与已知问题 #5 同源，但 #5 只记了 `LocalDate` → 数组），会让时间断言静默失真
- `TASKS.md` 补上 M3-01 漏勾的清单项（上一轮只加了"交付详情"段）

### 文档

- 新增已知问题 #16（幂等键并发非严格互斥）、#17（全站无任何限流）、#18
  （`WATCHLIST_ITEM_EXISTS` 无端点会抛）到 `PROJECT_STATUS.md` §6；#15 标记为已关闭
- 更正 `TASKS.md` 里 surefire `@Nested` 报数的说明：控制台会把**外层类的用例并进第一个 `@Nested` 那行**
  （实测 `$WatchlistItems: 14` = 外层 5 + 自己 9，`$WatchlistGroups: 10`，外层 `0`），
  统计总数必须以 `target/surefire-reports/TEST-*.xml` 为准

---

## 2026-09-20 — M3-01 自选分组 CRUD（V5 两张表首次被代码引用）

### 新增

- **WAT-01~WAT-05 自选分组接口**（`/api/v1/watchlist-groups`，`USER`）：列表、新建、改名、软删（可搬移自选项）、原子重排
- `stock-system` 新增 `watchlist` 包：`WatchlistGroup` / `WatchlistGroupName` / `WatchlistGroupRepository` /
  `MyBatisWatchlistGroupRepository` / `WatchlistGroupMapper` / `WatchlistGroupService` /
  `WatchlistGroupRow` / `CreatedGroup` / `DeleteResult` / `WatchlistErrorCode` / `WatchlistException`
- `stock-system` 新增 `idempotency` 包（**可复用**，契约里有 8 个接口要求 `Idempotency-Key`）：
  `IdempotencyStore` / `IdempotencyRecord` / `RedisIdempotencyStore` / `IdempotencyGuard` /
  `IdempotencyKeyConflictException` / `IdempotencyKeyMissingException`
- `stock-backend` 新增 `WatchlistGroupController`、`IfMatch`（共用的 `If-Match` 解析）、`InvalidIfMatchException`
- `DevelopmentAccountSeeder` 调用 `createDefaultGroup`，让 dev / test 数据与契约 §12.3 的"注册后恰好一个默认分组"一致

### 变更

- `StockBackendApplication` 的 `@MapperScan` 由写死 `cn.zhishi.stock.system.auth` 放宽为
  `cn.zhishi.stock.system`（仍限定 `annotationClass = Mapper.class`），新增 Mapper 不必再回来改这里
- `stock-system` 显式声明 `spring-boot-starter-json`（`IdempotencyGuard` 需要 Jackson），
  与 `stock-market` 已有的做法一致，而不是蹭传递依赖
- `GlobalExceptionHandler` 新增 4 个处理方法：`WatchlistException`（业务码与状态由异常自身携带）、
  `IdempotencyKeyConflictException`（409）、`IdempotencyKeyMissingException`（400）、`InvalidIfMatchException`（400）

### 修复

- 修复 V5 迁移自 M1 起"结构就绪、无代码引用"的状态：`user_watchlist_group` / `user_watchlist_item`
  两张表（含两个生成列与全部唯一索引 / CHECK）现在真正被应用层使用
- `BackendConfigurationTest` 补上 `WatchlistGroupMapper` 的 mock 与 4 个新 Bean 的装配断言——
  该测试用 `ApplicationContextRunner` 单独装配配置类，不走 `@MapperScan`，缺 mock 时上下文起不来

### 文档

- 新增 spec `docs/superpowers/specs/2026-09-20-watchlist-groups.md`
- 记录 surefire 对 `@Nested` 的报数怪癖：控制台会出现 `Tests run: 0` 与聚合行并存，
  统计总数必须以 `target/surefire-reports/TEST-*.xml` 的 `<testcase>` 为准

---

## 2026-09-20 — M2-11 总览板块预览真实化（关闭已知问题 #14）

### 新增

- `QuoteBatch` 从 `market.application` 下沉到 `market.domain` 并公开（自 `application` 包移入）：第 4 个消费方是摄入侧的 `SimulatedQuoteProvider`，它够不到一个包私有的上层类，而"缺快照的成分如何处理"这条口径不该抄第 4 遍
- `SimulatedQuoteProvider` 新增 `SectorProvider` 依赖；便捷构造新增 `SimulatedSources`，让整批快照源与板块源**共用同一份** `SecurityMasterProvider`（两者各持一份主数据而将来某一方换了日历，成分与快照会静默失配）
- `SECTOR_PREVIEW_SIZE = 3`
- 测试：`SimulatedQuoteProviderTest` 新增 `sectorPreviewIdsAreResolvableByTheSectorDetailApi`（预览每行都能被 SEC-03 解析）、`sectorPreviewMatchesTheSectorRankingTopThree`（与 SEC-02 默认口径首页前 3 行逐字段一致）
- 前端测试：`MarketOverview.test.ts` 新增"热点板块卡片用预览行的 `sectorId` 作为跳转主键"、"板块没有可统计行情时不把 `null` 渲染成空白"
- 设计文档 `docs/superpowers/specs/2026-09-20-overview-sector-preview-truth.md`

### 变更

- **总览快照的 `sectors[]` 改为投影自真实板块源**（此前是三个写死的常量）：`SectorProvider.findAll` → `QuoteBatch.ofMembers` → `SectorQuoteCalculator.calculate` → `RankingType.GAINERS.sectorOrder()` 取前 3 名，与 SEC-02 板块排行（默认口径 `GAINERS`）走同一条取数路径
- `SimulatedQuoteProvider.fetch()` 只取一次整批快照，榜单预览与板块预览共用同一批
- `BackendConfiguration.quoteProvider` 注入 `SectorProvider`
- 前端 `OverviewSectorQuote.leadingStock` 改为 `string | null`（后端契约可空），`MarketOverview.vue` 渲染 `?? '--'`（与 `SectorDetailPage.vue` 对同一字段的处理对齐）
- 前端与 e2e 夹具的板块改用真实的 `sim-bk0033` / `BK0033`
- `docs/superpowers/specs/2026-09-20-market-status-truth.md` 的 M2-11 归属行标记为已完成

### 修复

- **首页三张板块卡片点进去全部 404**：预览返回的 `sectorId` 是 `bk-ai` / `bk-chip` / `bk-broker`，而板块源生成的是 `sim-bk0001`…`sim-bk0039`，`GET /sectors/{sectorId}` 直接抛 `SECTOR_NOT_FOUND`。与 M2-06 修掉的 `stock-600519` 同类缺陷
- 总览「热点板块」与「板块分析」页对同一个市场给出不同的热点：预览的 `changeRate` / `companyCount` / `leadingStock` 都是写死的，与 `SectorQuoteCalculator` 的结果对不上，而**两处各自都是"合法"的值，没有任何测试会红**

---

## 2026-09-20 — M2-10 市场状态真实化（关闭已知问题 #7 / #12）

### 新增

- `stock-market/domain/TradingSessions`：纯函数 `latestTradeDate` + `currentSession`，让 MKT-01 摄入、MKT-02 查询、个股/整批快照三条链路共用同一份"今天是哪一天、此刻是哪个时段"的口径
- `stock-integration/.../SimulatedSessionTimes`：收盘时刻从交易日历取（不硬编码 15:00），总览与个股快照共用推导过程、各自保留兜底策略
- `TradingSessionsTest`（8 项）；`SimulatedQuoteProviderTest` 新增 5 项非交易日 / 盘后 / 盘前 / 节假日用例
- 前端 `types/domain.ts` 新增 MKT-02 契约类型 `MarketStatus`（`isTradingDay` 的命名与 Java 字段名不同，靠 `@JsonProperty` 对齐）
- 前端 `composables/useMarketStatus`：60s 定时刷新 + `visibilitychange` 页面不可见时暂停 + 暴露与刷新同节拍的 `now`
- `utils/format.ts` 新增 `formatDate` / `formatTime`（均显式钉 `Asia/Shanghai`）
- 测试：`useMarketStatus.test.ts`（9）、`format.test.ts`（+4）、`AppShell.test.ts`（+4）、`MarketOverview.test.ts`（+7）
- 设计文档 `docs/superpowers/specs/2026-09-20-market-status-truth.md`

### 变更

- **`SimulatedQuoteProvider` 的 `tradeDate` / `marketStatus` / `dataTime` 改由交易日历推导**（此前 `tradeDate = now.toLocalDate()` 完全不看日历、`sessionStatus` 由配置决定）：非交易日回退到最近有效收盘并标 `CLOSED`，`dataTime` 取该交易日的收盘时刻而不是"现在"
- `breadth` 与榜单预览共用同一个 `tradeDate`：此前广度按"今天"算、榜单按"最近交易日"算，非交易日两者相差一天
- `SimulatedMarketAccess.latestTradeDate()` 与 `MarketStatusQueryService.getStatus()` 改为委托 `TradingSessions`（等价搬移，行为不变）
- `stock-job` 新增 `TradingCalendarProvider` Bean 与 `MARKET_HOLIDAYS` 配置：此前它自建了一份**空节假日表**的日历，而 `stock-backend` 用 `stock.market.holidays`——配置了节假日时采集与查询会对"今天是哪一天"给出不同答案
- `compose.yaml` 的共享环境锚点补 `MARKET_HOLIDAYS`（`stock-api` 与 `stock-job` 同时生效）
- **`AppShell.vue` 顶栏的「交易中 14:32」改为消费 MKT-02**：时段进行中显示状态文案 + 当前北京时间；已收盘 / 非交易日只显示状态文案（收盘后显示当前时刻会被读成"数据截止该时刻"，而那时没有数据），`nextSessionAt` 放进 `title`
- **侧栏的「数据链路正常 / 延迟 26 秒」改为真实的交易日历数据源时间**；接口失败时顶栏与侧栏显示"状态未知"并可点击重试，不沿用上一次的文案
- **`MarketOverview.vue` 标注交易日与休市 / 已收盘标记**：修好 #7 之后非交易日展示的是上一交易日收盘数据，不解释清楚会让人以为页面坏了。判据是"快照的交易日是不是今天"——不能用 `dataTime` 与 `tradeDate` 是否同日，后端在非交易日会把 `dataTime` 回退到上一交易日收盘，两者本来就同日
- `market-state` 与 `live-dot` 在非交易时段停止脉动：一个一直在跳的"在线"点本身就在暗示数据在实时更新

### 修复

- **同一份总览快照内部两个字段来自不同交易日**（已知问题 #7）：修复前周日访问 `/markets/overview` 会得到 `tradeDate = 周日`、`marketStatus = TRADING`，而榜单预览来自周五。两处各自都是"合法"值，**没有任何测试会因此变红**
- **顶栏与侧栏的写死值**（已知问题 #12）：这两处出现在除 `/login` 外的每一个页面上
- **总览页的「较昨日 +8.69%」与它自己引用的数据矛盾**：同一份响应里的 `amount` / `previousAmount` 算出来是 +7.25%。已改为按这两个字段计算，`previousAmount` 缺失或为 0 时显示 `--` 而不是编一个涨跌幅
- 总览页写死的「今日市场，温和放量。」与「金融与科技方向形成共振」移除，导语改由真实广度数据拼出——那句固定文案在周日显示时，"今日市场"本身就是错的
- 总览页未接入的「生成市场解读」按钮改为 `disabled` + `title`（可点但无反应比禁用更糟）

### 文档

- `TASKS.md`：M2-10 交付详情（含 7 条取舍与"不在本轮范围"）
- `PROJECT_STATUS.md`：M2 进度 9/9 → 10/10，已知问题 #7 / #12 关闭，接口覆盖率盘点更新（MKT-02 由"已实现但无人消费"变为已消费）
- `backend/README.md`：补 `MARKET_HOLIDAYS` 环境变量说明（采集与查询共用，两边必须一致）

---

## 2026-09-20 — M2-09 全局搜索接真实接口（STK-01）

### 新增

- `services/securityApi.ts` 新增 `searchSecurities(q, limit)`；`limit` 显式传 10 而不依赖后端默认值，避免 PRD「最多 10 条」在别人改默认值时静默失效
- `components/GlobalSearch.test.ts`（13 项）
- 设计文档 `docs/superpowers/specs/2026-09-20-global-search.md`

### 变更

- **`components/GlobalSearch.vue` 重写**：输入即查 `GET /securities/search`，300ms 防抖；`↑`/`↓` 移动、`Enter` 选中、`Esc` 收起、`⌘K`/`Ctrl+K` 聚焦；点击组件外部关闭
- 结果项展示名称、交易所 · 代码，命中片段用响应里的 `highlight` **原文**高亮；停牌 / 退市 / 待上市 / ST 显示状态徽标
- 失败时展示后端文案与 `traceId` 并提供重试，**关键词保留在输入框**；无结果与空输入各有明确文案
- 复用 `useRemoteData`，直接获得请求序号守卫（防抖只降低并发概率，不消除）
- `focused` 与 `open` 拆成两个状态：点击外部会让面板收起但输入框仍握着光标，用一个状态会让输入框在有光标时看起来失焦
- 补 `role="combobox"` + `aria-activedescendant` + `aria-selected`：焦点始终在输入框上，读屏软件只能靠 `aria-activedescendant` 得知当前高亮哪一项
- `<kbd>` 按平台显示 `⌘ K` 或 `Ctrl K`，不再固定显示 Mac 写法

### 修复

- **全局搜索框此前从未发出过任何请求**：3 条写死的建议（浦发银行 / 宁德时代 / 中芯国际）、每条固定显示编造的 `+2.74%`、**点击任一条都跳到 `/stocks/19876543210001`**——该 ID 在证券主数据里不存在，个股详情页必然 404。这是全站最显眼的入口，点谁都坏
- 点击外部关闭改用 document 监听判断点击落点，替换原型的 `@blur` + `setTimeout(120ms)` + `@mousedown.prevent`（那是靠时间窗赌顺序，机器卡顿时点击会丢）

### 移除

- 原型写死的 3 条建议与固定涨跌幅（涨跌幅见下方"未交付"）
- 占位符「搜索股票、代码或板块」改为「输入代码或名称搜索证券」——STK-01 只搜证券，输入"银行"得到的是名字含"银行"的股票而非板块

### 未交付（已记入 `PROJECT_STATUS.md` 已知问题 #10）

- **搜索建议不展示涨跌幅**。PRD QTE-01 的输出列要求它，但 STK-01 响应里没有任何价格字段；补齐需要
  `STK-05 POST /quotes/securities/batch-query`，而该接口**后端未实现且 `TASKS.md` 未排期**。
  逐条调 STK-04 是 10 条建议 11 次请求，与 PRD「输入后 500 毫秒内出现结果」冲突。

---

## 2026-09-20 — M2-08 前端接入 rankings / sectors / sectors:id / stocks:id

### 新增

- `services/rankingApi.ts`（QTE-01）、`services/sectorApi.ts`（SEC-02/03/06）、`services/securityApi.ts`（STK-04/07）
- `composables/useRemoteData.ts`：一次远程请求的三态（`data` / `loading` / `error` / `reload`）与**过期响应守卫**（请求序号，防止先发的慢请求覆盖后发的快请求）
- `apiClient.toQueryString`：拼查询串时丢弃 `null` / `undefined` / 空串，但保留 `false` 与 `0`（`excludeSt=false` 与不传语义不同）
- 测试：`RankingsPage.test.ts`（5）、`SectorsPage.test.ts`（4）、`SectorDetailPage.test.ts`（5）、`StockDetailPage.test.ts`（7，重写）、`useRemoteData.test.ts`（4）
- 设计文档 `docs/superpowers/specs/2026-09-20-frontend-integration.md`

### 变更

- **`/rankings`** 接 `GET /stock-rankings`：三档口径（涨幅/跌幅/成交额）驱动 `rankingType`、交易所单选驱动 `exchangeCodes`、真实分页（`page` / `totalPages` / `hasNext`）；行号为**全榜单**名次而非页内序号
- **`/sectors`** 接 `GET /sector-rankings?size=100`：卡片字段全部来自 `SectorQuote`；原型里写死的"金融领涨，科技成交活跃"改为数据驱动的客观摘要（涨幅第一 + 成交额第一 + 上涨板块计数），并移除死的"生成板块综述"按钮
- **`/sectors/:id`** 接 `GET /sectors/{id}` + `/sectors/{id}/constituents?size=100`：头部取 `sector` / `parent` / `quote`；强度拆解的涨跌家数由真实成分股重算（停牌单列）并写明分母；成分股表格使用服务端的 `contributionRank`
- **`/stocks/:id`** 接 `GET /securities/{id}/quote` + `/securities/{id}/klines?period=`：K 线周期切换（日/周/月）真实重新请求；行情与 K 线各显示自己的数据截止时间（契约 STK-05 不保证同源同时）
- `format.ts` 的 `formatDateTime` 接受 `string | null`，`null` / 空串 / 非法时间返回 `--`（此前会渲染出 `Invalid Date`）
- `domain.ts`：`MockSectorQuote` 改名 `OverviewSectorQuote`（它并非 mock——`MarketOverview.vue` 早已接真实接口，该类型就是后端 `MarketOverview.SectorPreview` 的前端契约）

### 移除

- **`/rankings` 的"换手率榜"**：契约 QTE-01 的 `rankingType` 白名单只有三种，客户端按 `turnoverRate` 排序是自造口径，且只能对当前页排序，与服务端榜单在数据范围上不一致
- **`/rankings` 的关键字筛选框**：QTE-01 无 `keyword` 参数；在客户端过滤会让排名号与真实名次不符（第 7 名被过滤掉后第 8 名仍显示"08"）
- **`/stocks/:id` 的市盈率、市值、业务描述、所属板块、关联资讯、AI 速览**：全部无数据来源（STK-08/09/10 与 M3-06/07 未实现），保留即编造
- **`/sectors/:id` 的分时走势图**：SEC-05 未实现，假曲线会被当成真实走势；改为一行"尚未实现"说明
- `mockApi.ts` 的 `getStockDetail` 与 `stockDetail` 常量（32 根正弦函数生成的假蜡烛）；`domain.ts` 的 `StockDetail` 与 `MockKlinePoint`（唯一使用者是前者，且 `StockDetail` 上挂着本轮判定为"无数据来源"的那批字段）
- `/stocks/:id` 的"分时"档（STK-06 后端未实现，留着会得到一个永远画不出东西的按钮）

### 文档

- `TASKS.md` M2-08 交付详情（8 条关键取舍）、`PROJECT_STATUS.md`、本文件；`PROJECT_STATUS.md` 已知问题 #7 明确标注**仍未修**并说明理由（属后端行为变更，需独立切片）

---

## 2026-09-20 — M2-07 板块排行、详情与成分股（SEC-01 / SEC-02 / SEC-03 / SEC-04 / SEC-06）

### 新增

- **SEC-01 接口** `GET /api/v1/sectors`（PUBLIC）：可选 `sectorType=INDUSTRY|CONCEPT|REGION`、`parentId`、`keyword`、`status=ACTIVE|INACTIVE`（默认 `ACTIVE`）；返回 `data.items[]{Sector}`
- **SEC-02 接口** `GET /api/v1/sector-rankings`（PUBLIC）：`sectorType`、`rankingType=GAINERS|LOSERS|TURNOVER`（默认 `GAINERS`）、分页；返回**扁平** `data`：`items[]{SectorQuote}` + 分页字段 + `sectorType` + `rankingType` + `snapshotVersion` + `dataTime` + `dataStatus`
- **SEC-03 接口** `GET /api/v1/sectors/{sectorId}`（PUBLIC）：返回 `data{sector, parent, quote}`；停用板块仍返回 200
- **SEC-04 接口** `GET /api/v1/sectors/{sectorId}/quote`（PUBLIC）：返回板块最新行情统计；成分全停牌时 503
- **SEC-06 接口** `GET /api/v1/sectors/{sectorId}/constituents`（PUBLIC）：可选 `effectiveDate`、`rankingType`、分页；每项含嵌套 `quote` + `relationType` + `isPrimary` + `contributionRank`
- **领域模型** `SectorType`、`Sector`、`SectorMember`、`SectorMembershipIndex`、`SectorLeaderStock`、`SectorQuote`、`SectorRanking`、`SectorDetail`、`SectorConstituent`、`SectorList`；统计口径的唯一实现 `SectorQuoteCalculator`（纯函数）
- **端口** `SectorProvider`（`findAll` + 按板块分组的 `memberships`）
- **模拟实现** `SimulatedSectorProvider`：39 个板块（5 大类 + 20 二级行业 + 8 概念 + 6 地域），成分关系投影自 `SecurityMasterProvider`、只由 `securityCode` 哈希导出
- **应用服务** `SectorQueryService`（SEC-01/03/04/06）、`SectorRankingQueryService`（SEC-02）、`SectorCriteria` / `SectorRankingCriteria` / `ConstituentCriteria`、`SectorParameters`、异常 `InvalidSectorQueryException`（→ 400）、`SectorNotFoundException`（→ 404，业务码自带）、`SectorQuoteNotAvailableException`（→ 503）
- **Web 层** `SectorController`（5 个端点）
- 包级工具 `QuoteBatch`（整批快照的公共视图：批次属性 + 按证券索引 + 按成分关系取数）
- 设计文档 `docs/superpowers/specs/2026-09-20-sector-analysis.md`
- 前端契约类型：`SectorType`、`SectorStatus`、`SectorRelationType`、`Sector`、`SectorList`、`SectorListQuery`、`SectorLeaderStock`、`SectorQuote`、`SectorRanking`、`SectorRankingQuery`、`SectorDetail`、`SectorConstituent`、`ConstituentQuery`

### 变更

- `SecurityQueryService` 与 `StockRankingQueryService` 的 `sectorId` **从硬编码空页改为按成分关系真实筛选**，两者共用 `SectorMembershipIndex`
- 抽出 `SimulatedHashing`（SplitMix64 收尾混合的唯一实现），`SimulatedSecurityQuoteProvider` 与 `SimulatedPriceSeries` 的私有 `mix` 改为复用
- `RankingType` 新增 `hasSortKey(SectorQuote)` 与 `sectorOrder()`，板块排行与个股榜单共用同一套口径定义
- `BackendConfiguration` 新增 `SectorProvider` / `SectorQueryService` / `SectorRankingQueryService` Bean，并同步两个既有服务的构造参数
- `GlobalExceptionHandler` 新增板块三类异常的处理（400 / 404 带业务码 / 503）
- 前端 `domain.ts` 的原型 `SectorQuote` 改名 `MockSectorQuote`（避免与契约类型触发声明合并，同 M2-05 的 `MockKlinePoint`），`SectorsPage.vue` 同步

### 修复

- **QTE-01 与板块接口此前落到 `anyRequest().authenticated()`**：契约标为 `PUBLIC`，游客访问 `/api/v1/stock-rankings` 会被 401。已在 `SecurityConfiguration` 显式放开 QTE-01 与 SEC-01~06 的 GET

---

## 2026-09-19 — M2-06 榜单（QTE-01）

### 新增

- **QTE-01 接口** `GET /api/v1/stock-rankings`（PUBLIC）：`rankingType=GAINERS|LOSERS|TURNOVER`（必填）；可选 `exchangeCodes`、`boardCodes`（逗号分隔多值）、`sectorId`、`excludeSt`（默认 `false`）、`excludeSuspended`（默认 `true`）、`page`（默认 1）、`size`（默认 20，上限 100）；返回**扁平** `data`：`items[]{QuoteSnapshot}` + 分页字段 + `rankingType` + `snapshotVersion` + `dataTime` + `dataStatus`
- **领域模型** `RankingType`（含三种口径的排序比较器与 `hasSortKey`）、`StockRanking`（扁平响应）
- **端口** `QuoteSnapshotBatchProvider`（整批快照，**不接收筛选条件**）
- **应用服务** `StockRankingQueryService`、`RankingCriteria`、异常 `InvalidRankingQueryException`（→ 400）
- 包级工具 `QueryParameters`（多值筛选解析，与 STK-02 共用）
- 设计文档 `docs/superpowers/specs/2026-09-19-stock-rankings.md`

### 变更

- `SimulatedQuoteSnapshotProvider` 同时实现单只查询与整批查询，两条路径**共用同一个装配方法**；`SimulatedMarketAccess` 新增 `universe()` 与 `summaries()`（一次性建索引，避免整批装配退化成 O(n²)）
- `SimulatedQuoteProvider.rankings()` 由三个写死常量改为**投影自涨幅榜前 3 名**，与 QTE-01 榜单同源
- `SimulatedQuoteProvider` 构造器改为接收整批快照源；`BackendConfiguration` 只声明一个具体类型的 `quoteSnapshotProvider` Bean（同时满足两个端口），并把 `StockRankingQueryService` 接入
- `GlobalExceptionHandler` 新增 `InvalidRankingQueryException` → 400 `INVALID_REQUEST`
- `SecurityQueryService` 的多值筛选解析规则收敛到 `QueryParameters`，与榜单共用一份实现
- 前端 `domain.ts` 新增 `RankingType` / `StockRanking` / `RankingQuery`

### 修复

- **首页点击个股 404**：总览榜单预览的 `securityId` 用的是主数据里不存在的 `stock-600519`（实际为 `sim-600519`），前端 `/stocks/{id}` 链接必然 404。改为投影自真实批次后，预览行的 ID 必然可解析
- **总览榜单预览与榜单页数据不一致**：预览值原为写死常量，与 QTE-01 榜单无任何关联

### 说明

- 排序口径：涨幅榜按 `changeRate` 降序、跌幅榜升序、成交额榜按 `tradeAmount` 降序；统一兜底键 `security.fullSymbol` 升序（**不随主键方向翻转**，以保证排序稳定）
- 排序键一律解析为 `BigDecimal` 后比较：`changeRate` / `tradeAmount` 是十进制定点字符串，按字典序比较会错（`"0.10"` 字典序小于 `"0.0218"`，数值上却更大）
- 筛选值不存在 → **200 + 空页**（不报错）；`rankingType` 缺失 / 非法、分页越界 → 400 `INVALID_REQUEST`
- `sectorId` 在 M2-07 之前恒返回空页（板块关系数据尚不存在），与 STK-02 的处理一致
- 后端测试 241 → **286**（新增 45）；前端 13 文件 / 37 测试在默认时区与 `TZ=UTC` 下均全绿

---

## 2026-09-19 — M2-05 个股快照与日/周/月 K 线（STK-04 / STK-07）

### 新增

- **STK-04 接口** `GET /api/v1/securities/{securityId}/quote`（PUBLIC）：返回 `QuoteSnapshot`（证券摘要、前收 / 开 / 高 / 低 / 最新价、涨跌额与幅度、量额、换手率、`dataTime`、`serverTime`、`sequence`、`dataStatus`、`delaySeconds`）
- **STK-07 接口** `GET /api/v1/securities/{securityId}/klines`（PUBLIC）：`period=DAY|WEEK|MONTH`（必填）、`startDate`、`endDate`（缺省最近 **120 个交易日**）、`adjustment`（缺省 `NONE`）；返回 `KlineSeries`
- **领域模型** `QuoteSnapshot`、`KlinePoint`（§8.2）、`KlineSeries`、`KlineRequest`、`KlinePeriod`、`KlineAdjustment`、`KlineQualityStatus`
- **端口** `QuoteSnapshotProvider`、`KlineProvider`
- **应用服务** `SecurityDetailQueryService`、异常 `SecurityNotFoundException`（→ 404）、`InvalidKlineParameterException`（→ 400，携带三种业务码）
- **模拟数据源** `SimulatedQuoteSnapshotProvider`、`SimulatedKlineProvider`；共享价格算法 `SimulatedPriceSeries`、取数辅助 `SimulatedMarketAccess`
- 设计文档 `docs/superpowers/specs/2026-09-19-security-detail-and-klines.md`

### 变更

- `SecurityController` 新增两个端点；`BackendConfiguration` 装配 3 个新 Bean
- `GlobalExceptionHandler` 新增 `SecurityNotFoundException` → 404 `SECURITY_NOT_FOUND`、`InvalidKlineParameterException` → 400（业务码由异常自身携带）
- 前端 `domain.ts` 新增 `QuoteSnapshot` / `KlinePeriod` / `KlineAdjustment` / `KlineQualityStatus` / `KlinePoint` / `KlineSeries` / `KlineQuery`；原型同名类型改名 `MockKlinePoint` 以避开 TypeScript 声明合并

### 说明

- K 线**按需生成**（5149 只 × 5 年 ≈ 640 万点，不预生成）；价格序列以最近交易日为锚点**向前倒推**，保证日 K 末端收盘价恒等于该证券最新价
- 周 / 月 K **由日 K 聚合**，`time` 取该周期最后一个交易日；空周 / 空月不产生点，不做自然日补齐（PRD 明确）
- 不支持的复权方式返回 400 `ADJUSTMENT_NOT_SUPPORTED`，**不静默替换成 `NONE`**
- 已知取舍：K 线价格**跨交易日会漂移**（倒推起点随最近交易日前移），接入真实数据源后消失

---

## 2026-09-19 — 修复：行情时间统一按北京时间渲染

### 修复

- `formatDateTime` 未给 `Intl.DateTimeFormat` 指定 `timeZone`，会跟随运行环境本地时区渲染：
  本机（UTC+8）显示 `14:32`，CI（`ubuntu-latest`，UTC）显示 `06:32`，导致前端作业持续失败。
  现显式指定 `timeZone: 'Asia/Shanghai'`。**这同时是真实缺陷**——A 股行情时间只有北京时间一种语义，
  部署在非 UTC+8 环境时用户会看到错误的数据截止时间，**跨日时连日期都会错**
  （UTC 下 `2026-09-12T20:00Z` 曾渲染为 `09/12 20:00`，正确为 `09/13 04:00`）

### 工程

- `format.test.ts` 补 2 条跨时区断言（同一时刻等价写法渲染一致、跨日按北京时间归日）；
  此前该文件**未覆盖** `formatDateTime`，缺陷因此长期潜伏

---

## 2026-09-19 — M2-04 证券主数据与搜索建议（STK-01 / STK-02）

### 新增

- **STK-01 接口** `GET /api/v1/securities/search`（PUBLIC）：`q`（1–50 字符，必填）、`types`、`exchangeCodes`、`limit`（1–20，默认 10）；返回 `items[]{security, matchedField, highlight}`
- **STK-02 接口** `GET /api/v1/securities`（PUBLIC）：`keyword`、`securityType`、`exchangeCode`、`boardCode`、`listingStatus`、`sectorId`、`page`、`size`、`sort`；返回 `PageData<SecuritySummary>`
- **通用分页外壳** `PageData<T>`（`stock-common/api`）：`items` / `page` / `size` / `total` / `totalPages` / `hasNext`，含静态切片方法 `slice`
- **证券主数据领域模型** `SecuritySummary`（与 `RESTful-API.md` §4.1 逐字段对齐）、端口 `SecurityMasterProvider`、`SecuritySearchMatch`（含 `MatchedField` 枚举）、`SecuritySearchResult`
- **应用服务** `SecurityQueryService`（搜索 + 列表）、查询条件 `SecurityListCriteria`、异常 `InvalidSecurityQueryException`（→ 400）
- **模拟数据源** `SimulatedSecurityMasterProvider`：**投影**既有行情全集为证券主数据（5149 只），不重复定义代码段
- 设计文档 `docs/superpowers/specs/2026-09-19-security-master-and-search.md`

### 变更

- `SecurityConfiguration` 放行 `GET /api/v1/securities` 与 `/api/v1/securities/**`
- `GlobalExceptionHandler` 新增 `InvalidSecurityQueryException` → 400 `INVALID_REQUEST`
- `BackendConfiguration` 新增 `SecurityQuoteProvider` / `SecurityMasterProvider` / `SecurityQueryService` Bean（`SecurityQuoteProvider` 此前无 Bean，由 `SimulatedQuoteProvider` 内部自建）
- 前端 `src/types/domain.ts` 新增 `PageData` / `SecurityType` / `ListingStatus` / `SecurityMatchedField` / `SecuritySummary` / `SecuritySearchMatch` / `SecuritySearchResult` / `SecurityListQuery`

### 说明

- **匹配优先级固定**：`CODE`（代码或 `fullSymbol` **前缀**）→ `NAME`（名称**包含**）→ `PINYIN` → `PINYIN_ABBR`，一只证券只产生一条结果。代码用前缀是因为它是结构化标识（输入 `600` 期望 `600xxx` 这一段），名称用包含是因为它是自然语言
- **排序确定性**：搜索按「`matchedField` 优先级 → `fullSymbol` 升序」，第二个键是兜底——没有它，同优先级内的顺序取决于底层集合遍历顺序
- **筛选值不校验合法性，排序字段必须校验**：`types=ETF` 是合法取值、只是当前没有数据，报 400 会把"没有数据"错报成"参数非法"；而 `sort` 字段被静默忽略时调用方会拿到"顺序不对但看起来正常"的响应，故白名单外直接 400
- **拼音保留能力但不填值**：`SecuritySummary` 含 `pinyin` / `pinyinAbbr` 组件并标 `@JsonIgnore`，**JSON 输出严格等于文档 §4.1 的 11 个字段**。合成名称没有可核实的拼音，编一份假拼音会污染真实逻辑；单测用带拼音的桩数据覆盖 `PINYIN` / `PINYIN_ABBR` 两条分支
- **主数据从行情全集投影**：代码段只在一处定义，避免"主数据"与"广度计数"指向不同证券全集且无测试报警
- **`sectorId` 当前必然返回空页**：板块关系数据在 M2-07 之前不存在，"没有任何证券属于该板块"在当下是事实
- **不落库**：`stock_security` 表继续空置，与 M2-01 / M2-03 决策一致
- **验收标准「搜索 P95 < 500ms」**以宽松冒烟测试落实（预热后 100 次采样，P95 断言 < 500ms，两个数量级余量）

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
