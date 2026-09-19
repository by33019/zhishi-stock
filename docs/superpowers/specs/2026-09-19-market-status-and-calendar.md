# 交易日历与市场状态（MKT-02）设计

> 对应任务：M2-01（路线图 `docs/superpowers/plans/2026-09-19-mvp-delivery-roadmap.md`）
> 契约来源：`docs/RESTful-API.md` 第 7 章 MKT-02

## 1. 背景

`/markets/overview`（MKT-01）已能用确定性模拟 Provider 返回市场总览，但其中的 `marketStatus` 是硬编码的粗粒度状态（`NORMAL` 场景恒为 `TRADING`，`CLOSED` 场景恒为 `CLOSED`），既不知道当天是不是交易日，也无法区分集合竞价、连续竞价与午间休市。

前端 `MarketPage` 需要向用户展示"现在处于哪个交易时段""下一个时段何时开始"，资讯与 AI 研究链路也需要"数据截止时间落在哪个时段"作为口径依据。因此需要一个可判定交易时段、可判定非交易日的市场状态接口。

## 2. 目标

- 新增 `GET /api/v1/markets/{marketCode}/status`（MKT-02），PUBLIC 权限。
- 正确区分：盘前、开盘集合竞价、上午连续竞价、午间休市、下午连续竞价、收盘集合竞价、收盘。
- 非交易日可判：返回 `isTradingDay=false` 且 `sessionStatus=CLOSED`，不视为数据异常。
- 交易日历作为**端口**（`TradingCalendarProvider`）落地，当前用确定性模拟实现，真实数据源就位后只替换实现类。
- MKT-01 与 MKT-02 共用同一个 `sessionStatus` 枚举，避免语义分裂。

## 3. 不在本次范围

- 不读写 `stock_trade_calendar` 表。表结构已在 V3 就绪，但库内无数据、无代码引用；日历入库（抓取 + 落库 + 增量同步）由后续任务承担，本次只留端口。
- 不改造 MKT-01 的响应结构与行为。MKT-01 的 `marketStatus` 取值仍为 `TRADING` / `CLOSED` / `BREAK`，仅把类型换成共享枚举（枚举为超集，JSON 输出逐字不变）。
- 不引入真实节假日数据源。模拟实现不硬编码未经核实的法定节假日日期，改由配置项注入。
- 不做集合竞价撤单时段（09:20–09:25）等更细粒度的撮合规则区分。
- 不修改前端页面。MKT-02 的前端接入属于后续任务。
- 不新增数据库迁移（本次不需要新表新列）。

## 4. 领域模型

### 4.1 两个粒度的时段概念

| 概念 | 类型 | 取值 | 用途 |
| --- | --- | --- | --- |
| `sessionStatus` | `MarketSessionStatus` | `PRE_OPEN`、`CALL_AUCTION`、`TRADING`、`BREAK`、`CLOSED` | 粗粒度市场状态，供前端做样式与文案分支 |
| `currentSession` | `TradingSession` | `PRE_OPEN`、`OPENING_CALL_AUCTION`、`MORNING_CONTINUOUS`、`LUNCH_BREAK`、`AFTERNOON_CONTINUOUS`、`CLOSING_CALL_AUCTION`、`CLOSED` | 细粒度时段阶段，供文案精确描述 |

之所以拆成两个：`sessionStatus` 要跨市场（未来港股、美股）保持稳定且取值少，便于前端做视觉分支；`currentSession` 需要精确到"A 股上午连续竞价"这类描述，取值随交易所规则变化。二者是聚合与明细的关系。

### 4.2 A 股（CN）时段窗口

| 时段 | 时间区间 | `currentSession` | 对应 `sessionStatus` |
| --- | --- | --- | --- |
| 盘前 | 00:00 – 09:15 | `PRE_OPEN` | `PRE_OPEN` |
| 开盘集合竞价 | 09:15 – 09:25 | `OPENING_CALL_AUCTION` | `CALL_AUCTION` |
| 竞价后静默 | 09:25 – 09:30 | `PRE_OPEN` | `PRE_OPEN` |
| 上午连续竞价 | 09:30 – 11:30 | `MORNING_CONTINUOUS` | `TRADING` |
| 午间休市 | 11:30 – 13:00 | `LUNCH_BREAK` | `BREAK` |
| 下午连续竞价 | 13:00 – 14:57 | `AFTERNOON_CONTINUOUS` | `TRADING` |
| 收盘集合竞价 | 14:57 – 15:00 | `CLOSING_CALL_AUCTION` | `CALL_AUCTION` |
| 收盘后 | 15:00 – 24:00 | `CLOSED`（无窗口，兜底） | `CLOSED` |

窗口为**左闭右开**：`start <= t < end`。00:00 至 15:00 之间窗口首尾相接、不留缝隙，保证任意时刻落到唯一时段；15:00 之后不再定义窗口，由解析逻辑按 `CLOSED` 兜底。之所以不给收盘后定义显式窗口，是为了让"收盘"与"日历异常"共用同一条兜底路径，不产生第二个真相。

非交易日：`isTradingDay=false`，`currentSession=CLOSED`，`sessionStatus=CLOSED`，窗口列表为空。

### 4.3 记录结构

```java
record TradingSessionWindow(TradingSession session, LocalTime start, LocalTime end)
record TradingCalendarDay(LocalDate tradeDate, boolean tradingDay,
                          LocalDate previousTradeDate, LocalDate nextTradeDate,
                          List<TradingSessionWindow> windows, OffsetDateTime sourceTime)
record MarketStatus(String marketCode, LocalDate tradeDate, boolean tradingDay,
                    MarketSessionStatus sessionStatus, TradingSession currentSession,
                    OffsetDateTime nextSessionAt, OffsetDateTime calendarSourceTime)
```

`MarketStatus` 即 MKT-02 的响应体，字段名与契约逐字对应。

## 5. 接口契约

### 5.1 请求

```
GET /api/v1/markets/{marketCode}/status
Query: date（可选，ISO-8601 日期，缺省为当前交易日）
```

### 5.2 响应

沿用统一返回壳 `ApiResponse{success,code,message,data,traceId,timestamp}`。`data` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `marketCode` | string | 回显请求的市场代码 |
| `tradeDate` | date | 查询日对应的交易日 |
| `isTradingDay` | boolean | 是否交易日 |
| `sessionStatus` | enum | `PRE_OPEN` / `CALL_AUCTION` / `TRADING` / `BREAK` / `CLOSED` |
| `currentSession` | enum | 细粒度时段，见 4.1 |
| `nextSessionAt` | datetime | 下一个时段开始时间；当日已收盘或非交易日时为下一交易日的首个时段开始时间 |
| `calendarSourceTime` | datetime | 日历数据的来源时间 |

### 5.3 时段与 `nextSessionAt` 推导规则

**时段推导**：只有当查询日等于"今天"时才按当前时刻匹配窗口；查询历史日期或未来日期时整日按 `CLOSED` 返回。理由是那些日期的盘中状态无法由当前时刻还原，返回一个看似实时的假状态比返回 `CLOSED` 更有害。

**`nextSessionAt` 推导**（仅在查询日等于"今天"时计算，否则为 `null`）：

1. 交易日且当日仍有尚未开始的**非盘前**时段 → 取**当天最近的未来时段**开始时间（跳过 `PRE_OPEN` 占位窗口，因为它不是真正的交易时段）。
2. 交易日但当日已无后续时段（如已收盘）→ 取**下一交易日的首个交易时段**开始时间。
3. 非交易日 → 取**下一交易日的首个交易时段**开始时间。
4. 日历未给出下一交易日，或下一交易日窗口为空 → 返回 `null`，不抛异常。

### 5.4 错误处理

| 场景 | HTTP | `code` |
| --- | --- | --- |
| 正常 | 200 | `SUCCESS` |
| `marketCode` 不受支持（MVP 仅 `CN`） | 404 | `MARKET_NOT_FOUND` |
| `date` 格式非法 | 400 | 由既有 `GlobalExceptionHandler` 统一处理 |

`marketCode` 比较大小写不敏感；响应中回显规范化后的大写值。

## 6. 数据来源

新增端口 `TradingCalendarProvider`（`stock-market/domain`）：

```java
Optional<TradingCalendarDay> find(String marketCode, LocalDate date);
```

当前唯一实现 `SimulatedTradingCalendarProvider`（`stock-integration/market`）：

- 交易日判定：周一至周五，且不在注入的节假日集合内。
- 节假日集合由构造参数注入，来源为配置项 `stock.market.holidays`（逗号分隔 ISO 日期，默认空）。
  不硬编码法定节假日日期——未核实的数据不应写进代码，真实日历由后续真实 Provider 提供。
- 支持任意日期（含未来），保证确定性：同一输入恒定产出同一结果。
- `previousTradeDate` / `nextTradeDate` 由交易日判定向前/向后推导，上限 366 天（超出返回 `null`，避免死循环）。
- `sourceTime` 取当日 00:00（`Asia/Shanghai`），便于测试断言。

## 7. 改动清单

**新增（`stock-market`）**
- `domain/MarketSessionStatus.java`
- `domain/TradingSession.java`
- `domain/TradingSessionWindow.java`
- `domain/TradingCalendarDay.java`
- `domain/TradingCalendarProvider.java`
- `domain/MarketStatus.java`
- `application/MarketStatusQueryService.java`
- `application/MarketNotFoundException.java`
- 测试：`application/MarketStatusQueryServiceTest.java`

**新增（`stock-integration`）**
- `market/SimulatedTradingCalendarProvider.java`
- 测试：`market/SimulatedTradingCalendarProviderTest.java`

**修改**
- `stock-market/domain/MarketOverview.java` — 删除嵌套 `SessionStatus`，改用共享 `MarketSessionStatus`
- `stock-integration/market/SimulatedQuoteProvider.java` — 跟随枚举迁移
- `stock-backend/web/MarketController.java` — 新增 status 端点
- `stock-backend/config/BackendConfiguration.java` — 装配 Provider 与 Service
- `stock-backend/web/GlobalExceptionHandler.java` — 映射 `MarketNotFoundException` → 404
- `stock-backend/src/test/.../MarketControllerContractTest.java` — 新增 status 契约用例
- 前端 `frontend/src/types/domain.ts` — `marketStatus` 联合类型放宽为 5 值

## 8. 验收方式

- 单测：`MarketStatusQueryServiceTest` 覆盖 8 个时段边界 + 周末 + 节假日 + `nextSessionAt` 四种推导分支。
- 单测：`SimulatedTradingCalendarProviderTest` 覆盖交易日判定、前后交易日推导、确定性。
- 契约测试：`MarketControllerContractTest` 断言状态码、统一返回壳、字段与取值；不受支持市场返回 404。
- 全量回归：`mvn.cmd test`（后端）+ `vitest --run`（前端类型放宽后无破坏）。
