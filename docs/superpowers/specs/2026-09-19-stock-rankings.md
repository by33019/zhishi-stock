# M2-06 榜单（涨跌幅 / 成交额）+ 分页筛选 — 设计

> 任务：`TASKS.md` M2-06（P0），依赖 M2-04 ✅、M2-05 ✅
> 接口：`RESTful-API.md` §9.1 QTE-01；行类型 §4.2 `QuoteSnapshot`；分页外壳 §26.2
> 需求：PRD §7.3 QTE-01/P0（涨跌幅榜、成交额榜、筛选、分页）

## 1. 背景

M2-05 交付了 `QuoteSnapshot`（个股完整行情快照）与 `QuoteSnapshotProvider`（单只查询），
但那只解决了"**已知是哪只**证券"的查询。榜单要回答的是相反的问题：
"**全市场里哪些**证券排在最前"——输入是筛选条件与排序口径，输出是一页证券。

当前 `GET /stock-rankings` 完全不存在。前端 `/rankings` 页（`RankingsPage.vue`）仍在使用
原型期的 `mockApi.rankingRows` 常量，M2-08 才能接入真实接口，但接口本身需要先立起来。

另有一处既有缺陷会在本轮一并处理（见 §8.3）：市场总览 `MKT-01` 的 `rankings` 预览
是三个写死的常量，`securityId` 用的是 `stock-600519` 这类**主数据里不存在**的 ID，
前端首页点击"贵州茅台"会跳到 `/stocks/stock-600519` 并 404。

## 2. 目标

- 交付 `GET /api/v1/stock-rankings`（QTE-01，PUBLIC）
- 支持三种榜单口径：`GAINERS`（涨幅榜）、`LOSERS`（跌幅榜）、`TURNOVER`（成交额榜）
- 支持交易所、板块、板块 ID、ST、停牌筛选与稳定分页
- 新增领域端口与模拟实现，真实数据源后置替换时不改用例层
- 保证**同一只证券在"榜单"与"个股快照"两处给出同一套行情事实**（PRD §7.5 STK-01 验收原文：
  「行情值与榜单一致」）

## 3. 不在范围

| 项 | 归属 |
| --- | --- |
| QTE-02 / QTE-03 涨停榜、跌停榜 | `TASKS.md` M2 未列；涨停/跌停判定能力（`LimitRuleMatcher`）已就位，增量成本低，但本轮不做 |
| QTE-04 榜单筛选项枚举接口（`/stock-rankings/options`） | 未排期 |
| QTE-05 / EXP-01~04 榜单 Excel 导出 | 未排期 |
| `sectorId` 真实生效 | 板块关系数据（`stock_sector` / `stock_security_sector`）在 M2-07 之前不存在；本轮按"该板块下没有证券"返回空页，M2-07 后自然生效 |
| 前端 `/rankings` 页接入 | M2-08 |
| 榜单落库 / Redis 缓存 | 本轮按需生成；真实数据源接入后再定 |

## 4. 数据来源

### 4.1 落地方式：整批投影（内存，不落库）

沿用 M2-01 ~ M2-05 的可插拔模拟源模式。**榜单与个股快照共用同一批行情**：
新增端口 `QuoteSnapshotBatchProvider.fetchBatch(marketCode)` 一次返回整批快照，
由 `SimulatedQuoteSnapshotProvider` 同时实现单只查询与整批查询两条路径。

这与 M2-05 的 K 线不同：K 线不能预生成（全市场 5149 只 × 5 年 ≈ 640 万点），
但榜单**本来就是全市场的横截面**，一次请求需要全部证券，整批生成是自然的粒度。

### 4.2 为什么把"排序 / 筛选 / 分页"放在应用层而不是 Provider

三个理由：

1. **"同一批次"成为结构保证。** 端口只有 `fetchBatch` 一个方法，一次调用返回的就是同一批次，
   因此"整个榜单使用同一已完成快照版本"（契约原文）不需要额外约定——它由接口形状决定。
   若让 Provider 接收筛选条件，批次边界就落到了实现细节里，将来换实现时容易被破坏。
2. **排序口径可以脱离数据源测试。** "涨幅榜 = 涨跌幅降序 + `fullSymbol` 兜底"是业务语义，
   用桩批次直接断言即可，不必先造一份全市场数据。
3. **与既有 `SecurityQueryService` 一致。** STK-02 证券列表已经在应用层做筛选、排序、分页，
   榜单走同一条路，两个列表接口的参数校验与分页行为不会各说各话。

代价：生产环境真实 Provider 若用 SQL `ORDER BY ... LIMIT` 实现，应用层这套逻辑会退化为
"参考实现"而非实际执行路径。这一点记入 §11。

### 4.3 排序键从哪来

`QuoteSnapshot` 的 `changeRate` / `tradeAmount` 是**十进制定点字符串**（契约 §4.2），
不是数值。直接按字符串比较是错的（`"0.10" < "0.0218"` 按字典序成立，但数值上前者更大），
因此排序键必须解析为 `BigDecimal` 再比较。

**没有有效排序键的行不进榜。** 涨跌幅榜要求该行有涨跌幅，成交额榜要求有成交额；
PRD §7.3 QTE-02 也写明「停牌和**无有效价格**默认排除」。这条规则同时让排序比较器
不必处理 `null`——进入排序的行一定带得动排序键。

### 4.4 与既有模拟源的一致性

| 事实 | 唯一来源 |
| --- | --- |
| 证券身份（代码、名称、交易所、板块、ST、停牌） | `SecurityMasterProvider`（投影自行情全集） |
| 前收价 / 最新价 | `SecurityQuoteProvider.fetchUniverse` |
| 涨跌停价 | `LimitRuleMatcher` + `LimitRuleProvider` |
| 开高低 / 量额 / 换手率 | `SimulatedPriceSeries.bar` |

榜单**不新增任何生成逻辑**，只是把 `SimulatedQuoteSnapshotProvider` 已有的单只装配
按批次跑一遍。单只路径与整批路径共用同一个装配方法，避免"同一条数据两条生成路径"。

## 5. 接口契约

### 5.1 QTE-01 `GET /api/v1/stock-rankings`

**Query 参数**

| 参数 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `rankingType` | 是 | — | `GAINERS` \| `LOSERS` \| `TURNOVER`，大小写不敏感 |
| `exchangeCodes` | 否 | 全部 | 逗号分隔，如 `SH,SZ`；取值 `SH` / `SZ` / `BJ` |
| `boardCodes` | 否 | 全部 | 逗号分隔，如 `MAIN,GEM`；取值 `MAIN` / `GEM` / `STAR` / `BSE` |
| `sectorId` | 否 | 全部 | M2-07 前恒返回空页（见 §3） |
| `excludeSt` | 否 | `false` | `true` 时剔除 `isSt=true` 的证券 |
| `excludeSuspended` | 否 | `true` | `true` 时剔除停牌证券。**默认排除**依 PRD §7.3 QTE-02 |
| `page` | 否 | `1` | 从 1 开始，`< 1` 报 400 |
| `size` | 否 | `20` | 1 至 100，越界报 400 |

**响应（HTTP 200，`data` 为扁平对象）**

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "查询成功",
  "data": {
    "items": [
      {
        "security": {
          "securityId": "sim-600001",
          "fullSymbol": "SH.600001",
          "securityCode": "600001",
          "securityName": "模拟证券600001",
          "exchangeCode": "SH",
          "securityType": "STOCK",
          "boardCode": "MAIN",
          "listingStatus": "LISTED",
          "isSt": false,
          "isSuspended": false,
          "priceScale": 2
        },
        "previousClosePrice": "12.34",
        "openPrice": "12.40",
        "latestPrice": "13.57",
        "highPrice": "13.57",
        "lowPrice": "12.38",
        "changeAmount": "1.23",
        "changeRate": "0.0997",
        "tradeVolume": "48213900",
        "tradeAmount": "628439211.00",
        "turnoverRate": "0.0163",
        "dataTime": "2026-09-18T15:00:00+08:00",
        "serverTime": "2026-09-19T15:48:15+08:00",
        "sequence": "sim-2026-09-18",
        "dataStatus": "REALTIME",
        "delaySeconds": null
      }
    ],
    "page": 1,
    "size": 20,
    "total": 4633,
    "totalPages": 232,
    "hasNext": true,
    "rankingType": "GAINERS",
    "snapshotVersion": "sim-2026-09-18",
    "dataTime": "2026-09-18T15:00:00+08:00",
    "dataStatus": "REALTIME"
  },
  "traceId": "01J7AQ1K8Y4Q9W9GAF2B6CVR6M",
  "timestamp": "2026-09-19T15:48:15.120+08:00"
}
```

**外层字段**

| 字段 | 说明 |
| --- | --- |
| `items` | 当前页 `QuoteSnapshot` 数组 |
| `page` / `size` / `total` / `totalPages` / `hasNext` | 分页信息，与 §26.2 同义 |
| `rankingType` | 回显生效的榜单口径（大写） |
| `snapshotVersion` | 本批次快照版本；见 §8.2 不变式 |
| `dataTime` | 本批次的行情时间；见 §8.2 不变式 |
| `dataStatus` | `REALTIME`（模拟源）；真实源接入后按延迟降级为 `DELAYED` / `STALE` |

**异常**

| 场景 | HTTP | 业务码 |
| --- | --- | --- |
| `rankingType` 缺失 / 空 / 不在枚举内 | 400 | `INVALID_REQUEST` |
| `page < 1`、`size` 越界、`size` 非整数 | 400 | `INVALID_REQUEST` |
| `exchangeCodes` / `boardCodes` / `sectorId` 取值在数据中不存在 | 200 | — （返回空页，`total = 0`） |
| 页码越界（超过 `totalPages`） | 200 | — （返回空页，`total` 仍为真实总数） |

## 6. 排序与筛选规则

### 6.1 排序口径

| `rankingType` | 主排序键 | 方向 | 兜底键 |
| --- | --- | --- | --- |
| `GAINERS` | `changeRate` | 降序 | `security.fullSymbol` 升序 |
| `LOSERS` | `changeRate` | 升序 | `security.fullSymbol` 升序 |
| `TURNOVER` | `tradeAmount` | 降序 | `security.fullSymbol` 升序 |

兜底键**不随主键方向翻转**：它的作用是让同值项有唯一的、与方向无关的先后，
翻转了就失去"稳定"的意义。PRD §7.3 QTE-02 要求「排序稳定」。

### 6.2 筛选顺序

```
exchangeCodes → boardCodes → sectorId → excludeSt → excludeSuspended → 有有效排序键 → 排序 → 分页
```

- `exchangeCodes` / `boardCodes`：**多值取并集，多个参数之间取交集**（PRD QTE-04「多条件取交集」）。
- 大小写不敏感；空串项被忽略（`"SH,,SZ"` 等价于 `"SH,SZ"`）。
- 参数为空或缺失表示该条件不参与筛选。

### 6.3 分页

复用 `stock-common` 的 `PageData.slice(...)` 完成切片与 `total` / `totalPages` / `hasNext` 计算，
再展平成响应对象。分页算术只有一份实现。

## 7. 参数校验

沿用 `SecurityQueryService` 已确立的两条规则：

- **筛选值不校验合法性**：`exchangeCodes=SH` 是合法取值，只是数据里恰好没有——返回空结果，
  语义上诚实。若报 400，就把"没有数据"错报成"参数非法"。
- **枚举与排序字段必须校验**：`rankingType` 被静默忽略时，调用方拿到的是"榜单类型不对
  但看起来正常"的响应，极难排查。因此枚举外的值直接 400，不回落默认值。

## 8. 一致性约束（写成测试）

### 8.1 榜单 ↔ 个股快照

对榜单中任意一行，其 `securityId` 走 `QuoteSnapshotProvider.fetch` 得到的快照，
`latestPrice` / `changeRate` / `tradeVolume` / `tradeAmount` / `turnoverRate` / `dataTime`
必须**逐字段相同**。这是 PRD §7.5 STK-01 验收「行情值与榜单一致」的直接落地。

### 8.2 批次版本自洽

- 响应 `snapshotVersion` == 当前页**每一行**的 `sequence`
- 响应 `dataTime` == 当前页**每一行**的 `dataTime`

模拟实现下一次榜单就是同一交易日的一整批快照，两者本就同源。写成测试是为了在真实
Provider 接入、批次策略发生变化时立刻变红。

### 8.3 榜单 ↔ 市场广度

榜单行的涨跌方向（`changeRate` 符号）必须与 `BreadthCalculator` 对同一批行情的分类一致；
涨停股必须出现在 `GAINERS` 榜且 `changeRate` 等于其涨跌停幅度。
两条口径共用 `LimitRuleMatcher`，这是它们不会分叉的结构性原因。

### 8.4 分页拼接完整

对同一组条件，逐页取完后拼接的结果必须与"不分页取全量"逐元素相同：
无重复、无遗漏、顺序一致。

### 8.5 市场总览榜单预览（本轮一并修复）

`MKT-01` 的 `rankings` 预览改为**投影自 `GAINERS` 榜的前 3 名**，因此：

- 预览行的 `securityId` 必然存在于证券主数据中（修掉首页点击 404）
- 预览行的 `securityName` / `latestPrice` / `changeRate` / `tradeAmount` / `turnoverRate`
  与榜单首页前 3 名一致
- 预览行的 `exchangeCode` 由主数据给出，不再是写死的常量

## 9. 改动清单

### stock-market / domain（新增）

| 文件 | 说明 |
| --- | --- |
| `RankingType.java` | `GAINERS` / `LOSERS` / `TURNOVER`；`code()`、`fromCode(String)`（大小写不敏感，未知返回 `Optional.empty()`）、`order()`（返回 `Comparator<QuoteSnapshot>`，即 §6.1 口径） |
| `StockRanking.java` | 扁平响应记录：`items` + 5 个分页字段 + `rankingType` + `snapshotVersion` + `dataTime` + `dataStatus` |
| `QuoteSnapshotBatchProvider.java` | `@FunctionalInterface`，`List<QuoteSnapshot> fetchBatch(String marketCode)` |

### stock-market / application（新增）

| 文件 | 说明 |
| --- | --- |
| `RankingCriteria.java` | 全部可空 + `empty()`；字段顺序与契约 Query 参数一致 |
| `InvalidRankingQueryException.java` | → 400 `INVALID_REQUEST` |
| `StockRankingQueryService.java` | 校验 → 取批次 → 筛选 → 排序 → 分页 → 组装外层字段 |

### stock-integration（修改）

| 文件 | 说明 |
| --- | --- |
| `SimulatedQuoteSnapshotProvider.java` | 实现 `QuoteSnapshotBatchProvider`；抽出共用的单只装配方法，单查与整批两条路径共用；整批路径用一次 `findAll` 建索引，避免 O(n²) |
| `SimulatedQuoteProvider.java` | `rankings()` 改为投影自榜单批次的前 3 名（§8.5）；构造器新增所需依赖 |

### stock-backend（新增 / 修改）

| 文件 | 说明 |
| --- | --- |
| `RankingController.java` | 新增，`@RequestMapping("/api/v1/stock-rankings")` |
| `BackendConfiguration.java` | 修改：新增 `StockRankingQueryService` Bean；把 `SimulatedQuoteSnapshotProvider` 显式暴露为 `QuoteSnapshotProvider` 与 `QuoteSnapshotBatchProvider` 两个端口（同一个实例，两条路径共用装配） |
| `GlobalExceptionHandler.java` | 修改：新增 `InvalidRankingQueryException` → 400 |

### frontend（修改）

| 文件 | 说明 |
| --- | --- |
| `src/types/domain.ts` | 新增 `RankingType` / `StockRanking` / `RankingQuery`；**先 grep 同名类型**，避免 M2-05 遇到的 TS 声明合并陷阱 |

> 页面接入属 M2-08；本轮只补契约类型，让 M2-08 不必再回头改类型。

### 测试（新增）

| 文件 | 覆盖 |
| --- | --- |
| `stock-market/.../application/StockRankingQueryServiceTest.java` | 三种排序口径与兜底键、多条件交集、筛选值不存在返回空页、`excludeSt` / `excludeSuspended` 默认值、分页越界、参数校验、批次版本自洽（§8.2）、分页拼接完整（§8.4） |
| `stock-integration/.../market/SimulatedQuoteSnapshotBatchProviderTest.java` | 整批与单查逐字段一致（§8.1）、涨跌方向与广度口径一致（§8.3）、涨停股在 `GAINERS` 榜首位 |
| `stock-backend/.../web/RankingControllerContractTest.java` | JSON 字段名与扁平结构、`rankingType` 缺失/非法 → 400、空结果页 `total=0`、页码越界 |

## 10. 验收方式

1. `mvn.cmd -q test`（`JAVA_HOME=D:/idea/JDK17`）后端全量测试通过，基线 241 → 预计 275+
2. 前端 `npx vue-tsc --noEmit` 零错误；`npx vitest --configLoader runner --run` 全绿
3. 手工核对：`GET /api/v1/stock-rankings?rankingType=GAINERS&size=3` 的前 3 名，
   与 `GET /api/v1/markets/overview` 的 `rankings` 预览逐字段相同
4. 手工核对：`GET /api/v1/securities/{securityId}/quote` 对榜单首行的 `securityId`
   返回与榜单行相同的 `latestPrice` / `changeRate`
5. CI 四个作业全绿

## 11. 已知取舍

| # | 取舍 | 理由与代价 |
| --- | --- | --- |
| 1 | 排序 / 筛选 / 分页放在应用层，Provider 只提供整批 | 换来"同一批次"的结构保证与可脱离数据源的排序测试；代价是真实 Provider 若用 SQL 排序，应用层逻辑退化为参考实现 |
| 2 | 响应扁平，不用嵌套 `PageData` | 契约未写明外层形状，按用户决策取扁平；代价是记录里重复 5 个分页字段，故内部仍走 `PageData.slice` 保证分页算术只有一份 |
| 3 | `snapshotVersion` 与行内 `sequence` 相同 | 模拟实现下一次榜单就是同一交易日的一整批快照，两者同源；真实 Provider 批次策略变化时，§8.2 的测试会红 |
| 4 | `excludeSuspended` 默认 `true`，`excludeSt` 默认 `false` | 依 PRD §7.3 QTE-02「停牌和无有效价格默认排除」；PRD 未对 ST 给出默认，故不默认剔除。两个默认值不同是有意为之 |
| 5 | 无有效排序键的行不进榜 | 涨跌幅榜要求该行有涨跌幅；也避免比较器处理 `null`。代价是"成交额榜漏掉某只当日无成交的股票"需要靠 `dataStatus` 与 `total` 而非"全市场证券数"来核对 |
| 6 | `sectorId` 当前恒返回空页 | 板块关系数据在 M2-07 之前不存在，与 STK-02 的处理一致；不报错，因为"该板块下没有证券"在当下是事实 |
| 7 | 本轮一并修复总览榜单预览（§8.5） | 修掉首页点击 404 与"预览与榜单不一致"两个可见缺陷；代价是触碰 M2-04 交付的 `SimulatedQuoteProvider`，其构造器与 Bean 需同步调整 |
