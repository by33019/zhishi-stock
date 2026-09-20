# M2-07 板块排行、详情与成分股 — 设计

> 任务：`TASKS.md` M2-07（P1），依赖 M2-04 ✅、M2-06 ✅
> 接口：`RESTful-API.md` §10 SEC-01 / SEC-02 / SEC-03 / SEC-04 / SEC-06；行类型 §4.1 / §4.2；分页 §3.5
> 需求：PRD §7.4 SEC-01 / SEC-02 / SEC-03（均为 P0）

## 1. 背景

M2-04 交付了证券主数据（5149 只）与搜索/列表，M2-05 交付了个股快照与 K 线，M2-06 交付了全市场榜单。
这三轮都绕开了同一个洞：**板块**。

- `GET /sectors`、`/sector-rankings`、`/sectors/{id}`、`/sectors/{id}/quote`、`/sectors/{id}/constituents` **全部不存在**。
- 前端 `/sectors` 与 `/sectors/:id` 两页仍读 `mockApi` 常量。
- STK-02 与 QTE-01 的 `sectorId` 参数虽然存在，但**在用例层被硬编码短路为"返回空页"**
  （`SecurityQueryService` 与 `StockRankingQueryService` 各一处 `List.of()`）。
  当时的理由是"板块关系数据不存在"——这个理由在本轮之后不再成立，两处短路必须一并拆掉。

板块是"从市场下钻到个股"的中间层：PRD §7.4 SEC-03 要求「从板块下钻到标的」，
所以本轮不只是新增 5 个接口，还要把 `sectorId` 变成**真实可用的筛选键**。

## 2. 目标

- 交付 §10 的 SEC-01、SEC-02、SEC-03、SEC-04、SEC-06 五个接口（PUBLIC）
- 板块行情由**成分股行情聚合**得出，含领涨股与领跌股（PRD：「领涨股为有效成分股中涨幅最高者」）
- 新增领域端口 `SectorProvider` 与确定性模拟实现，真实数据源后置替换时不改用例层
- 拆掉 STK-02 / QTE-01 的 `sectorId` 短路，改为按成分关系真实筛选
- 保证**同一只证券在"板块成分股"与"个股快照"两处给出同一套行情事实**

## 3. 不在范围

| 项 | 归属 |
| --- | --- |
| SEC-05 `GET /sectors/{sectorId}/trend`（板块走势） | 契约列于 §10，但 `TASKS.md` M2-07 的范围是「排行、详情与成分股」。走势需要按成分股聚合出时间序列，属独立增量；记入 `PROJECT_STATUS.md` 已知问题 |
| SEC-07 `GET /sectors/{sectorId}/news` | 依赖资讯域（M3-04 资讯 Provider）；本轮无资讯数据可关联 |
| 板块 AI 解读（PRD SEC-04） | 依赖 AI 编排（M3-06 / M3-07） |
| 板块数据落库（`stock_sector` / `stock_security_sector`） | 与 M2-01~M2-06 一致：模拟 Provider 内存生成，表结构已就绪但继续空置，入库随真实数据源接入一并处理 |
| 前端 `/sectors`、`/sectors/:id` 接入 | M2-08 |
| 板块 Excel 导出 | M3-12 |

## 4. 数据来源

### 4.1 落地方式：确定性模拟 Provider（内存，不落库）

新增端口 `SectorProvider`，实现 `SimulatedSectorProvider`。

**证券身份投影自 `SecurityMasterProvider`，不自己定义证券全集。** 这与 M2-04 的理由相同：
代码段一旦在两处各写一遍，"板块成分"与"广度计数"就会指向不同的证券全集，且没有任何测试会红。

**成分关系只由证券代码导出，与列表顺序无关。** 用 `securityCode` 的哈希而非"在全集中的序号"
决定归属：序号是列表的属性，代码是证券的属性；前者一旦生成顺序变化，同一只证券就会悄悄换板块。

### 4.2 板块集合（39 个）

| 类型 | 数量 | 说明 |
| --- | --- | --- |
| `INDUSTRY` 一级大类 | 5 | 金融、科技、制造与周期、消费与医药、民生与公用；`levelNo=1`、`parentId=null` |
| `INDUSTRY` 二级行业 | 20 | 银行/证券/保险、半导体/消费电子/软件服务/通信设备、电力设备/汽车整车/基础化工/有色金属/煤炭开采/石油石化、医药生物/医疗器械/食品饮料/家用电器、房地产开发/交通运输/公用事业；`levelNo=2`、`parentId` 指向所属大类 |
| `CONCEPT` | 8 | 人工智能、国产替代、高股息、新能源、数据中心、军民融合、消费复苏、一带一路 |
| `REGION` | 6 | 长三角、珠三角、京津冀、成渝、中部地区、东北地区 |

- `sectorId` = `sim-bk` + 4 位序号（`sim-bk0001`…`sim-bk0039`）
- `sectorCode` = `BK` + 4 位序号（`BK0001`…`BK0039`）
- 编号顺序为「5 个大类 → 20 个行业 → 8 个概念 → 6 个地域」，**父级先于子级**出现，
  因此 `sectorCode` 的字典序与层级顺序一致（`BK0001` 金融 → `BK0006` 银行）
- 全部 `status = ACTIVE`（`INACTIVE` 分支由桩数据在单测中覆盖，见 §8.4）

板块名称是**合成数据集的一部分**，与 `模拟证券600000` 同性质：全部为模拟数据，不代表真实映射。

### 4.3 成分关系规则（确定性）

| 关系 | 基数 | `relationType` | `isPrimary` | 规则 |
| --- | --- | --- | --- | --- |
| 二级行业 | 恰好 1 个 | `PRIMARY` | `true` | `floorMod(hash(code, 11), 20)` |
| 一级大类 | 恰好 1 个 | `SECONDARY` | `false` | 所属二级行业的 `parentId` |
| 概念 | 0 或 1 个 | `MEMBER` | `false` | `floorMod(hash(code, 23), 100) < 25` |
| 地域 | 恰好 1 个 | `SECONDARY` | `false` | `floorMod(hash(code, 37), 6)` |

- 生效窗口统一为 `effectiveFrom = 2010-01-04`（与 `SimulatedSecurityQuoteProvider.EARLIEST_LISTING` 同日）、`effectiveTo = null`（当前有效）。
- 由此每只证券平均有 1 + 1 + 0.25 + 1 ≈ 3.25 条关系，一级大类约 1029 只、二级行业约 257 只、概念约 1287 只。

### 4.4 与既有模拟源的一致性

| 事实 | 唯一来源 |
| --- | --- |
| 证券身份（代码、名称、交易所、板块、ST、停牌） | `SecurityMasterProvider` |
| 个股行情（前收、最新价、开高低、量额、换手率、涨跌停价） | `QuoteSnapshotBatchProvider`（即 `SimulatedQuoteSnapshotProvider`） |
| 板块集合与成分关系 | `SectorProvider` |

板块**不新增任何行情生成逻辑**，只是把已有整批快照按成分关系分组后聚合。
因此"成分股行情 ↔ 个股快照 ↔ 榜单"对同一只证券给出同一套事实，是构造保证而非约定。

## 5. 接口契约

### 5.1 SEC-01 `GET /api/v1/sectors`

**Query 参数**

| 参数 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `sectorType` | 否 | 全部 | `INDUSTRY` \| `CONCEPT` \| `REGION`，大小写不敏感；白名单外报 400 |
| `parentId` | 否 | 全部 | 父板块 ID；筛选值不存在 → 空结果 |
| `keyword` | 否 | 全部 | 对 `sectorCode` / `sectorName` 做**包含**匹配，大小写不敏感 |
| `status` | 否 | `ACTIVE` | `ACTIVE` \| `INACTIVE`；白名单外报 400 |

**响应（HTTP 200）**

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "查询成功",
  "data": {
    "items": [
      {
        "sectorId": "sim-bk0006",
        "sectorCode": "BK0006",
        "sectorName": "银行",
        "sectorType": "INDUSTRY",
        "parentId": "sim-bk0001",
        "levelNo": 2
      }
    ]
  },
  "traceId": "01J7AQ1K8Y4Q9W9GAF2B6CVR6M",
  "timestamp": "2026-09-20T14:20:00.000+08:00"
}
```

> 契约 §10 只写「返回板块数组」。选 `data.items` 而不是让 `data` 直接是数组：
> 与 STK-01 的 `data.items` 一致，且将来要加 `total` 之类的字段不必改变外层类型。
> 板块不排序之外的额外字段一律不加——`status` 只参与筛选，不进 JSON（见 §7.3）。

### 5.2 SEC-02 `GET /api/v1/sector-rankings`

**Query 参数**

| 参数 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `sectorType` | 否 | 全部 | 同上 |
| `rankingType` | 否 | `GAINERS` | `GAINERS` \| `LOSERS` \| `TURNOVER`，大小写不敏感；白名单外报 400 |
| `page` | 否 | `1` | `< 1` 报 400 |
| `size` | 否 | `20` | 1 至 100，越界报 400 |

**响应（HTTP 200，`data` 为扁平对象）**

```json
{
  "data": {
    "items": [
      {
        "sectorId": "sim-bk0006",
        "sectorCode": "BK0006",
        "sectorName": "银行",
        "sectorType": "INDUSTRY",
        "companyCount": 257,
        "averagePrice": "16.42",
        "changeRate": "0.0183",
        "tradeVolume": "27418200000",
        "tradeAmount": "451973800000.00",
        "leadingStock": {
          "security": { "securityId": "sim-600001", "fullSymbol": "SH.600001", "...": "..." },
          "latestPrice": "13.57",
          "changeRate": "0.0997"
        },
        "laggingStock": { "security": { "...": "..." }, "latestPrice": "9.11", "changeRate": "-0.0988" },
        "dataTime": "2026-09-18T15:00:00+08:00",
        "dataStatus": "REALTIME"
      }
    ],
    "page": 1,
    "size": 20,
    "total": 39,
    "totalPages": 2,
    "hasNext": true,
    "sectorType": null,
    "rankingType": "GAINERS",
    "snapshotVersion": "sim-2026-09-18",
    "dataTime": "2026-09-18T15:00:00+08:00",
    "dataStatus": "REALTIME"
  }
}
```

排序口径与 QTE-01 同构：

| `rankingType` | 主排序键 | 方向 | 兜底键 |
| --- | --- | --- | --- |
| `GAINERS` | `changeRate` | 降序 | `sectorCode` 升序 |
| `LOSERS` | `changeRate` | 升序 | `sectorCode` 升序 |
| `TURNOVER` | `tradeAmount` | 降序 | `sectorCode` 升序 |

无有效 `changeRate` / `tradeAmount` 的板块**不进榜**（`INACTIVE` 板块一律不进榜）。

### 5.3 SEC-03 `GET /api/v1/sectors/{sectorId}`

返回板块主数据、父级板块、当前统计。

```json
{
  "data": {
    "sector": { "sectorId": "sim-bk0006", "sectorCode": "BK0006", "sectorName": "银行", "sectorType": "INDUSTRY", "parentId": "sim-bk0001", "levelNo": 2 },
    "parent": { "sectorId": "sim-bk0001", "sectorCode": "BK0001", "sectorName": "金融", "sectorType": "INDUSTRY", "parentId": null, "levelNo": 1 },
    "quote": { "...同 SEC-04..." }
  }
}
```

- `parent` 在 `parentId` 为 `null`（一级板块）或父板块不存在时为 `null`。
- **已停用板块仍返回 200**（契约：「已停用板块可返回历史状态但不进入当前排行」）。
- 契约的「数据状态字段」由 `quote.dataStatus` 承载，不再在 `Sector` 上重复一份。

### 5.4 SEC-04 `GET /api/v1/sectors/{sectorId}/quote`

返回 §5.2 中 `items[]` 的同一个 `SectorQuote` 对象（顶层 `data`）。字段：
`sectorId`、`sectorCode`、`sectorName`、`sectorType`、`companyCount`、`averagePrice`、`changeRate`、
`tradeVolume`、`tradeAmount`、`leadingStock`、`laggingStock`、`dataTime`、`dataStatus`。

### 5.5 SEC-06 `GET /api/v1/sectors/{sectorId}/constituents`

**Query 参数**

| 参数 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `effectiveDate` | 否 | 当前有效 | `yyyy-MM-dd`；格式非法报 400 |
| `rankingType` | 否 | `GAINERS` | 成分股排序口径；白名单外报 400 |
| `page` | 否 | `1` | `< 1` 报 400 |
| `size` | 否 | `20` | 1 至 100，越界报 400 |

**响应（HTTP 200，标准 `PageData` 形状 §3.5）**

```json
{
  "data": {
    "items": [
      {
        "quote": { "security": { "...": "..." }, "latestPrice": "13.57", "...": "..." },
        "relationType": "PRIMARY",
        "isPrimary": true,
        "contributionRank": 1
      }
    ],
    "page": 1,
    "size": 20,
    "total": 257,
    "totalPages": 13,
    "hasNext": true
  }
}
```

- `contributionRank`：该成分股在板块内按 `changeRate` 降序的序号（从 1 开始）；
  无有效行情的成分股（停牌等）排在有效项之后，按 `fullSymbol` 升序续号。
  **它不受 `rankingType` 影响**——贡献度是数据属性，不是排序结果。
  `contributionRank = 1` 即领涨股，与 SEC-04 的 `leadingStock` 指向同一只证券。
- 契约写「`PageData<QuoteSnapshot>`；每项附 `relationType`、`isPrimary`、`contributionRank`」。
  形状选**嵌套 `quote`**：把 16 个快照字段摊平到每项上，需要再造一个 19 字段的记录，
  于是 `QuoteSnapshot` 的字段增删要同步两处，且不会有任何测试变红。

### 5.6 异常

| 场景 | HTTP | 业务码 |
| --- | --- | --- |
| `sectorType` / `status` / `rankingType` 缺失或不在白名单、`page` / `size` 越界、`effectiveDate` 格式非法 | 400 | `INVALID_REQUEST` |
| `sectorId` 不存在 | 404 | `SECTOR_NOT_FOUND` |
| SEC-04 / SEC-06 的目标板块已停用 | 404 | `SECTOR_INACTIVE` |
| SEC-06 的板块存在但无有效成分关系 | 404 | `SECTOR_CONSTITUENTS_MISSING` |
| 板块有成分但无任何可统计行情（成分股全部停牌） | 503 | `SECTOR_QUOTE_NOT_AVAILABLE` |
| `parentId` / `keyword` 无匹配 | 200 | —（空结果） |

三条 404 共用一个异常类（`SectorNotFoundException`），业务码由异常自身携带——
与 `InvalidKlineParameterException` 同构：HTTP 状态相同，只有业务码不同。

## 6. 板块统计口径

对某板块的**有效成分股快照**集合：

| 字段 | 口径 |
| --- | --- |
| `companyCount` | 全部有效成分股数量（**含停牌**） |
| `averagePrice` | 非停牌成分股 `latestPrice` 的**简单平均**（等权），2 位小数 |
| `changeRate` | 非停牌成分股 `changeRate` 的**简单平均**（等权），4 位小数 |
| `tradeVolume` | 非停牌成分股 `tradeVolume` 之和 |
| `tradeAmount` | 非停牌成分股 `tradeAmount` 之和 |
| `leadingStock` | 非停牌成分股中 `changeRate` 最高者；同值取 `fullSymbol` 升序 |
| `laggingStock` | 非停牌成分股中 `changeRate` 最低者；同值取 `fullSymbol` 升序 |
| `dataTime` / `dataStatus` | 取自整批快照（与 QTE-01 同源） |

**停牌股计入 `companyCount` 但不参与价格与量额统计**：停牌没有有效价格，把它的前收价
混进均价会让板块均价失真；而"该板块有几只成分股"是成分事实，与停牌无关。

**为什么用等权平均而不是市值加权**：模拟数据源没有总股本字段，编一个股本数会让
"板块涨跌幅"变成两个编造数相乘的结果。等权口径下 `changeRate` 就是成分股涨跌幅的平均，
可被逐项复核。真实数据源接入后若改用加权口径，`SectorQuoteCalculator` 是唯一改动点。

**为什么 `averagePrice` / `changeRate` 可为 `null`**：板块全部成分股停牌时，
"平均涨跌幅"没有定义。补 `0` 会被读成"板块平盘"——PRD §7.4 SEC-02 明确「历史断点不得补 0」。

**量额用 `BigDecimal` 累加，不用 `long`**：`tradeVolume` 是 `integer-string`、
`tradeAmount` 是 `decimal-string`，两者都可能超出 `long` 的安全表达范围（`tradeAmount` 带小数）。
统一走 `BigDecimal` 后按契约格式化为字符串。

## 7. 关键设计决策

### 7.1 板块聚合放在 domain 的纯函数里

`SectorQuoteCalculator.calculate(sector, constituents, dataTime, dataStatus)` 只依赖
`List<QuoteSnapshot>`，不依赖任何 Provider。于是"口径"可以被逐条断言，
不必先造一份 5149 只的模拟数据；SEC-02 / SEC-03 / SEC-04 三处共用同一份实现，
不会各自演化出"均价算不算停牌股"这种差异。

### 7.2 成分关系索引只建一次

`SectorProvider.memberships(marketCode, effectiveDate)` **一次返回全部关系并按 `sectorId` 分组**，
而不是"每个板块查一次"。SEC-02 要对 39 个板块各取一次成分，逐板块查询会把
5149 只证券的关系表重算 39 遍；QTE-01 的 `sectorId` 筛选同样受益。

`effectiveDate` 为 `null` 表示「按当前有效关系解析」——由实现决定基准日，
用例层不必为了取一个日期而额外依赖交易日历。

### 7.3 `Sector.status` 不进 JSON

`Sector` 记录的 `status` 组件标 `@JsonIgnore`：契约 §10 的 SEC-01 只列出 6 个字段，
而 `status` 在服务端是**筛选与排序规则**的输入（INACTIVE 不进排行、SEC-04/06 报 `SECTOR_INACTIVE`），
不是展示字段。与 M2-04 的 `pinyin` / `pinyinAbbr` 同一处理方式。

### 7.4 拆掉 STK-02 / QTE-01 的 `sectorId` 短路

两处原先写死 `List.of()`，理由已随本轮消失。改为按 `SectorMembershipIndex` 筛选：
- `SecurityQueryService.list`：`sectorId` 命中成分集合才保留
- `StockRankingQueryService.rank`：同上，在交易所/板块筛选之后、排序之前

两个服务共用 `SectorMembershipIndex`，不各写一遍"怎么算成分"。
既有测试 `returnsEmptyPageForSectorId` 随之改写为"不存在的板块 ID 返回空页" +
"存在的板块 ID 返回该板块成分"，后者才是它本该断言的行为。

### 7.5 `SectorRanking` 与 `StockRanking` 同构，但**不共用记录**

两者字段形状几乎一样，但一个是 `QuoteSnapshot` 列表、一个是 `SectorQuote` 列表。
抽一个泛型 `RankedPage<T>` 会让两个响应类型互相耦合，且泛型记录在 Jackson 上的行为
需要额外验证。选择各写一个 11 字段记录，代价是重复，收益是两条链路可以独立演进。

## 8. 一致性约束（写成测试）

### 8.1 成分股 ↔ 个股快照

SEC-06 任意一行的 `quote`，与该 `securityId` 走 `QuoteSnapshotProvider.fetch` 得到的快照
**逐字段相同**。这是 PRD §7.5 STK-01「行情值与榜单一致」在板块链路上的延伸。

### 8.2 成分股 ↔ 板块统计

- `companyCount` == SEC-06 的 `total`
- `leadingStock.security.securityId` == SEC-06 中 `contributionRank = 1` 的 `securityId`
- 当板块无停牌成分股时，`changeRate` == SEC-06 全部成分股 `changeRate` 的算术平均

### 8.3 `sectorId` 筛选 ↔ 成分关系

对任意板块 S，QTE-01（`sectorId=S`、`size=100`、`excludeSt=false`、`excludeSuspended=false`）
与 STK-02（`sectorId=S`）返回的证券集合，必须与 SEC-06 在相同条件下列出的成分集合**相同**。
三条链路共用 `SectorProvider` 与 `SectorMembershipIndex`，这是它们不会分叉的结构性原因。

### 8.4 停用板块规则（桩数据覆盖）

模拟数据全部为 `ACTIVE`，因此 INACTIVE 分支无法用真实数据触发。用桩 `SectorProvider`
注入一个 `INACTIVE` 板块，断言：
- SEC-02 排行中不出现该板块
- SEC-03 对该板块返回 200
- SEC-04 / SEC-06 对该板块返回 404 `SECTOR_INACTIVE`

### 8.5 分页拼接完整

SEC-02 与 SEC-06 逐页取完后拼接的结果，必须与"不分页取全量"逐元素相同：无重复、无遗漏、顺序一致。

## 9. 改动清单

### stock-market / domain（新增）

| 文件 | 说明 |
| --- | --- |
| `SectorType.java` | `INDUSTRY` / `CONCEPT` / `REGION`；`code()`、`fromCode()`（大小写不敏感，未知返回空） |
| `Sector.java` | `sectorId`、`sectorCode`、`sectorName`、`sectorType`、`parentId`、`levelNo` + `@JsonIgnore status` |
| `SectorMember.java` | `securityId`、`sectorId`、`relationType`、`isPrimary`、`effectiveFrom`、`effectiveTo` |
| `SectorMembershipIndex.java` | 由分组关系构建；`securityIdsOf(sectorId)`；`isEmpty(sectorId)` |
| `SectorProvider.java` | 端口：`findAll(marketCode)`、`memberships(marketCode, effectiveDate)` |
| `SectorLeaderStock.java` | `security`（`SecuritySummary`）+ `latestPrice` + `changeRate` |
| `SectorQuote.java` | SEC-02 行 / SEC-03 / SEC-04 的统计对象（13 字段） |
| `SectorQuoteCalculator.java` | §6 口径的唯一实现（纯函数） |
| `SectorRanking.java` | 扁平分页响应（11 字段） |
| `SectorDetail.java` | `sector` + `parent` + `quote` |
| `SectorConstituent.java` | `quote` + `relationType` + `isPrimary` + `contributionRank` |
| `SectorList.java` | SEC-01 响应：`items` |

### stock-market / application（新增 / 修改）

| 文件 | 说明 |
| --- | --- |
| `SectorCriteria.java` | SEC-01 条件（`sectorType`、`parentId`、`keyword`、`status`） |
| `SectorRankingCriteria.java` | SEC-02 条件（`sectorType`、`rankingType`、`page`、`size`） |
| `ConstituentCriteria.java` | SEC-06 条件（`effectiveDate`、`rankingType`、`page`、`size`） |
| `InvalidSectorQueryException.java` | → 400 `INVALID_REQUEST` |
| `SectorNotFoundException.java` | → 404，业务码由异常携带（`SECTOR_NOT_FOUND` / `SECTOR_INACTIVE` / `SECTOR_CONSTITUENTS_MISSING`） |
| `SectorQuoteNotAvailableException.java` | → 503 `SECTOR_QUOTE_NOT_AVAILABLE` |
| `SectorQueryService.java` | SEC-01 / SEC-03 / SEC-04 / SEC-06 |
| `SectorRankingQueryService.java` | SEC-02 |
| `SecurityQueryService.java` | **修改**：拆掉 `sectorId` 短路，改用 `SectorMembershipIndex` |
| `StockRankingQueryService.java` | **修改**：同上 |

### stock-integration（新增 / 修改）

| 文件 | 说明 |
| --- | --- |
| `SimulatedSectorProvider.java` | 新增：39 个板块 + 确定性成分关系，投影自 `SecurityMasterProvider` |
| `SimulatedHashing.java` | 新增：`mix(long)`（SplitMix64 收尾混合）的唯一实现 |
| `SimulatedSecurityQuoteProvider.java` | **修改**：删除私有 `mix`，改用 `SimulatedHashing` |
| `SimulatedPriceSeries.java` | **修改**：同上 |

### stock-backend（新增 / 修改）

| 文件 | 说明 |
| --- | --- |
| `SectorController.java` | 新增，5 个端点 |
| `BackendConfiguration.java` | 修改：新增 `SectorProvider` / `SectorQueryService` / `SectorRankingQueryService` Bean；`StockRankingQueryService` / `SecurityQueryService` 构造同步 |
| `GlobalExceptionHandler.java` | 修改：`InvalidSectorQueryException` → 400、`SectorNotFoundException` → 404（用自带业务码）、`SectorQuoteNotAvailableException` → 503 |
| `SecurityConfiguration.java` | 修改：放开 QTE-01 与 SEC-01~06 的 GET。契约把这六个接口标为 `PUBLIC`，而此前只有 `/markets/**` 与 `/securities/**` 被显式放开，`/api/v1/stock-rankings` 一直落到 `anyRequest().authenticated()`——游客打开榜单页会 401 |

### frontend（修改）

| 文件 | 说明 |
| --- | --- |
| `src/types/domain.ts` | 新增 `SectorType`、`Sector`、`SectorListQuery`、`SectorQuote`、`SectorLeaderStock`、`SectorRanking`、`SectorRankingQuery`、`SectorDetail`、`SectorConstituent`、`ConstituentQuery`；**原型 `SectorQuote` 改名 `MockSectorQuote`**（避免 TS 声明合并，同 M2-05 的 `MockKlinePoint`） |

> 页面接入属 M2-08；本轮只补契约类型。

### 测试（新增 / 修改）

| 文件 | 覆盖 |
| --- | --- |
| `domain/SectorQuoteCalculatorTest.java` | §6 全部口径：停牌计入 `companyCount` 但不参与统计、量额用 `BigDecimal`、领涨/领跌 tie-break、全停牌返回 `null` |
| `application/SectorQueryServiceTest.java` | SEC-01 筛选与 `status` 默认、SEC-03 父级与停用规则、SEC-04 停用/无行情、SEC-06 分页与 `contributionRank`、参数校验 |
| `application/SectorRankingQueryServiceTest.java` | 三种排序口径与兜底键、INACTIVE 不进榜、无排序键不进榜、分页拼接完整 |
| `application/SecurityQueryServiceTest.java` / `StockRankingQueryServiceTest.java` | **修改**：`sectorId` 短路 → 真实筛选 |
| `integration/market/SimulatedSectorProviderTest.java` | 板块数量与类型分布、关系基数（行业/大类/地域恰好 1、概念 0 或 1）、与代码而非序号相关、生效窗口、确定性 |
| `backend/web/SectorControllerContractTest.java` | JSON 字段名与结构、5 个端点、400 / 404 / 503 业务码 |
| `backend/web/RankingControllerContractTest.java` / `SecurityControllerContractTest.java` | **修改**：`sectorId` 行为变更 |

## 10. 验收方式

1. `mvn.cmd -q test`（`JAVA_HOME=D:/idea/JDK17`）后端全量测试通过，基线 286 → **实测 338 通过**
   （另有 1 项 `InfrastructureIntegrationTest` 依赖 Testcontainers，本机未启动 Docker 守护进程时无法执行，CI 覆盖）
2. 前端 `npx vue-tsc --noEmit` 零错误；`npx vitest --configLoader runner --run` 全绿（13 文件 / 37 项）
3. 手工核对：`GET /api/v1/sector-rankings?rankingType=GAINERS&size=3` 的首行板块，
   其 `leadingStock.security.securityId` 出现在 `GET /api/v1/sectors/{sectorId}/constituents`
   的 `contributionRank = 1` 行上，且两者 `changeRate` 相同
4. 手工核对：`GET /api/v1/stock-rankings?rankingType=GAINERS&sectorId=sim-bk0006&size=100`
   的证券集合与 `GET /api/v1/sectors/sim-bk0006/constituents?size=100` 的成分集合一致
5. CI 四个作业全绿

## 11. 已知取舍

| # | 取舍 | 理由与代价 |
| --- | --- | --- |
| 1 | 板块与成分关系不落库 | 与 M2-01~M2-06 一致，真实数据源接入后统一入库；代价是 `stock_sector` / `stock_security_sector` 继续空置 |
| 2 | 成分关系由 `securityCode` 哈希导出 | 与列表顺序解耦；代价是关系看起来"随机"，无法与任何真实行业分类对照 |
| 3 | 等权平均而非市值加权 | 模拟源没有总股本，编一个会让板块涨跌幅变成两个编造数相乘；代价是接入真实数据源后口径需要重定义 |
| 4 | 停牌股计入 `companyCount` 但不参与统计 | 停牌没有有效价格；代价是 `companyCount` 与 `averagePrice` 的分母不同，前端若用 `companyCount` 反推均值会算错（已写入契约说明） |
| 5 | SEC-06 每项嵌套 `quote` 而非摊平 | 避免再造一个 19 字段记录并让 `QuoteSnapshot` 有两处定义；代价是与 QTE-01 的 `items[]` 形状不一致 |
| 6 | `SectorQuote` 一份记录服务 SEC-02/03/04 | 同一份统计只定义一次；代价是 SEC-02 的响应比契约多一个 `laggingStock` 字段（超集） |
| 7 | 模拟源的停牌股仍带非零成交量（M2-05 遗留） | 板块统计按"停牌不计入成交"处理，绕开该问题而不改动 M2-05 的 Provider；真实数据源接入后自然消失 |
| 8 | SEC-05 走势与 SEC-07 资讯本轮不做 | 超出 `TASKS.md` M2-07 的范围；记入 `PROJECT_STATUS.md` 已知问题 |
| 9 | 抽取 `SimulatedHashing` 并回改两个既有 Provider | 避免 `mix` 出现第三份拷贝；改动是纯搬移，既有确定性测试即为回归保护 |
