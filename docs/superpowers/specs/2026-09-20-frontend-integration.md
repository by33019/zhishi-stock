# M2-08 前端接入（rankings / sectors / sectors:id / stocks:id）— 设计

> 任务：`TASKS.md` M2-08（P0），依赖 M2-05 ✅、M2-06 ✅、M2-07 ✅
> 接口：`RESTful-API.md` §9.1 QTE-01、§10 SEC-01/02/03/04/06、§8.1 STK-04、§8.2 STK-07
> 需求：PRD §7.3 QTE、§7.4 SEC、§7.5 STK

## 1. 背景

M2-05 / M2-06 / M2-07 已经把后端接口做完，`domain.ts` 里的契约类型也补齐了，但**页面还在读 `mockApi`**：

| 页面 | 现状 |
| --- | --- |
| `RankingsPage.vue` | 读 `mockApi.rankingRows`（5 行写死数据）；"换手率榜"在契约里根本不存在；分页脚注写死"共 5,248 个交易标的 · 第 1 / 210 页" |
| `SectorsPage.vue` | 读 `mockApi.getMarketOverview()` 的 `sectors` 预览（4 条），不是板块列表 |
| `SectorDetailPage.vue` | 标题写死"银行 BK0475"、成分股表格复用 `rankingRows.slice(0, 4)`、分时曲线是 8 个写死点位 |
| `StockDetailPage.vue` | 用假 ID `19876543210001` 调 `mockApi.getStockDetail`；K 线是 32 根正弦函数生成的假蜡烛 |

`PROJECT_STATUS.md` 已知问题 #2 记的就是这一条（9 个页面仍走 mockApi）。

## 2. 目标

- 四个页面改为读真实接口，并**正确处理加载中 / 失败 / 数据状态**三态
- 页面展示的每一个数字都必须能在接口响应里找到来源；**没有来源的字段一律降级为"暂无数据"，不得保留编造值**
- 所有行情时间按北京时间渲染（沿用 `formatDateTime` 的 `Asia/Shanghai` 钉死）
- 抽掉四个页面各写一遍的 loading / error / retry 样板

## 3. 不在范围

| 项 | 归属 | 理由 |
| --- | --- | --- |
| `/news` 接真实接口 | M3-05 | 依赖资讯 Provider（M3-04） |
| `/watchlist` 接真实接口 | M3-03 | 依赖自选 CRUD（M3-01 / M3-02） |
| `/ai`、`/history` 接真实接口 | M3-10 | 依赖 AI 编排与持久化（M3-07 / M3-08） |
| `/admin` | M3-11 | 依赖配额与用量（M3-09） |
| 全局搜索接真实接口 | M2-09 | 独立任务 |
| 个股所属板块（STK-09） | 后续 | 后端未实现 |
| 个股资料（STK-08：`businessDescription` / `primarySector`） | 后续 | 后端未实现 |
| 个股关联资讯（STK-10） | M3-05 | 依赖资讯域 |
| 板块走势（SEC-05） | 后续 | 后端未实现 |
| 板块 AI 解读 | M3-06 / M3-07 | 依赖 AI 编排 |
| 热点榜单 Excel 导出 | M3-12 | 后端未实现 |

## 4. 接口与页面的对应关系

| 页面 | 调用 |
| --- | --- |
| `/rankings` | `GET /stock-rankings?rankingType=&exchangeCodes=&page=&size=` |
| `/sectors` | `GET /sector-rankings?sectorType=&rankingType=GAINERS&size=100` |
| `/sectors/:id` | `GET /sectors/{id}` + `GET /sectors/{id}/constituents?size=100` |
| `/stocks/:id` | `GET /securities/{id}/quote` + `GET /securities/{id}/klines?period=` |

### 4.1 为什么 `/sectors` 用 `sector-rankings` 而不是 `sectors` + 逐个 `quote`

板块卡片要展示 `changeRate` / `tradeAmount` / `leadingStock` / `companyCount`，这些只在 `SectorQuote` 里。
用 `GET /sectors`（只有 6 个主数据字段）就得对每个板块再发一次 `/sectors/{id}/quote`——
39 个板块 39 次请求，且**每次请求取到的快照批次可能不同**，页面上的板块涨跌幅会来自不同时刻。

`GET /sector-rankings` 一次请求返回同一快照下的全部板块行情，正是为这个场景设计的。
`size=100` 覆盖 39 个板块（上限即 100），因此不需要分页。

### 4.2 为什么个股页要两次请求

`GET /securities/{id}/quote` 返回 `QuoteSnapshot`，`GET /securities/{id}/klines` 返回 `KlineSeries`。
两个接口的 `dataTime` / `dataStatus` 语义相同但**不保证同一批次**（契约 STK-05 明确"不保证不同证券源时间完全相同"），
因此页面分别展示，不合并成一个"数据截止时间"。

## 5. 逐页设计

### 5.1 `/rankings` 行情榜单

**口径切换**：只保留 `GAINERS` / `LOSERS` / `TURNOVER` 三档，**移除"换手率榜"**。

契约 QTE-01 的 `rankingType` 白名单只有三种，且 QTE-04 明确「不允许客户端自行构造字段名」。
"换手率榜"若在客户端按 `turnoverRate` 排序，就是自造一个服务端没有的口径——
它与服务端榜单在**数据范围**上也不一致（服务端按 `size` 分页后才返回，客户端只能对当前页排序）。

**移除关键字筛选框**：QTE-01 **没有** `keyword` 参数。在当前页做客户端过滤会让排名号与真实名次不符
（第 7 名被过滤掉后，第 8 名仍显示"08"），而这正是榜单最不能被破坏的东西。改为展示一行说明：
"榜单为全市场排序结果，共 N 个标的"。

**新增交易所筛选**：用 `.segmented-tabs` 单选（全部 / 沪市 / 深市 / 北交所）驱动 `exchangeCodes`，
复用页面上已有的视觉语言。契约支持逗号分隔多值，但 MVP 阶段单选已覆盖主要用法。

**真实分页**：上一页 / 下一页 / 当前页号由 `page` / `totalPages` / `hasNext` 驱动，页脚显示
"共 {total} 个标的 · 第 {page} / {totalPages} 页"。翻页时保留当前口径与交易所筛选。

**顶部三张卡片**：
- "领涨标的"→ 当前榜第一行的名称与涨跌幅
- "成交额最高"→ 额外取一次 `TURNOVER` 榜的第一行
- 第三张原为"市场中位数"（契约无此字段）→ 改为**数据截止时间 + 数据状态**

**导出 Excel 按钮**：M3-12 未实现 → 置为 `disabled` 并加 `title="待接入"`。

### 5.2 `/sectors` 板块分析

数据来自 `GET /sector-rankings?size=100`，卡片字段映射：

| 卡片位置 | 来源 |
| --- | --- |
| 序号 | 数组下标 + 1 |
| 涨跌幅 | `SectorQuote.changeRate`（可为 `null` → 渲染 `--`） |
| 名称 | `sectorName` |
| 成分股数与代码 | `companyCount` · `sectorCode` |
| 成交额 | `tradeAmount` |
| 领涨股 | `leadingStock.security.securityName`（`leadingStock` 可为 `null` → `--`） |

**"AI SECTOR NOTE" 区块**：文案是写死的（"金融领涨，科技成交活跃"），生成按钮是死的。
AI 编排在 M3-06/07，因此本轮把该区块改为**由真实数据驱动的客观摘要**
（涨幅第一的板块 + 成交额第一的板块 + 上涨板块占比），并移除"生成板块综述"按钮。

> 写死一句看起来像结论的话，比留空更危险：用户会当成系统判断。

**视图切换（网格 / 列表）**保留。

### 5.3 `/sectors/:id` 板块详情

`GET /sectors/{id}` 给出 `sector` / `parent` / `quote`；`GET /sectors/{id}/constituents?size=100` 给出成分股。

| 区块 | 处理 |
| --- | --- |
| 头部 | `sectorName` / `sectorCode` / `companyCount` / `parent.sectorName` / `quote.changeRate`；数据截止用 `quote.dataTime` |
| 分时走势图 | **移除图表**，改为一行说明"板块走势（SEC-05）待接入"。假曲线比空白更糟 |
| 强度拆解 | 上涨 / 下跌家数由**真实成分股**的 `changeRate` 计算；"板块成交额"取 `quote.tradeAmount`；"领涨集中度"与"相对大盘"契约无定义 → 移除 |
| 成分股表格 | `constituents.items[]`，按返回顺序（服务端已按涨跌幅排序），显示 `contributionRank` / `latestPrice` / `changeRate` / `tradeAmount` / `turnoverRate` |
| AI 板块解读按钮 | 置 `disabled`，标注待接入 |

**为什么上涨/下跌家数可以在前端算**：成分股列表就是板块的全部成分，`changeRate` 是每个成分的真实涨跌幅。
这是对已取回数据的**重新计数**，不是新造数据。相比之下"领涨集中度"没有定义式，只能移除。

**停牌成分股**：`changeRate` 为 `null`，既不计入上涨也不计入下跌，单独显示"停牌 N 只"。

### 5.4 `/stocks/:id` 个股详情

`GET /securities/{id}/quote` 给出 `QuoteSnapshot`；`GET /securities/{id}/klines?period=` 给出 `KlineSeries`。

| 区块 | 处理 |
| --- | --- |
| 头部 | `security.securityName` / `fullSymbol` / `exchangeCode` / `latestPrice` / `changeAmount` / `changeRate` |
| 指标条 | 今开 / 最高 / 最低 / 昨收 / 成交量 / 成交额 / 换手率全部来自 `QuoteSnapshot`；**市盈率移除**（契约无此字段，STK-03 的扩展字段也没有） |
| K 线图 | `KlineSeries.points[]` 真实蜡烛；周期切换（日 K / 周 K / 月 K）**真实触发重新请求**；"分时"移除（STK-06 未实现） |
| 经营与主题 | `businessDescription`（STK-08 未实现）→ "暂无资料"；`sectors[]`（STK-09 未实现）→ 空标签行；总市值（契约无）→ 移除 |
| 关联事件 | STK-10 未实现 → "暂无关联资讯" |
| AI 异动速览 | M3-06/07 未实现 → 整块移除，避免展示编造的"量价可信度：高" |

**K 线数据质量**：`KlineSeries.points[].qualityStatus` 若含 `DELAYED`，在图表下方提示。

## 6. 关键设计决策

### 6.1 没有数据来源的字段一律降级，不保留编造值

这是本轮最重要的决策。原型里的 `peRatio: '6.21'`、`marketCap: '362400000000'`、
`"量价可信度：高"`、`"+2.15% 相对大盘"` 全是编造的，但**看起来像真实数据**。
在一个投资辅助工具里，编造的估值指标比空白危险得多——用户会据此做判断。

因此：**契约有字段就接真实值；契约没有就显示"暂无数据"并移除图表/指标**，
不保留"看起来还行"的占位数字。这也与 PRD §7.5 STK-08「资料缺失时返回字段为 `null`，
AI 不得自行补全」同源。

代价：个股详情页会明显变空（PE、市值、业务描述、板块、资讯、AI 速览都空）。
接受这个代价——M3 会把这些逐个填回来，而填回来时它们会是真数据。

### 6.2 抽 `useRemoteData` 统一三态

四个页面都需要"加载中 → 成功 / 失败（带 traceId，可重试）"。`MarketOverview.vue` 已经手写了一遍，
再抄三遍就是四份同样的 `try/catch/finally`。抽一个 ~35 行的 composable：

```ts
const { data, loading, error, reload } = useRemoteData(() => getRankings(query))
```

**不做**：不做请求缓存、不做并发去重、不做轮询。这些都没有需求支撑，
且 `apiClient` 已经处理了 401 刷新。composable 只负责"一次请求的三态"。

### 6.3 筛选条件变化触发重新请求，而不是客户端过滤

口径、交易所、页码、K 线周期都是**服务端参数**。改变它们就重新请求。
客户端过滤只用于"服务端不支持该筛选"的场景——而本轮的四个页面都不需要它（见 5.1 移除关键字筛选）。

### 6.4 `formatDateTime` 接受 `null`

`QuoteSnapshot.dataTime` 等字段在契约里可为 `null`。当前签名是 `formatDateTime(value: string)`，
页面传 `null` 会渲染出 `Invalid Date`。改为 `string | null`，`null` 时返回 `'--'`，
并补一条测试。这是向后兼容的改动（`MarketOverview.vue` 传的仍是非空值）。

### 6.5 板块详情用一次 `constituents` 同时满足两个区块

"强度拆解"的上涨/下跌家数与"成分股表格"都需要全部成分。`size=100` 一次取回（板块最多 20 个二级行业
之一约 257 只——**超过 100**），因此：

- 表格显示第一页（`size=100`）
- 上涨/下跌家数基于**已取回的这一页**计算，并在文案里写明"基于前 100 只成分股"

> 不把 257 只全拉回来：契约 `size` 上限就是 100，翻三次页只为算一个家数不值得。
> 但**必须写明分母**，否则"38 家上涨"会被读成整个板块。

## 7. 改动清单

### frontend / services（新增）

| 文件 | 说明 |
| --- | --- |
| `rankingApi.ts` | `getStockRankings(query)` |
| `sectorApi.ts` | `getSectorRankings(query)`、`getSectorDetail(id)`、`getSectorConstituents(id, query)` |
| `securityApi.ts` | `getSecurityQuote(id)`、`getSecurityKlines(id, query)` |

### frontend / composables（新增）

| 文件 | 说明 |
| --- | --- |
| `useRemoteData.ts` | 一次请求的三态：`data` / `loading` / `error` / `reload` |

### frontend / pages（修改）

| 文件 | 说明 |
| --- | --- |
| `RankingsPage.vue` | 接 `stock-rankings`；三档口径 + 交易所筛选 + 真实分页；移除换手率榜与关键字框 |
| `SectorsPage.vue` | 接 `sector-rankings`；AI 区块改为数据驱动的客观摘要 |
| `SectorDetailPage.vue` | 接 `sectors/{id}` + `constituents`；移除假走势图 |
| `StockDetailPage.vue` | 接 `securities/{id}/quote` + `klines`；周期切换真实生效；移除无来源字段 |

### frontend / utils（修改）

| 文件 | 说明 |
| --- | --- |
| `format.ts` | `formatDateTime` 接受 `string \| null` |

### frontend / services（清理）

| 文件 | 说明 |
| --- | --- |
| `mockApi.ts` | 删除已无引用的 `getStockDetail` 与 `stockDetail` 常量；`rankingRows` 保留（`WatchlistPage` 与 `MarketOverview.rankings` 仍在用，归 M3-03） |
| `mockApi.test.ts` | 同步删除 `getStockDetail` 的断言，改断言 `getNews` |
| `apiClient.ts` | 新增 `toQueryString`：拼查询串时丢弃 `null` / `undefined` / 空串，但保留 `false` 与 `0` |

### frontend / types（清理）

| 文件 | 说明 |
| --- | --- |
| `domain.ts` | 删除 `StockDetail` 与 `MockKlinePoint`（唯一使用者是刚被删掉的 `stockDetail` 常量）；`MockSectorQuote` 改名 `OverviewSectorQuote` |

`StockDetail` 上挂着 `peRatio` / `marketCap` / `businessDescription` / `aiPrompts` 这些**没有数据来源**的字段，
`MockKlinePoint` 的价格还是 `number`。留着它们等于给下一个人留一份"看起来能用"的假契约。

`MockSectorQuote` 的改名是纠错：它**不是 mock**——`MarketOverview.vue` 早已接真实接口，
这个类型就是后端 `MarketOverview.SectorPreview` 的前端契约（`leadingStock` 只有名称、无 `securityId`）。
`Mock` 前缀会让人以为它是可以随手改的占位数据。

### 测试（新增 / 修改）

| 文件 | 覆盖 |
| --- | --- |
| `pages/RankingsPage.test.ts` | 三档口径切换触发重新请求、交易所筛选拼参、翻页、失败态可重试、数据截止时间 |
| `pages/SectorsPage.test.ts` | 卡片字段映射、`leadingStock` 为 `null` 时渲染 `--`、失败态 |
| `pages/SectorDetailPage.test.ts` | 头部字段、成分股表格、上涨/下跌家数含停牌处理、失败态 |
| `pages/StockDetailPage.test.ts` | 快照字段、K 线周期切换触发重新请求、无来源字段显示"暂无数据"、失败态 |
| `utils/format.test.ts` | `formatDateTime(null)` 返回 `--` |
| `composables/useRemoteData.test.ts` | 成功 / 失败带 traceId / reload |

## 8. 验收方式

1. `npx vue-tsc --noEmit` 零错误
2. `npx vitest --configLoader runner --run` 全绿，**并在 `TZ=UTC` 下再跑一遍**
   （时间渲染相关改动必须验证时区无关性）
3. `npx vite build` 成功
4. 手工核对（dev server + 后端在跑）：
   - `/rankings` 切换三档口径时榜单内容变化，页脚总数与后端 `total` 一致
   - `/rankings` 点第一行能跳到 `/stocks/sim-600xxx` 且详情页有数据（不再 404）
   - `/sectors` 的涨跌幅与 `/sector-rankings` 接口返回一致
   - `/sectors/sim-bk0006` 的成分股表格第一行 `contributionRank = 1`，与头部领涨股一致
   - `/stocks/sim-600000` 切日/周/月 K 时图表变化，且 `dataTime` 显示为北京时间
5. 页面中不再出现 `mockApi` 的引用（`grep -rn "services/mockApi" src/pages` 只剩 `NewsPage` 与 `WatchlistPage`）

## 9. 已知取舍

| # | 取舍 | 理由与代价 |
| --- | --- | --- |
| 1 | 移除"换手率榜" | 契约 QTE-01 无此口径，客户端自造排序与服务端榜单在数据范围上不一致；代价是用户少一个视图，需等 QTE-04 的 options 接口 |
| 2 | 移除榜单关键字筛选框 | QTE-01 无 `keyword` 参数，客户端过滤会破坏排名语义；代价是用户无法在榜单页内搜索，需走全局搜索（M2-09） |
| 3 | 个股页移除 PE / 市值 / 业务描述 / 所属板块 / 关联资讯 / AI 速览 | 全部无数据来源，保留即编造；代价是页面明显变空，等 M3 逐个填回 |
| 4 | 板块详情移除走势图 | SEC-05 未实现，假曲线会被当成真实走势；代价是详情页只剩表格 |
| 5 | 板块详情的涨跌家数基于前 100 只成分股 | 契约 `size` 上限 100，板块可达 257 只；代价是家数是"部分"统计，已用文案写明分母 |
| 6 | 抽 `useRemoteData` composable | 避免四份相同的三态样板；代价是多一个间接层，且它不做缓存/去重（刻意） |
| 7 | `/sectors` 用 `sector-rankings` 而非 `sectors` + 逐个 `quote` | 一次请求取同一快照下的全部板块行情；代价是页面上没有"停用板块"可看（排行不含 `INACTIVE`），而 `GET /sectors` 可以 |
| 8 | 交易所筛选做成单选而非多选 | 复用已有 `.segmented-tabs` 样式，MVP 覆盖主要用法；代价是与契约的逗号分隔多值能力不完全对齐 |
