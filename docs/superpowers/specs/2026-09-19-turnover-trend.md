# 成交趋势（MKT-04）设计

> 对应任务：M2-03（路线图 `docs/superpowers/plans/2026-09-19-mvp-delivery-roadmap.md`）
> 契约来源：`docs/RESTful-API.md` 第 7 章 MKT-04
> 依赖：M2-01（交易日历与市场状态）

## 1. 背景

MKT-01 的 `turnover` 是一个硬编码的折线（`amount`、`previousAmount`、8 个点）。它只是"首页那张图能画出来"，
既没有档位概念，也没有"这个点是什么时刻"的信息。MKT-04 要把成交趋势做成一个**可查询、口径明确**的接口：
三档粒度、点位带时间、单位显式声明、并给出数据可信边界。

## 2. 目标

- 新增 `GET /api/v1/markets/{marketCode}/turnover-trend`（MKT-04），PUBLIC 权限。
- `range` 三档：`TODAY`（分钟粒度）、`5D`、`20D`（日粒度）。
- 盘中 `interval` 可选，取值 `1m|5m|15m|30m|60m`。
- 响应含 `unit`（单位声明）与 `dataCutoffAt`（数据可信边界）。
- 交易日与时段判定**复用** M2-01 的 `TradingCalendarProvider`，不另造一套日历。

## 3. 不在本次范围

- **不读写 `stock_minute_bar` / `stock_kline_day` 表**。这两张表是**证券级**（`security_id`），
  市场级趋势需要对全市场证券跨表聚合（约 120 万行/交易日），MVP 阶段代价与收益不匹配。
  个股级分钟线/日 K 的落库与读取由 M2-05 承担；本次只留端口。
- 不做集合竞价的单独建模：开盘集合竞价（09:15–09:25）与收盘集合竞价（14:57–15:00）的成交
  并入其相邻的连续竞价点，不单列。
- 不做跨市场（`marketCode` 非 `CN`）的趋势。
- 不修改前端页面。MKT-04 的前端接入属于 M2-08；本次只补 `domain.ts` 类型。

## 4. 数据模型

```java
record TurnoverTrend(
    String marketCode,
    String range,                    // "TODAY" / "5D" / "20D"
    String interval,                 // 盘中档位非空；跨日档位为 null
    Unit unit,
    OffsetDateTime dataCutoffAt,
    List<Point> points) {

    record Unit(String tradeAmount, String tradeVolume) {
        static Unit standard() { return new Unit("CNY", "SHARE"); }
    }

    record Point(String time, String tradeAmount, String tradeVolume) {}
}

enum TurnoverRange { TODAY("TODAY", 1, true), FIVE_DAYS("5D", 5, false), TWENTY_DAYS("20D", 20, false) }
```

**为什么 `time` 是 `String`**：契约里它是"datetime/date —— 分钟时间或交易日"，随 `range` 变化，
不存在单一 Java 时间类型能同时精确表达两者。用 `String` 并在生成处显式格式化
（盘中 `ISO_OFFSET_DATE_TIME`、日粒度 `ISO_LOCAL_DATE`），比引入联合类型或统一成某个近似时刻更诚实。
`tradeAmount` / `tradeVolume` 用字符串同样是既有约定（十进制定点数字符串，避免浮点误差）。

**为什么 `unit` 是对象而不是字符串**：响应同时含金额与数量两个量，单个字符串无法承载两种单位。
按字段拆开声明，前端不必靠猜。

**为什么 `TurnoverRange` 的常量名不是 `5D`**：Java 枚举常量不能以数字开头，`5D` / `20D`
只能作为 `code` 存在。接口对外暴露的始终是 `code`。

## 5. 点位语义（本设计的核心）

| 档位 | `time` 格式 | 数值含义 | `interval` |
| --- | --- | --- | --- |
| `TODAY` | `2026-09-11T09:31:00+08:00` | **从开盘累计到该时刻**的成交额/量，单调不减 | 非空，缺省 `1m` |
| `5D` / `20D` | `2026-09-11` | 该交易日**全天**的成交额/量 | `null` |

**点位的 `time` 取"区间结束时刻"而非"区间开始时刻"**。这样每个点的时间就是它累计值的生效时刻，
`dataCutoffAt` 天然等于最后一个点的时间，不需要额外定义。于是 1 分钟粒度的首个点是 `09:31`
（表示"截至 09:31 的累计成交"），最后一小时粒度的点落在 `15:00`。

**只返回已经走完的区间**，不返回未完成分钟的伪数据（与 STK-06 的口径一致）。因此
`interval=5m` 在 10:02 只返回 6 个点（截至 `10:00`），不返回 `10:05` 那个残缺桶。

**`dataCutoffAt` 恒等于最后一个点位的时间**——趋势数据的可信边界就是它最后一个点，
不另立一个可能与之矛盾的时间字段。

## 6. 分钟区间与权重模型

分钟区间取**连续竞价**的两个时段：`09:30–11:30`、`13:00–15:00`，共 240 分钟。
粒度 `interval=n` 的边界为该时段起点后第 `n, 2n, 3n …` 分钟。

单分钟权重按 A 股实际的"开盘与尾盘放量、午间清淡"特征构造：

```
weight(i) = 24 + 60/(1 + i/5) + 60/(1 + (239-i)/5) + (1 + mix(i) % 12)
            基准    开盘衰减项        收盘衰减项            确定性抖动
```

**全程整数运算，刻意避开浮点**：`Math.sin` / `Math.exp` 允许跨平台 1 ulp 差异，
会让"确定性"这句话在 CI 与本机之间失效。

累计值按 **`全天总量 × 累计权重 / 总权重`** 计算，而不是把每分钟的值逐个相加——
后者会因逐项截断而丢失末位，导致收盘点与日粒度的同一天对不上。

## 7. 交易日与兜底规则

```
range=TODAY：
  ① 今日是交易日且已走完至少一个区间 → 用今日，返回已走完的区间
  ② 否则（盘前 / 非交易日 / 刚开盘）→ 回落到最近一个交易日，返回它的全天序列

range=5D / 20D：
  起点 = 最近一个「已经收盘」的交易日（盘中与盘前都会回退到上一个交易日）
  向前取 N 个交易日，按时间升序返回
```

两条兜底规则的理由：

- 规则 ② 沿用 MKT-01「非交易日返回最近有效收盘快照」的既有语义，避免盘前请求得到一条空曲线。
- 日粒度排除**仍在运行中的当日**：把一个半天的成交量画进日线图，看起来就是一次虚假的缩量。
  收盘后它自然进入日粒度序列，且数值与盘中曲线的终点一致（由单测断言）。

**"是否已收盘"从日历推导**（`TradingCalendarDay.lastSessionEnd()`），不在调用方硬编码 15:00。
为此给 `TradingCalendarDay` 补了一个与既有 `firstSessionStart()` 对称的 `lastSessionEnd()`。

## 8. 接口契约

### 8.1 请求

```
GET /api/v1/markets/{marketCode}/turnover-trend
Query: range=TODAY|5D|20D（必填）、interval（可选）
```

`range` 与 `interval` 在 Controller 层刻意声明为 `String` 而非枚举：非法取值必须返回业务码
`INVALID_REQUEST`，而枚举绑定失败会被 Spring 转成 `MethodArgumentTypeMismatchException`，
把"取值不在白名单"混同为"参数格式错误"。校验由用例层承担。

### 8.2 响应 `data`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `marketCode` | string | 回显市场代码（大写规范化） |
| `range` | enum | `TODAY` / `5D` / `20D` |
| `interval` | string/null | 盘中档位非空；跨日档位为 `null` |
| `unit` | object | `{tradeAmount:"CNY", tradeVolume:"SHARE"}` |
| `dataCutoffAt` | datetime | 等于最后一个点位的时间 |
| `points[]` | array | `time`、`tradeAmount`、`tradeVolume` |

### 8.3 参数校验与错误

| 情形 | 结果 |
| --- | --- |
| `range` 缺失或不在白名单 | 400 `INVALID_REQUEST` |
| `interval` 不在 `1m\|5m\|15m\|30m\|60m` | 400 `INVALID_REQUEST` |
| 日粒度档位传了 `interval` | 400 `INVALID_REQUEST`（**不静默忽略**） |
| 不受支持的市场 | 404 `MARKET_NOT_FOUND` |

日粒度档位传 `interval` 选择报错而非忽略：客户端以为参数生效、实际被丢掉，是比报错更糟的结果。
这与文档对 STK-07 的要求（"不支持的复权方式返回明确错误而非静默替换"）同源。

## 9. 改动清单

**新增（`stock-market/domain`）**
- `TurnoverRange.java`、`TurnoverTrend.java`、`TurnoverTrendProvider.java`

**新增（`stock-market/application`）**
- `TurnoverTrendQueryService.java`、`InvalidTurnoverParameterException.java`

**新增（`stock-integration/market`）**
- `SimulatedTurnoverTrendProvider.java`

**修改**
- `TradingCalendarDay` — 新增 `lastSessionEnd()`
- `MarketController` — 新增 MKT-04 端点
- `GlobalExceptionHandler` — `InvalidTurnoverParameterException` → 400 `INVALID_REQUEST`
- `BackendConfiguration` — 装配 `TurnoverTrendProvider` 与用例
- 前端 `types/domain.ts` — 新增 `TurnoverRange`、`TurnoverTrend`、`TurnoverTrendUnit`、`TurnoverTrendPoint`

**测试**
- `TurnoverTrendQueryServiceTest` — 三档解析、`interval` 缺省/白名单/日粒度拒绝、市场 404
- `SimulatedTurnoverTrendProviderTest` — 时段边界、午休跳过、桶完整性、累计单调、兜底规则、日粒度排除盘中、确定性
- `MarketControllerContractTest` — MKT-04 契约用例 +3（成功、400、404）

## 10. 验收方式

- 单测覆盖 09:30/11:30/13:00/15:00 四个时段边界，以及盘前与周末两条兜底路径。
- **往返一致性**：今日曲线的终点必须等于同一天在日粒度上的那个点。
- 契约测试断言 `unit`、`dataCutoffAt` 与点位数组逐字段格式。
- 全量回归：`mvn.cmd test`（后端）+ `vitest --run`（前端）。

## 11. 已知取舍

- **日粒度不含当日盘中**：见 §7。代价是盘中查 `5D` 时最后一天是昨天，与 `TODAY` 的"今天"不同日；
  这是刻意的，避免在日线图上画出半天数据。
- **集合竞价不单独建模**：开盘与收盘集合竞价的成交并入相邻连续竞价点。真实数据源接入后，
  若需要单列集合竞价，`Point` 结构无需变化，只需调整边界生成。
- **`TurnoverTrendProvider` 用 `Optional.empty()` 同时表达"市场不受支持"**。
  当前模拟实现只会对不受支持的市场返回空；真实数据源接入后，
  「市场存在但当前无趋势数据」需要区分成 503，届时再扩展端口语义。
- **`TODAY` 与 `MKT-01` 的 `turnover` 是两套数据**：MKT-01 的 `turnover` 仍是首页聚合用的简化结构，
  本次不动它。前端把首页折线切到 MKT-04 属于 M2-08 的范围。
