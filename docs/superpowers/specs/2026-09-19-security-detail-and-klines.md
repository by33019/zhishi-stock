# M2-05 个股快照与日/周/月 K 线 — 设计

> 任务：`TASKS.md` M2-05（P0），依赖 M2-04 ✅
> 接口：`RESTful-API.md` STK-04、STK-07；数据结构 §4.2、§8.2、§8.3
> 需求：PRD §7.5 个股详情 — STK-02/P0（分时，本轮不做）、STK-03/P0（日/周/月 K 线）

## 1. 背景

M2-04 交付了证券主数据（`SecuritySummary`、`SecurityMasterProvider`、`SecurityQueryService`），
但那只解决了"**找到**一只证券"，没有解决"**看**这只证券"。

个股详情页首屏需要两样东西，当前都缺失：

1. **实时行情头部** —— 现价、涨跌、开高低、成交量额、换手率、数据时间与状态（STK-04）；
2. **日/周/月 K 线主图** —— 一段区间的 OHLC 与量额序列（STK-07）。

现有 `SecurityQuote` 无法承担这个职责。它的类注释写明是"市场广度计数的输入单位"，
字段只有 `previousClosePrice` 与 `latestPrice`——广度计数只需要这两个数就能分类涨跌停。
往它身上加开高低、量额、换手率，会让一个纯计数输入模型变成半成品行情模型，
后续每个消费方都要重新判断"哪些字段在这里是有意义的"。

## 2. 目标

- 交付 `GET /api/v1/securities/{securityId}/quote`（STK-04，PUBLIC）
- 交付 `GET /api/v1/securities/{securityId}/klines`（STK-07，PUBLIC）
- 新增领域端口与模拟实现，真实数据源后置替换时不改用例层
- 保证**同一只证券的价格在"市场广度""个股快照""日 K 末端"三处完全一致**

## 3. 不在范围

| 项 | 归属 |
| --- | --- |
| STK-03 证券详情基础资料（`listedDate` / `delistedDate` / `lotSize` / `primarySector`） | 未排期 |
| STK-06 分时图（分钟级 OHLCV） | PRD §7.5 列为 P0，但 `TASKS.md` 的 M2 未列此任务；本轮不做，需单独排期 |
| STK-05 批量查询 | M2-06 / M2-07 落地后再定 |
| 前复权 / 后复权与除权除息 | PRD 明确纳入 V1.2 |
| 前端页面接入 | M2-08 |
| K 线落库（`stock_kline_day`） | 本轮按需生成；落库待真实数据源接入 |

## 4. 数据来源

### 4.1 落地方式：按需确定性生成（内存，不落库）

沿用 M2-01 ~ M2-04 的可插拔模拟源模式。**但 K 线有一个本质区别：不能预生成。**

全市场 5149 只 × 5 年 × 约 250 交易日 ≈ **640 万点**，内存里既存不下也不该存。
因此 K 线 Provider 是**按需**的：给定 `securityId` + 区间，只算该证券该区间的点
（默认区间 120 个交易日 → 每次约 120 个点）。

代价是每次请求都要走一遍 `SecurityQuoteProvider.fetchUniverse`（5149 只）来定位目标证券。
这与 M2-04 的 `SimulatedSecurityMasterProvider` 同构，已有 P95 < 500ms 的性能证据支撑。

### 4.2 价格生成的锚定策略：末端锚定 + 倒推

K 线序列必须满足一个硬约束：

> **最近交易日的收盘价 == 该证券快照的 `latestPrice`**

否则用户会看到"头部显示 1580.00，K 线最后一点却是 1543.20"这种自相矛盾的画面。
而 `latestPrice` 由 `SimulatedSecurityQuoteProvider` 按涨跌停规则反推得出，**不可改**——
它是广度计数的输入，改了会让 M2-02 的涨跌停计数失效。

因此价格序列采用**从最近交易日向前倒推**：

```
close(last) = latestPrice                       // 末端锚定
close(d)    = close(d+1) / (1 + rate(securityId, d+1))   // 逐日倒推
rate(s, d)  ∈ (-3%, +3%)，由 (securityId, d) 经 SplitMix64 确定性导出
```

- ✅ **同一时刻自洽**：同一次查询内，任意区间的重叠部分价格完全一致（倒推起点相同）
- ✅ **末端与快照一致**：`close(last)` 恒等于 `latestPrice`
- ⚠️ **跨交易日会漂移**：`last` 前移一天，同一历史日期的价格会随之变化

最后一条是**有意接受的取舍**，见 §11。

### 4.3 为什么不用"固定锚点正推"

固定锚点（如 2015-01-05 + 确定性起始价）正推能让历史价格永久稳定，
但序列末端是一个与 `latestPrice` 无关的值——要么接受头部与 K 线不一致，
要么在末端强行覆盖造成跳变。两者都比"跨日漂移"更难解释，也更难在测试里表达。

### 4.4 与既有模拟源的一致性

| 数据 | 来源 | 一致性要求 |
| --- | --- | --- |
| 证券身份 | `SecurityMasterProvider`（投影行情全集） | 同一 `securityId` 的 `SecuritySummary` 与 M2-04 逐字段相同 |
| 前收价 / 最新价 / ST / 停牌 | `SecurityQuoteProvider.fetchUniverse` | **不重新生成**，直接取用 |
| 涨跌停价 | `LimitRuleProvider` + `LimitRuleMatcher` | 用于夹取 `high`/`low`，与广度计数同一套规则 |
| 交易日 | `TradingCalendarProvider` | 决定 K 线的日期轴，不用自然日 |

## 5. 接口契约

### 5.1 STK-04 `GET /api/v1/securities/{securityId}/quote`

权限 `PUBLIC`。Path 参数 `securityId`（系统稳定主键，不用 `securityCode` 代替）。

返回 `QuoteSnapshot`，字段与 `RESTful-API.md` §4.2 逐一对齐：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `security` | `SecuritySummary` | 证券摘要，与 M2-04 同源 |
| `previousClosePrice` | decimal-string | 前收价 |
| `openPrice` | decimal-string | 开盘价 |
| `latestPrice` | decimal-string | 最新价 |
| `highPrice` | decimal-string | 最高价 |
| `lowPrice` | decimal-string | 最低价 |
| `changeAmount` | decimal-string | 涨跌额 |
| `changeRate` | decimal-string | 涨跌幅（小数比例，`0.10` = 10%） |
| `tradeVolume` | integer-string | 成交量，股 |
| `tradeAmount` | decimal-string | 成交额，元 |
| `turnoverRate` | decimal-string | 换手率（小数比例） |
| `dataTime` | datetime | 行情时间（= 最近交易日收盘时刻） |
| `serverTime` | datetime | 服务时间（由 `Clock` 注入，可测） |
| `sequence` | string | 快照批次号，同一交易日全部证券相同 |
| `dataStatus` | enum | 复用 `MarketOverview.DataStatus` |
| `delaySeconds` | integer/null | 实时数据为 `null` |

**数值一律用十进制定点数字符串**，与 `MarketOverview.QuoteRow`、`TurnoverTrend.Point` 一致。
前端不做浮点运算，字符串是唯一能保证"展示值 == 服务端值"的载体。

**停牌证券**：返回最近有效价格，`security.isSuspended = true`，**不得将价格置零**（§8.3）。

### 5.2 STK-07 `GET /api/v1/securities/{securityId}/klines`

权限 `PUBLIC`。

| 参数 | 必填 | 取值 | 默认 |
| --- | --- | --- | --- |
| `period` | 是 | `DAY` / `WEEK` / `MONTH` | — |
| `startDate` | 否 | `yyyy-MM-dd` | 最近 120 个交易日前 |
| `endDate` | 否 | `yyyy-MM-dd` | 最近交易日 |
| `adjustment` | 否 | `NONE` | `NONE` |

返回 `KlineSeries`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `security` | `SecuritySummary` | 证券摘要 |
| `period` | enum | 回显生效周期 |
| `adjustment` | enum | 回显生效复权方式 |
| `dataCutoffAt` | datetime | 数据可信边界 = 最后一个点位的时刻（沿用 M2-03 原则） |
| `dataStatus` | enum | 数据状态 |
| `points` | `KlinePoint[]` | K 线点，按时间升序 |

`KlinePoint` 字段与 §8.2 逐一对齐：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `time` | date | 交易日 `yyyy-MM-dd`（周/月 K 取**该周期最后一个交易日**） |
| `openPrice` | decimal-string | 开盘价 |
| `highPrice` | decimal-string | 最高价 |
| `lowPrice` | decimal-string | 最低价 |
| `closePrice` | decimal-string | 收盘价 |
| `previousClosePrice` | decimal-string | 前收价 |
| `changeAmount` | decimal-string | 涨跌额 |
| `changeRate` | decimal-string | 涨跌幅 |
| `tradeVolume` | integer-string | 成交量，股 |
| `tradeAmount` | decimal-string | 成交额，元 |
| `turnoverRate` | decimal-string | 换手率 |
| `qualityStatus` | enum | `VALID` / `DELAYED` / `CORRECTED` |

## 6. K 线生成与聚合规则

### 6.1 日 K 单日 OHLC

```
close        = 倒推序列（§4.2）
previousClose = 前一交易日的 close（区间首日由确定性函数导出）
open          = previousClose 与 close 之间的确定性插值
high          = max(open, close) + 确定性上影（夹到涨停价）
low           = min(open, close) - 确定性下影（夹到跌停价）
tradeVolume   = 确定性导出（股）
tradeAmount   = tradeVolume × 均价，均价取 (high + low + close) / 3
turnoverRate  = 确定性导出，落在 0.1% ~ 15%
```

`high` / `low` 必须被夹在涨跌停价之内——否则会出现"K 线最高价超过涨停价"这种
一眼假的数据，且与 M2-02 的涨跌停计数口径冲突。

### 6.2 周 K / 月 K 由日 K 聚合

**不独立生成**，一律由日 K 聚合，保证两种视图永远自洽：

| 字段 | 聚合规则 |
| --- | --- |
| `time` | 该周期内**最后一个交易日** |
| `openPrice` | 该周期内第一个交易日的 `openPrice` |
| `closePrice` | 该周期内最后一个交易日的 `closePrice` |
| `highPrice` | 该周期内 `highPrice` 的最大值 |
| `lowPrice` | 该周期内 `lowPrice` 的最小值 |
| `tradeVolume` / `tradeAmount` | 该周期内求和 |
| `previousClosePrice` | 该周期前一个交易日的 `closePrice` |

- **周**：按 ISO 周（周一至周日）分组，只用**实际有交易日的周**，空周不产生点。
- **月**：按自然月分组，只用实际有交易日的月。
- **不使用自然日空值补齐**（PRD 明确）。

`time` 取"该周期最后一个交易日"而非首日或自然月末——与 M2-03 的
"点位取区间结束时刻"同一原则：这样 `dataCutoffAt` 天然等于最后一个点的时刻，
不会出现"数据截止到 9/19，最后一点却标着 9/15"的矛盾。

## 7. 参数校验

| 情况 | 处置 | 错误码 |
| --- | --- | --- |
| `securityId` 不存在 | 404 | `SECURITY_NOT_FOUND` |
| `period` 缺失或不在白名单 | 400 | `INVALID_REQUEST` |
| `adjustment` 为 `FORWARD` / `BACKWARD` | 400 | `ADJUSTMENT_NOT_SUPPORTED` |
| `startDate` > `endDate` | 400 | `INVALID_REQUEST` |
| 日 K 跨度 > 10 年 | 400 | `KLINE_RANGE_TOO_LARGE` |
| 周/月 K 跨度 > 20 年 | 400 | `KLINE_RANGE_TOO_LARGE` |
| 日期格式非法 | 400 | `INVALID_REQUEST` |

**`adjustment` 传不支持的值必须报错，不能静默替换成 `NONE`**——§8.3 明确要求
"不支持的复权方式返回明确错误而非静默替换"。调用方以为拿到了前复权数据、
实际拿到的是不复权数据，是最难排查的一类问题（同 M2-03 的 `interval` 处理）。

Controller 层参数一律声明为 `String`，校验集中在用例层——枚举绑定失败会被 Spring
转成 `MethodArgumentTypeMismatchException`，把"取值不在白名单"混同为"参数格式错误"。

## 8. 与既有能力的一致性约束（写成测试）

以下四条是**硬约束**，每条都要有对应测试，否则会静默漂移：

1. **快照 ↔ 广度**：`QuoteSnapshot.latestPrice` == `SecurityQuoteProvider.fetchUniverse`
   中该证券的 `latestPrice`。同一价格不能在市场总览与个股详情显示成两个数。
2. **日 K 末端 ↔ 快照**：日 K 区间包含最近交易日时，最后一根的 `closePrice` == `latestPrice`。
3. **周/月 K ↔ 日 K**：把周 K 的每个点与同期日 K 的最后一天对比，`closePrice` 必须相等。
4. **停牌不置零**：停牌证券的 `latestPrice` == `previousClosePrice` 且非 `"0.00"`。

## 9. 改动清单

### stock-market / domain（新增）

| 文件 | 职责 |
| --- | --- |
| `QuoteSnapshot.java` | §4.2 契约 |
| `QuoteSnapshotProvider.java` | 端口：`Optional<QuoteSnapshot> fetch(String securityId, String marketCode)` |
| `KlinePeriod.java` | `DAY` / `WEEK` / `MONTH` + `fromCode` |
| `KlineAdjustment.java` | `NONE` + `fromCode`（返回 `Optional`，非法值由用例层拒绝） |
| `KlineQualityStatus.java` | `VALID` / `DELAYED` / `CORRECTED` |
| `KlinePoint.java` | §8.2 契约 |
| `KlineSeries.java` | K 线响应体 |
| `KlineRequest.java` | 查询参数打包（securityId / marketCode / period / startDate / endDate / adjustment） |
| `KlineProvider.java` | 端口：`Optional<KlineSeries> fetch(KlineRequest request)` |

### stock-market / application（新增）

| 文件 | 职责 |
| --- | --- |
| `SecurityDetailQueryService.java` | STK-04 + STK-07 用例，含全部参数校验 |
| `SecurityNotFoundException.java` | → 404 |
| `InvalidKlineParameterException.java` | → 400 |
| `AdjustmentNotSupportedException.java` | → 400 |

### stock-integration（新增）

| 文件 | 职责 |
| --- | --- |
| `SimulatedPriceSeries.java` | 共享的确定性价格算法（倒推序列、OHLC 派生、量额换手率） |
| `SimulatedQuoteSnapshotProvider.java` | 投影行情全集 + `SimulatedPriceSeries` 派生快照 |
| `SimulatedKlineProvider.java` | 按需生成日 K，并聚合周/月 K |

`SimulatedPriceSeries` 单独抽出，是为了让快照与 K 线**共用同一份价格算法**——
若两边各写一遍，改一处就会让"头部价格"与"K 线末端"分叉，且没有任何测试会红。

### stock-backend

| 文件 | 改动 |
| --- | --- |
| `web/SecurityController.java` | 新增两个端点 |
| `config/BackendConfiguration.java` | 装配 `QuoteSnapshotProvider` / `KlineProvider` / `SecurityDetailQueryService` |
| `web/GlobalExceptionHandler.java` | 新增 404 / 400 映射 |

`SecurityConfiguration` **无需改动**：`/api/v1/securities/**` 已在 M2-04 放行。

### frontend

| 文件 | 改动 |
| --- | --- |
| `src/types/domain.ts` | 新增 `QuoteSnapshot` / `KlinePeriod` / `KlineAdjustment` / `KlinePoint` / `KlineSeries` / `KlineQuery` 类型（页面接入归 M2-08） |

### 测试（新增）

| 文件 | 覆盖 |
| --- | --- |
| `SecurityDetailQueryServiceTest` | 参数校验、404、复权拒绝、区间上限、默认区间 |
| `SimulatedQuoteSnapshotProviderTest` | 确定性、与广度口径一致、停牌不置零 |
| `SimulatedKlineProviderTest` | 末端锚定、聚合正确性、周/月边界、涨跌停夹取、不补齐空周 |
| `SecurityControllerContractTest` | 两个端点的契约（JSON 字段名、状态码） |

## 10. 验收方式

- 后端全量测试通过（当前 189 → 预计 230+），`BUILD SUCCESS`
- 新增测试覆盖 §8 的四条一致性约束
- 前端 `typecheck` 0 错误、既有 37 测试全绿
- 手工验证（dev server）：`GET /api/v1/securities/sim-600519/quote` 与
  `GET /api/v1/securities/sim-600519/klines?period=DAY` 返回真实 JSON，
  且 `quote.latestPrice` == `klines.points[last].closePrice`
- 确定性验证：同一请求连续两次返回完全相同的 JSON

## 11. 已知取舍

| 取舍 | 原因 | 影响 |
| --- | --- | --- |
| **K 线跨交易日会漂移** | 价格序列末端锚定 `latestPrice`，倒推起点随最近交易日前移 | 同一历史日期的价格今天查与明天查可能不同。模拟数据阶段可接受；接入真实数据源后自然消失 |
| 每次请求全量扫描 5149 只定位证券 | `SecurityQuoteProvider` 是 `@FunctionalInterface`，只有 `fetchUniverse` | 已有 P95 < 500ms 证据；真实数据源接入时改为按主键查询 |
| 换手率是确定性生成值 | 无流通股本数据 | 与真实换手率无对应关系，仅保证量级合理 |
| 不落库 | 与 M2-01~M2-04 一致 | 进程重启后数据不变（确定性生成），但无法验证 SQL 层 |
| 分时图不做 | `TASKS.md` M2 未列此任务 | PRD 把分时列为 P0，需单独排期，当前是**已知缺口** |
