# 市场广度（MKT-03）设计

> 对应任务：M2-02（路线图 `docs/superpowers/plans/2026-09-19-mvp-delivery-roadmap.md`）
> 契约来源：`docs/RESTful-API.md` 第 7 章 MKT-03
> 依赖：M2-01（交易日历与市场状态）

## 1. 背景

MKT-01 的 `breadth` 目前是 `SimulatedQuoteProvider` 里**硬编码**的五个数字（涨 2876 / 跌 1924 / 平 164 / 涨停 82 / 跌停 7）。它有三个问题：

1. **不是算出来的**。涨跌停家数没有经过任何规则判定，改一个数字不需要任何理由。
2. **字段不全**。契约要求 7 个计数（另有 `suspendedCount`、`totalCount`），当前只有 5 个。
3. **口径无法自证**。"同一快照口径"是一句承诺，但没有任何机制保证——只要有人把两个不同批次的数字拼在一起，也没人发现。

本任务要把广度从"编出来的数字"变成"**按规则算出来的结果**"，并让"同一快照口径"由构造保证。

## 2. 目标

- 新增 `GET /api/v1/markets/{marketCode}/breadth`（MKT-03），PUBLIC 权限，可选 `snapshotTime`。
- 广度在**摄入时**对整批个股行情按**限幅规则**分类计数，结果随快照一起持久化。
- 查询时读**同一个快照**，因此"不混用不同批次行情"由构造保证，不靠约定。
- 补全 7 个计数，并明确每个计数的定义与包含关系。
- `snapshotTime` 实现"取不晚于该时刻的最近快照"，真正兑现契约。

## 3. 不在本次范围

- 不读写 `stock_limit_rule` / `stock_security` 表。两张表结构已就绪但库内无数据、无代码引用；规则的落库与证券主数据入库由 M2-04 承担，本次只留端口。
- **不编码"新股上市首日不设涨跌幅"规则**。该规则随板块与时期变化（且各板块表述不一致），我无法核实到可以写进代码的程度。模型与匹配器**保留** `noPriceLimit` 与上市天数窗口能力，并用合成规则做单测覆盖；模拟规则集只填**可核实的稳定规则**。
- 不做停牌复牌时点、退市整理期、风险警示切换等状态时序。
- 不做分交易所 / 分板块的广度拆分（契约只要求市场级聚合）。
- 不修改前端页面。MKT-03 的前端接入属于 M2-08。

## 4. 数据模型

### 4.1 个股行情

```java
record SecurityQuote(
    String securityId, String securityCode, String exchangeCode, String securityName,
    String boardCode,          // MAIN、GEM、STAR、BSE
    String securityType,       // STOCK、ETF、INDEX…
    boolean st,                // 风险警示
    boolean suspended,         // 停牌
    LocalDate listedDate,
    BigDecimal previousClosePrice, BigDecimal latestPrice)
```

### 4.2 限幅规则（镜像 `stock_limit_rule` 表）

```java
record LimitRule(
    String ruleCode, String exchangeCode, String boardCode,
    String securityType, String specialStatus,   // NORMAL、ST
    Integer minListingDays, Integer maxListingDays,   // 上市后自然日窗口，null 表示不限
    BigDecimal upperLimitRate, BigDecimal lowerLimitRate,
    boolean noPriceLimit,
    LocalDate effectiveFrom, LocalDate effectiveTo,   // effectiveTo 为 null 表示当前有效
    int priorityNo)                                    // 数值越小优先级越高
```

`LimitRule` 自带两个派生方法，供匹配器与生成器共用，避免限价算法出现两份实现：

```java
BigDecimal limitUpPrice(BigDecimal previousClose)    // previousClose × (1 + upperLimitRate)，四舍五入到 2 位
BigDecimal limitDownPrice(BigDecimal previousClose)  // previousClose × (1 - lowerLimitRate)，四舍五入到 2 位
```

**限价按"价格"判定而非按"比例"判定**：交易所把限价四舍五入到分后，该价格即为上限。若按比例判定，`10.03 → 11.03` 的涨幅是 `9.97%`，会被误判为非涨停。

### 4.3 广度

```java
record BreadthData(int riseCount, int fallCount, int flatCount, int suspendedCount,
                   int limitUpCount, int limitDownCount) {
    public int totalCount() { return riseCount + fallCount + flatCount + suspendedCount; }
}
```

`totalCount` 由四态相加派生而非单独存储——快照把这四态划分完备，存一份冗余的"总数"只会制造一个可能不一致的第二真相。

## 5. 计数定义

对快照覆盖的每只证券，按以下**优先级**归入唯一一类：

| 顺序 | 条件 | 归类 |
| --- | --- | --- |
| 1 | `suspended == true` | 停牌 → `suspendedCount` |
| 2 | 有匹配规则且非 `noPriceLimit`，且 `latestPrice >= limitUpPrice` | 涨停 → `riseCount` + `limitUpCount` |
| 3 | 有匹配规则且非 `noPriceLimit`，且 `latestPrice <= limitDownPrice` | 跌停 → `fallCount` + `limitDownCount` |
| 4 | `latestPrice > previousClosePrice` | 上涨 → `riseCount` |
| 5 | `latestPrice < previousClosePrice` | 下跌 → `fallCount` |
| 6 | 其余 | 平盘 → `flatCount` |

**关键约定**：涨停**包含在**上涨里（`limitUpCount ⊆ riseCount`），跌停同理。这与市场惯例一致——"涨停家数"是"上涨家数"的子集。

**规则缺失时的处置**：若某只证券匹配不到任何规则（例如新增板块尚无规则），**不计入涨跌停**，但仍按价格计入涨/跌/平。理由：把"无规则"当成"不限幅"会把一只 10% 涨的普通股算成涨停，是更严重的错误。规则缺失属于数据缺口，应在 M2-04 入库时补齐。

## 6. 规则匹配

`LimitRuleMatcher.match(rules, quote, ruleDate)` 从候选规则中选出**唯一适用**的一条：

1. **静态匹配**：`exchangeCode`、`boardCode`、`securityType`、`specialStatus` 全等。
2. **生效窗口**：`effectiveFrom <= ruleDate` 且（`effectiveTo == null` 或 `effectiveTo >= ruleDate`）。
3. **上市天数窗口**：`listingDays = ChronoUnit.DAYS.between(listedDate, ruleDate)`；
   若规则给了 `minListingDays` / `maxListingDays`，则须落在窗口内（自然日，与表注释一致）。
4. **优先级**：满足上述条件的规则中取 `priorityNo` 最小者；仍并列时取 `ruleCode` 字典序最小者，
   保证结果**确定性**（不依赖集合遍历顺序）。

## 7. 模拟数据来源

### 7.1 模拟限幅规则集（`SimulatedLimitRuleProvider`）

只填**可核实的稳定规则**，10 条（5 个板块 × NORMAL/ST）：

| 交易所 | 板块 | special_status | 上下限 |
| --- | --- | --- | --- |
| SH / SZ | MAIN 主板 | NORMAL | ±10% |
| SH / SZ | MAIN 主板 | ST | ±5% |
| SZ | GEM 创业板 | NORMAL / ST | ±20% |
| SH | STAR 科创板 | NORMAL / ST | ±20% |
| BJ | BSE 北交所 | NORMAL / ST | ±30% |

> 创业板 / 科创板的风险警示股仍为 ±20%，故 NORMAL 与 ST 两条规则取值相同但**必须各存一条**——
> 匹配是精确等值匹配，不存就会出现"ST 股匹配不到规则"。

所有规则 `effectiveFrom = 2000-01-01`、`effectiveTo = null`、`noPriceLimit = false`。

### 7.2 模拟个股行情（`SimulatedSecurityQuoteProvider`）

- 生成约 **5400** 只证券（接近真实 A 股上市公司数量），覆盖 SH/SZ/BJ 三所与 MAIN/GEM/STAR/BSE 四板块。
- **完全确定性**：所有取值由证券代码经固定算法导出，不使用随机数、不依赖当前时间。同一输入恒定同一输出。
- 代码段按真实规则分配（`600/601/603/605/688` → SH，`000/001/002/300` → SZ，`430/83/87` → BJ）。
- 约 3% 的主板证券标记为 ST；约 1% 标记为停牌。
- 每只证券先由确定性算法定出一个**目标状态**（涨停/上涨/平盘/下跌/跌停/停牌），再**按匹配到的规则反推价格**：
  涨停股取 `latestPrice = limitUpPrice`，跌停股取 `latestPrice = limitDownPrice`，其余在合理区间内取值。
  这样"生成"与"计数"互为逆运算——`BreadthCalculator` 重新分类的结果必然与目标状态一致，
  该往返一致性由单测直接断言。

## 8. 接口契约

### 8.1 请求

```
GET /api/v1/markets/{marketCode}/breadth
Query: snapshotTime（可选，ISO-8601 日期时间）
```

### 8.2 响应 `data`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `marketCode` | string | 回显市场代码（大写规范化） |
| `riseCount` / `fallCount` / `flatCount` / `suspendedCount` | int | 四态计数，互斥且完备 |
| `limitUpCount` / `limitDownCount` | int | 涨跌停计数，分别是 `riseCount` / `fallCount` 的子集 |
| `totalCount` | int | 四态之和 |
| `dataTime` | datetime | 该批次行情的数据截止时间 |
| `dataStatus` | enum | `REALTIME` / `DELAYED` / `STALE` / `UNAVAILABLE` |
| `lastSuccessfulSyncAt` | datetime | 最近一次成功同步时间 |
| `snapshotVersion` | string | 快照版本，可用于确认是否为同一批次 |

### 8.3 快照解析规则（"同一快照口径"的落地）

```
snapshotTime 缺省：
  ① 读实时存储（Redis）命中 → 原样返回
  ② 未命中 → 读归档最近一条，标记 STALE
  ③ 都没有 → 503 MARKET_DATA_UNAVAILABLE

snapshotTime 给出：
  ① 实时存储命中且 dataTime <= snapshotTime → 原样返回
  ② 否则读归档"不晚于 snapshotTime 的最近一条"，标记 STALE
  ③ 都没有 → 503 MARKET_DATA_UNAVAILABLE
```

所有计数**一律来自这一个快照对象**，因此"不混用不同批次"是结构性保证而非约定。
归档读取一律标记 `STALE`——沿用 MKT-01 已有的单一规则，不为 MKT-03 引入第二套状态语义。

## 9. 改动清单

**新增（`stock-market/domain`）**
- `SecurityQuote.java`、`SecurityQuoteProvider.java`
- `LimitRule.java`、`LimitRuleProvider.java`、`LimitRuleMatcher.java`
- `BreadthCalculator.java`
- `MarketBreadth.java`

**新增（`stock-market/application`）**
- `MarketBreadthQueryService.java`

**新增（`stock-integration/market`）**
- `SimulatedLimitRuleProvider.java`
- `SimulatedSecurityQuoteProvider.java`

**修改**
- `MarketOverview.BreadthData` — 增加 `suspendedCount` 与派生的 `totalCount()`
- `MarketOverviewArchive` — 新增 `findAt` 默认方法（默认退化为"最新且不晚于该时刻"）
- `JdbcMarketOverviewArchive` — 实现 `findAt`（走已有索引 `idx_market_overview_latest`）
- `MarketOverviewQueryService` — 新增带 `snapshotTime` 的重载，原方法行为不变
- `SimulatedQuoteProvider` — 广度改为由个股行情按规则计算，不再硬编码
- `MarketController` — 新增 MKT-03 端点
- `BackendConfiguration` — 装配新端口与用例

**测试**
- `LimitRuleMatcherTest` — 静态匹配、生效窗口、上市天数窗口、优先级与确定性
- `BreadthCalculatorTest` — 六类归类优先级、涨停 ⊆ 上涨、规则缺失处置、往返一致性
- `SimulatedLimitRuleProviderTest`、`SimulatedSecurityQuoteProviderTest` — 确定性与规则往返
- `MarketBreadthQueryServiceTest` — 同一快照口径、`snapshotTime` 回溯、STALE 标记、503
- `JdbcMarketOverviewArchiveTest` — 补 `findAt` 用例
- `MarketControllerContractTest` — 新增 MKT-03 契约用例

## 10. 验收方式

- 单测覆盖六类归类的边界与优先级，以及"规则缺失不计涨跌停"。
- **往返一致性测试**：生成器按目标状态造价格 → 计数器重新分类 → 两者逐只一致。
- 契约测试断言 7 个计数的口径关系（`totalCount` = 四态之和、`limitUpCount <= riseCount`）。
- `snapshotTime` 回溯用例：给出早于当前快照的时刻，必须取到更早的那一批，而不是当前批次。
- 全量回归：`mvn.cmd test`（后端）+ `vitest --run`（前端）。

## 11. 已知取舍

- **广度数字会变化**：从硬编码的 `2876 / 1924 / 164 / 82 / 7` 变为按规则计算的结果。这是本任务的目的，
  不是回归；分布已调到相近量级，避免前端观感突变。
- **规则集不完整**：仅 10 条稳定规则，缺"新股不限幅"等条款。数据缺口在 M2-04 规则入库时补齐，
  匹配器无需改动。
- **`BreadthData.totalCount` 不参与序列化**（`@JsonIgnore`）。快照会被持久化并在读取时反序列化，
  派生字段一旦落盘就会在归档里形成第二个可能与四态不一致的真相；而 `MarketBreadth.totalCount`
  是响应视图、不会被反序列化，因此正常序列化。两者语义一致，只是持久化边界不同。
- **模拟全市场行情每次 `fetch` 都重新生成**（5149 只）。这是纯算术，无 I/O，耗时可忽略；
  代价是摄入与查询不再共享同一批个股明细——广度已随快照落盘，因此不影响"同一快照口径"。
- **`MarketBreadth` 的 `marketCode` 回显快照自身的市场代码**，而不是请求参数的原样字符串：
  它描述的是"这批数字属于哪个市场"，而不是"你问了哪个市场"。

