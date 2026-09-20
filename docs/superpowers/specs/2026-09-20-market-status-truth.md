# M2-10 市场状态真实化 — 设计

> 对应契约：`docs/RESTful-API.md` MKT-01 / MKT-02。
> 关闭 `PROJECT_STATUS.md` 已知问题 **#7**（非交易日 `tradeDate` / `marketStatus` 未回退）与 **#12**（顶栏写死的"交易中 14:32"）。

## 1. 背景

### 1.1 契约要求

`docs/RESTful-API.md` §3.6 之后明确：

> 非交易日返回最近有效收盘快照，并将 `marketStatus.sessionStatus` 标记为 `CLOSED`，不视为数据延迟。

### 1.2 现状 A：总览快照完全不看交易日历（后端）

`SimulatedQuoteProvider.fetch()` 的开头是：

```java
OffsetDateTime now = OffsetDateTime.now(clock);
LocalDate tradeDate = now.toLocalDate();                       // ← 不看日历
MarketSessionStatus sessionStatus = scenario == Scenario.CLOSED
        ? MarketSessionStatus.CLOSED
        : MarketSessionStatus.TRADING;                          // ← 由配置决定，默认恒为 TRADING
```

因此在**周日 / 节假日 / 收盘后**访问 `GET /markets/overview`，会得到 `tradeDate = 今天`、`marketStatus = TRADING`。
今天是 2026-09-20（周日），该接口此刻正在报"今天是交易日、正在交易中"。

### 1.3 现状 A 的连带后果：同一份快照内部自相矛盾

这份快照里的两个字段来自**不同的交易日**：

| 字段 | 取数路径 | 非交易日实际得到 |
| --- | --- | --- |
| `breadth` | `breadth(tradeDate)`，其中 `tradeDate = now.toLocalDate()` | **周日**的行情（该日无行情） |
| `rankings` | `quoteSnapshotBatchProvider.fetchBatch(marketCode)` → `SimulatedMarketAccess.latestTradeDate()` | **周五**的行情（已正确回退） |

`SimulatedMarketAccess.latestTradeDate()` 早已实现了正确规则：

```java
/** 最近的有效交易日：盘中为当日，盘后与节假日回退到上一交易日。 */
LocalDate latestTradeDate() {
  LocalDate today = LocalDate.now(clock);
  return tradingCalendarProvider.find(SUPPORTED_MARKET, today)
      .map(day -> day.tradingDay() ? day.tradeDate() : day.previousTradeDate())
      .orElse(today);
}
```

**问题不在缺少规则，而在于规则只被部分链路使用**。个股快照、榜单、板块、证券主数据都已走它，唯独总览 Provider 自己算了一遍，且算错了。
破坏的不变量是"同一份快照必须同源"——而这个破坏**不会有任何测试报错**，因为两个字段各自都是"合法"的值。

### 1.4 现状 B：顶栏与侧栏写死（前端）

`AppShell.vue`：

```html
<span class="market-state"><i />交易中 <b>14:32</b></span>
...
<div class="data-source">
  <span class="live-dot" />
  <span><strong>数据链路正常</strong><small>延迟 26 秒</small></span>
</div>
```

`AppShell` 是 shell 布局，出现在除 `/login` 之外的**每一个页面**上。这两处是写死的常量，且 `14:32` 与"延迟 26 秒"看起来像实时数据。

数据源早已就位：MKT-02 `GET /markets/{marketCode}/status` 已实现，且**已正确处理非交易日**（`day.tradingDay()` 为假 → `CLOSED`），但**从未被前端消费**。

### 1.5 与 MKT-02 spec 的关系

`docs/superpowers/specs/2026-09-19-market-status-and-calendar.md` §3 明确写「**不改造 MKT-01 的响应结构与行为**」，并在 §1 已指出"MKT-01 的 `marketStatus` 是硬编码的粗粒度状态（`NORMAL` 场景恒为 `TRADING`）"。

本次切片**接续并解除该延后**：MKT-01 的响应结构不变（字段与类型都不动），只把取值改为由日历推导。

## 2. 目标

1. `/markets/overview` 的 `tradeDate` / `marketStatus` / `dataTime` 全部由交易日历推导；非交易日回退到最近有效收盘并标 `CLOSED`。
2. 总览快照内部一致：`breadth` 与 `rankings` 使用**同一个** `tradeDate`。
3. 口径规则**只有一处实现**，被 MKT-01 摄入、MKT-02 查询、个股/整批快照三条链路共用。
4. 顶栏与侧栏展示真实交易时段状态；请求失败时显示"状态未知"并可重试，不显示编造值。
5. 总览页明确标注**数据对应的交易日**，使"非交易日展示上一交易日收盘数据"这件事对用户可见。

## 3. 不在范围

| 事项 | 说明 | 归属 |
| --- | --- | --- |
| 总览快照里写死的板块预览 | `SimulatedQuoteProvider.sectors()` 返回 `bk-ai` / `bk-chip` / `bk-broker`，而真实板块源生成的是 `sim-bk0001`…`sim-bk0039`。总览页把它们链接到 `/sectors/{sectorId}`，**三张卡片点进去全部 404**。与 M2-06 修掉的 `stock-600519` 同类 | **M2-11**（已确认，不在本轮） |
| 总览快照里写死的指数 | `SimulatedQuoteProvider.indices()` 是常量数组。**与本轮的板块问题性质不同**：`idx-*` 不与任何其它链路冲突，没有坏链接，作为模拟源的数据是自洽的 | 不做（见 §6.5） |
| 顶栏"消息通知"铃铛 | 无数据源 | M3 通知域 |
| STK-05 批量行情 | 搜索建议涨跌幅与自选页首屏依赖它 | M3-03 |
| 分时 STK-06 | PRD 列 P0 但 TASKS 未排期 | 单独排期 |
| MKT-05~08（交易日历接口等） | 未排期 | 未排期 |
| 真实行情源 / 日历源接入 | 后置 | 后置 |

## 4. 口径规则

新增纯函数 `TradingSessions`（`cn.zhishi.stock.market.domain`）。两条规则各只有一处实现。

### 4.1 `latestTradeDate(calendar, marketCode, today)`

| 情形 | 结果 |
| --- | --- |
| 日历给出当日且 `tradingDay = true` | 当日 |
| 日历给出当日且 `tradingDay = false` | `previousTradeDate` |
| 日历查不到（市场代码不支持） | `today`（兜底，不抛错） |

与 `SimulatedMarketAccess.latestTradeDate()` 现有语义**逐字一致**——抽取是等价的搬移，不是行为变更。

### 4.2 `currentSession(day, today, now)`

仅当 `day.tradeDate().equals(today) && day.tradingDay()` 时按 `day.sessionAt(now)` 推导，否则一律 `TradingSession.CLOSED`。

> 与 MKT-02 spec §4.2 的时段窗口一致：00:00–15:00 连续无缝，15:00 之后无窗口由 `CLOSED` 兜底。
> 因为窗口连续，**盘前不会落空**：08:00 落到 `PRE_OPEN` 而不是"无窗口兜底"。

### 4.3 总览快照的三个字段

| 字段 | 规则 | 非交易日（周日）结果 |
| --- | --- | --- |
| `tradeDate` | `latestTradeDate(calendar, marketCode, today)` | 周五 |
| `marketStatus` | `currentSession(day, today, now).status()` | `CLOSED` |
| `dataTime` | `now` 落在该交易日某个时段窗口内 → `now`；否则 → 该交易日的**收盘时刻** | 周五 15:00 |

`dataTime` 取收盘时刻而不是 `now`，因为契约 §3.6 定义它是「该行情本身对应的时间」：周日 15:20 生成的快照里装的是周五的行情，写成周日 15:20 是假的。

## 5. 接口契约（本轮前端消费 MKT-02）

`GET /markets/{marketCode}/status`（PUBLIC）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `marketCode` | string | 本轮固定 `CN` |
| `tradeDate` | date | 查询日期 |
| `isTradingDay` | boolean | **JSON 名与 Java 字段名不同**（`@JsonProperty("isTradingDay") boolean tradingDay`），前端类型必须用 `isTradingDay` |
| `sessionStatus` | enum | `PRE_OPEN` / `CALL_AUCTION` / `TRADING` / `BREAK` / `CLOSED` |
| `currentSession` | enum | 细粒度时段，7 档 |
| `nextSessionAt` | datetime/null | 下一时段开始；日历未给出未来交易日时为 `null` |
| `calendarSourceTime` | datetime | 日历数据源时间（模拟源取 `now`） |

## 6. 关键设计决策

### 6.1 抽 `TradingSessions`，而不是在总览里再写一遍

现状的教训是"规则存在但只被部分链路使用"。如果这次只在 `SimulatedQuoteProvider` 里补一段相同逻辑，就有了**两份**实现，下次改一处就会让总览与榜单对"今天是哪一天"给出不同答案——而且同样不会有测试报错。

因此把两条规则抽到 `domain` 层纯函数，三处调用方全部改为委托：
`SimulatedMarketAccess.latestTradeDate()`、`MarketStatusQueryService.getStatus()`、`SimulatedQuoteProvider.fetch()`。

### 6.2 `dataTime` 的"盘中"判定用时段窗口，而不是比较 `tradeDate == today`

两种写法的差别只在**收盘后**：交易日 20:00 时 `tradeDate == today` 成立，但市场 15:00 已收盘。
用窗口判定能得到 15:00（真实收盘时刻），用日期比较会得到 20:00（一个没有数据的时刻）。
判据取"有没有窗口覆盖此刻"，与 `TradingCalendarDay` 的既有语义同源。

### 6.3 保留 `Scenario.CLOSED` 作为显式覆盖

日历接管后 `Scenario.CLOSED` 变得冗余（非交易日与盘后自然会得到 `CLOSED`）。但它已被 `compose.yaml`、`backend/README.md`、两个 `application.yml` 与三处测试引用，删除它属于**扩大范围**且会破坏用户的 compose 环境变量。

因此保留，但语义明确为**演示/测试用的强制覆盖**：设置后无视日历强制标记为已收盘。它不是数据源，而是模拟源的场景开关。

### 6.4 顶栏的"14:32"是真实北京时间，不是数据时间

原设计 `交易中 14:32` 的 `14:32` 是写死的。改成：
- **时段进行中**（`PRE_OPEN` / `CALL_AUCTION` / `TRADING` / `BREAK`）→ `状态文案 + 当前北京时间`
- **已收盘 / 非交易日** → 只显示状态文案，把 `nextSessionAt` 放进 `title`

收盘后显示"已收盘 20:00"是误导（20:00 没有数据）；而"下一时段何时开始"才是收盘后唯一有用的时间信息，但顶栏横向空间有限，放 `title` 里而不是挤压布局。

时间随 60 秒刷新节拍更新（见 §6.6），不额外起一个秒级定时器。

### 6.5 指数写死可以留，板块写死不能留

判据是**这个写死的值是否需要与系统其它部分对齐**：

- `indices()` 返回 `idx-sh` / `idx-sz` / `idx-hs300` / `idx-hsi`，没有任何链路按这些 ID 反查，值本身自洽 → 作为模拟源的数据可以接受。
- `sectors()` 返回 `bk-ai` / `bk-chip` / `bk-broker`，而总览页会把它们当作真实 `sectorId` 去跳转 `/sectors/{sectorId}` → **必须与 `SimulatedSectorProvider` 对齐**，否则是坏链接（M2-11）。

一句话：**写死的数值可以是模拟数据；写死的标识符只要需要被别处解析，就是缺陷。**

### 6.6 60 秒刷新放在独立 composable，不进 `useRemoteData`

`useRemoteData` 刻意不做轮询（M2-08 决策），不应为顶栏破例。新建 `composables/useMarketStatus.ts`，在 `useRemoteData` 之上叠加：

- **60 秒定时刷新**：市场状态会在 09:15 / 09:25 / 09:30 / 11:30 / 13:00 / 14:57 / 15:00 变化，不刷新就会一直停在旧状态。
- **页面不可见时暂停**（`document.visibilitychange`）：后台标签页持续请求没有意义；恢复可见时立刻补一次，避免切回来看到过期状态。
- 暴露 `now`（与刷新同节拍更新的北京时间），供顶栏显示。

### 6.7 总览页必须标注交易日

修好 #7 之后，周日打开 `/market` 会看到"数据截止 09/19 15:00"（周五）。若不解释，用户会以为页面坏了或数据停更。因此在数据截止时间旁明确标注交易日，并在非交易时段显示"休市 / 已收盘"标记。

这是本切片与 #7 不可分割的一半：**只改后端会让页面从"显示假数据"变成"显示正确但令人困惑的数据"。**

## 7. 改动清单

### 7.1 后端

| 文件 | 说明 |
| --- | --- |
| `stock-market/domain/TradingSessions.java` | **新增**。`latestTradeDate` + `currentSession` 两条纯函数 |
| `stock-integration/market/SimulatedMarketAccess.java` | `latestTradeDate()` 改为委托 `TradingSessions` |
| `stock-market/application/MarketStatusQueryService.java` | 时段推导改为委托 `TradingSessions.currentSession` |
| `stock-integration/market/SimulatedQuoteProvider.java` | 注入 `TradingCalendarProvider`；`tradeDate` / `marketStatus` / `dataTime` 由日历推导；`breadth` 与榜单预览共用同一 `tradeDate` |
| `stock-backend/config/BackendConfiguration.java` | `quoteProvider` Bean 传入 `TradingCalendarProvider` |
| `stock-job/config/JobConfiguration.java` | 同上 |

### 7.2 前端

| 文件 | 说明 |
| --- | --- |
| `types/domain.ts` | 新增 MKT-02 契约类型 `MarketStatus`（注意 `isTradingDay` 命名） |
| `services/marketApi.ts` | 新增 `getMarketStatus(marketCode = 'CN')` |
| `composables/useMarketStatus.ts` | **新增**。三态 + 60s 刷新 + 可见性暂停 + `now` |
| `layouts/AppShell.vue` | 顶栏接真实状态；侧栏写死的"数据链路正常 / 延迟 26 秒"改为真实的 `calendarSourceTime` |
| `pages/MarketOverview.vue` | 标注交易日；非交易时段显示"休市 / 已收盘"标记 |

### 7.3 测试

| 文件 | 说明 |
| --- | --- |
| `TradingSessionsTest.java` | 新增：交易日盘中/盘后、非交易日、日历缺失、`previousTradeDate` 为 null |
| `SimulatedQuoteProviderTest.java` | 新增非交易日用例：`tradeDate` 回退、`marketStatus = CLOSED`、`dataTime` = 上一交易日收盘、`breadth` 与榜单同源 |
| `MarketStatusQueryServiceTest.java` | 回归：委托后行为不变 |
| `useMarketStatus.test.ts` | 新增：60s 刷新、不可见时暂停、失败暴露 traceId |
| `AppShell.test.ts` | 扩展：真实状态文案、失败时"状态未知"、不出现写死值 |
| `MarketOverview.test.ts` | 扩展：交易日标注与休市标记 |

## 8. 验收方式

1. 后端 `mvn.cmd -q test` 全量通过（基线 338 → 预计 350+）
2. 前端 **`npm run typecheck`**（不是 `npx vue-tsc --noEmit`，见 `MEMORY.md`）零错误
3. `npx vitest --configLoader runner --run` 全绿；`TZ=UTC` 下再跑一遍
4. `npx vite build --configLoader runner` 成功
5. 关键不变量断言（写成测试）：
   - 非交易日 `marketStatus == CLOSED` 且 `tradeDate != today`
   - `breadth` 与 `rankings` 来自同一 `tradeDate`
   - 交易日 10:00 → `TRADING`、`dataTime == now`
   - 交易日 20:00 → `CLOSED`、`dataTime == 当日 15:00`
   - 前端：接口失败时顶栏显示"状态未知"，页面文本**不含** `14:32` / `延迟 26 秒`
6. 推送后用免鉴权 GitHub API 核对 CI 四个作业全绿

### 验收结果（2026-09-20）

| 项 | 结果 |
| --- | --- |
| 后端 | **354 项通过**（新增 13：`TradingSessionsTest` 8 + `SimulatedQuoteProviderTest` 5） |
| 前端 | **19 文件 / 99 项通过**（基线 18 / 75；新增 `useMarketStatus` 9、`AppShell` +4、`MarketOverview` +7、`format` +4），`TZ=UTC` 下同样全绿 |
| 类型 / 构建 | `npm run typecheck` 0 错误；`vite build` 成功 |
| 关键不变量 | 全部写成测试并通过：非交易日 `marketStatus == CLOSED` 且 `tradeDate != today`；`breadth` 与 `rankings` 同源；交易日 10:00 → `TRADING` 且 `dataTime == now`；交易日 20:00 → `CLOSED` 且 `dataTime == 当日 15:00`；前端接口失败时顶栏显示"状态未知"且页面不含 `14:32` / `延迟 26 秒` |

**一处与 spec 的偏差**：§6.7 原写"判据是快照的 `tradeDate` 与 `dataTime` 是否同日"，实现时发现这不成立——
后端在非交易日会把 `dataTime` 回退到**上一交易日的收盘时刻**，两者本来就同日，区分不出周末与盘后。
改为与"今天"（按北京时间）比较：`tradeDate == today` → 已收盘，否则 → 非交易日。

## 9. 已知取舍

| 取舍 | 代价 | 为什么接受 |
| --- | --- | --- |
| `Scenario.CLOSED` 保留为强制覆盖 | 存在一个能"伪造"收盘状态的环境变量 | 删除它会破坏已发布的 compose 环境变量与三处测试，属于扩大范围；且它明确是模拟源的场景开关 |
| 顶栏收盘后不显示时间 | 比原型少了一个数字 | 收盘后显示当前时刻（如 20:00）会被读成"数据截止 20:00"，而那时没有数据 |
| 60 秒刷新而非按 `nextSessionAt` 定时唤醒 | 每个打开的标签页每分钟一次请求 | 按边界唤醒在日历本身过期时会静默停更；固定节拍简单且不会失效 |
| 总览页不重构指数区块 | 指数仍是模拟常量 | 指数 ID 不与任何链路冲突，没有坏链接；重构它属于 M2-11 之外的范围 |
| 不改 `SimulatedQuoteSnapshotProvider` 的 `dataTime` 口径 | 总览与个股快照的 `dataTime` 在盘中不同（前者=此刻，后者=当日收盘） | 两者语义不同：总览是"当前市场快照"，个股快照是"已完成的快照版本"。统一会牺牲其中一个的准确性 |
| 本轮不修板块预览坏链接 | 首页三张板块卡片仍 404 | 它需要注入 `SectorProvider` 并按 `SectorQuoteCalculator` 重算，属独立一块；已确认为 M2-11 并写进已知问题 |
