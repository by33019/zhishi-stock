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
