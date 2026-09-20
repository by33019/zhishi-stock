# M2-11 总览板块预览真实化 — 设计

> 对应契约：`docs/RESTful-API.md` MKT-01（`sectors[]` 预览段）与 §10 SEC-02 / SEC-03。
> 关闭 M2-10 spec 的「不在本轮范围」第 1 项：总览快照写死的板块预览导致首页三张卡片 404。

## 1. 背景

### 1.1 现状

`SimulatedQuoteProvider` 里总览的板块预览是一段写死的常量：

```java
private static List<SectorQuote> sectors() {
    return List.of(
            new SectorQuote("bk-ai", "BK-AI", "人工智能", "0.0342", "126800000000", "中科曙光", 68),
            new SectorQuote("bk-chip", "BK-CHIP", "半导体", "0.0286", "105400000000", "北方华创", 81),
            new SectorQuote("bk-broker", "BK-BROKER", "证券", "0.0231", "87600000000", "东方财富", 50));
}
```

而真实的板块源 `SimulatedSectorProvider` 生成的是 `sim-bk0001`…`sim-bk0039`（`BK0001`…`BK0039`），
其中**没有任何一个**是 `bk-ai` / `bk-chip` / `bk-broker`。

`MarketOverview.vue` 把这七个字段直接当成跳转依据：

```html
<RouterLink v-for="(sector, index) in market.sectors" :to="`/sectors/${sector.sectorId}`">
```

后端 `SectorQueryService.detail(sectorId)` → `require(sectorId)` 在 `sectorProvider.findAll("CN")` 里查不到就抛
`SectorNotFoundException` → **首页三张板块卡片点进去全部 404**。

这与 M2-06 修掉的 `stock-600519` 是同一类缺陷：**写死的标识符被别处当作真实主键解析**。
M2-10 已把判据写明（`TASKS.md` 取舍 7）：

> 写死的数值可以是模拟数据；写死的标识符只要需要被别处解析，就是缺陷。

### 1.2 为什么数值也是错的，而不只是 ID

即使不点进去，预览的三个数字也与系统其它部分对不上：

| 预览字段 | 写死值 | 真实板块源会给出的值 |
| --- | --- | --- |
| `sectorName` | 人工智能 / 半导体 / 证券 | 这三个名称确实存在（`sim-bk0025` / `sim-bk0007` / `sim-bk0004`），但 ID 不同 |
| `changeRate` | 0.0342 / 0.0286 / 0.0231 | 由 `SectorQuoteCalculator` 对成分股等权平均得到，与 SEC-02 榜首一致 |
| `companyCount` | 68 / 81 / 50 | 该板块的成分事实（含停牌），由成分关系投影得出 |
| `leadingStock` | 中科曙光 / 北方华创 / 东方财富 | 该板块内涨跌幅最高的成分股名称 |

因此「热点板块」卡片与「板块分析」页展示的是两套互不相干的数据，
且**没有任何测试会红**——两处各自都是"合法"的值。

### 1.3 对比：榜单预览已经是投影的

同一个类里的 `rankings()` 在 M2-06 之后已经改成**投影自与 QTE-01 同一批快照**：

```java
private static List<QuoteRow> rankings(List<QuoteSnapshot> batch) {
    return batch.stream()
            .filter(RankingType.GAINERS::hasSortKey)
            .filter(snapshot -> !snapshot.security().isSuspended())
            .sorted(RankingType.GAINERS.order())
            .limit(RANKING_PREVIEW_SIZE)
            .map(SimulatedQuoteProvider::toQuoteRow)
            .toList();
}
```

板块预览是这条改造漏掉的最后一处。本切片把它补齐，使两个预览段与各自的榜单页
**由构造方式保证一致**，而不是靠约定维持。

## 2. 目标与非目标

### 2.1 目标

1. 总览的 `sectors[]` 每一行的 `sectorId` 都能被 `GET /sectors/{sectorId}` 解析（不再 404）。
2. 总览的 `sectors[]` 与 `GET /sector-rankings`（SEC-02，默认口径 `GAINERS`）首页前 3 行**逐字段一致**。
3. 一致性由构造方式保证：两处走同一个 `SectorProvider` + 同一个 `QuoteBatch` + 同一个
   `SectorQuoteCalculator` + 同一个 `RankingType.GAINERS.sectorOrder()`。

### 2.2 非目标

| 项 | 说明 |
| --- | --- |
| 写死的 `indices()` | **保留**。`idx-sh` / `idx-sz` / `idx-hs300` / `idx-hsi` 不与任何链路冲突，作为模拟源数据自洽（M2-10 取舍 7） |
| `news()` 单条模拟资讯 | 保留，归 M3-05 资讯域 |
| `turnover` 写死的量额与点位 | 保留。它没有可对齐的第二来源，且不属于"标识符" |
| 真实行情源 | 仍后置。本切片只消除**同一系统内两处不一致**，不引入新数据源 |
| 前端改动 | 无需改动。前端已经把 `sectorId` 当主键用，是后端给错了值 |

## 3. 设计

### 3.1 复用 `QuoteBatch`：把它从 `application` 下沉到 `domain`

SEC-03 / SEC-04 的板块统计走的是 `SectorQueryService.statistics(sector)`：

```java
private SectorQuote statistics(Sector sector) {
    QuoteBatch batch = QuoteBatch.of(batchProvider.fetchBatch(MARKET_CODE));
    List<QuoteSnapshot> constituents = batch.ofMembers(
            memberships(null).getOrDefault(sector.sectorId(), List.of()));
    return SectorQuoteCalculator.calculate(
            sector, constituents, batch.dataTime(), batch.dataStatus());
}
```

要让预览与 SEC-03 **逐字段相同**，最可靠的做法就是走同一条路径。
但 `QuoteBatch` 目前是 `cn.zhishi.stock.market.application` 里的
**包私有**类（`final class QuoteBatch`），`stock-integration` 够不到它。

两条路：

| 方案 | 代价 |
| --- | --- |
| A. 在适配器里再写一遍"按成分关系取数 + 缺快照则跳过" | `QuoteBatch` 的类注释写明它存在的理由正是"不要让这段语义各写一遍"，第 4 个消费方再抄一遍等于把注释变成假话 |
| B. 把 `QuoteBatch` 下沉到 `domain` 并公开 | 1 个文件换包 + 3 个调用方各加 1 行 import |

选 **B**。理由：`QuoteBatch` 只依赖领域类型（`QuoteSnapshot` / `SectorMember` /
`MarketOverview.DataStatus`），是一个**纯领域视图**，放在 `application` 只是因为前三个消费方都在那里；
把它放在 `domain` 不构成依赖倒置，而"批次属性取首行"与"缺快照的成分如何处理"这两条口径从此只有一份。

> 注意 `QuoteBatch.dataTime()` 是**整批快照首行的 `dataTime`**（模拟源里恒为该交易日收盘时刻），
> 与总览自身的 `dataTime`（盘中为 `now`）不是一回事。这里刻意沿用批次的口径，
> 因为 `MarketOverview.SectorQuote` 只有 7 个字段、**不含 `dataTime`**，该值不会外泄；
> 而沿用批次口径使本路径与 SEC-03 逐字相同。

### 3.2 板块预览的投影

```java
private static List<MarketOverview.SectorQuote> sectors(QuoteBatch batch, SectorProvider sectorProvider) {
    Map<String, List<SectorMember>> memberships = sectorProvider.memberships(MARKET_CODE, null);
    return sectorProvider.findAll(MARKET_CODE).stream()
            .filter(Sector::active)
            .map(sector -> SectorQuoteCalculator.calculate(
                    sector,
                    batch.ofMembers(memberships.getOrDefault(sector.sectorId(), List.of())),
                    batch.dataTime(),
                    batch.dataStatus()))
            .filter(RankingType.GAINERS::hasSortKey)
            .sorted(RankingType.GAINERS.sectorOrder())
            .limit(SECTOR_PREVIEW_SIZE)
            .map(SimulatedQuoteProvider::toSectorQuote)
            .toList();
}
```

逐条对齐 `SectorRankingQueryService.rank()`：

| 步骤 | SEC-02 | 本切片 |
| --- | --- | --- |
| 停用板块 | `.filter(Sector::active)` | 同 |
| 成分取数 | `batch.ofMembers(memberships.getOrDefault(...))` | 同 |
| 统计 | `SectorQuoteCalculator.calculate(sector, constituents, batch.dataTime(), batch.dataStatus())` | 同 |
| 排序键过滤 | `.filter(rankingType::hasSortKey)` | `RankingType.GAINERS::hasSortKey` |
| 排序 | `.sorted(rankingType.sectorOrder())` | `RankingType.GAINERS.sectorOrder()` |
| 取几行 | `PageData.slice(ranked, 1, 3)` | `.limit(3)` |

`SectorParameters.DEFAULT_RANKING_TYPE = RankingType.GAINERS`，因此"默认口径"两边相同。

### 3.3 13 字段 → 7 字段的映射

`domain.SectorQuote`（13 字段）与 `MarketOverview.SectorQuote`（7 字段）**同名但不同记录**。
两个类型不合并：前者服务 SEC-02/03/04/06，后者是总览预览段，字段集本就不同
（契约的预览段没有 `sectorType` / `averagePrice` / `tradeVolume` / `laggingStock` / `dataTime` / `dataStatus`）。

映射规则：

| 预览字段 | 来源 |
| --- | --- |
| `sectorId` / `sectorCode` / `sectorName` | 直接取自板块主数据 |
| `changeRate` / `tradeAmount` | 直接取自统计 |
| `leadingStock` | `quote.leadingStock().security().securityName()`，领涨股缺失时为 `null` |
| `companyCount` | 直接取自统计（含停牌成分） |

命名冲突的处理：本文件原本 `import ...MarketOverview.SectorQuote`。改动后
**移除该 import、改为写全 `MarketOverview.SectorQuote`**，并导入领域侧的 `SectorQuote`——
因为领域类型在 `map` 与比较器中是主力类型，而预览类型只出现在 `sectors()` 的返回类型与映射目标两处。
两个同名记录同时出现在一个文件里，显式限定比隐式导入更不容易读错
（前端 `OverviewSectorQuote` 的命名决定出于同一考虑）。

### 3.4 装配

`SimulatedQuoteProvider` 新增一个 `SectorProvider` 依赖：

- 生产装配（`BackendConfiguration` / `JobConfiguration`）传入配置层已有的 `SectorProvider` Bean。
  **两个模块都必须传**，否则 `stock-job` 会退化成自建一份——这正是 M2-10 在交易日历上踩过的坑。
- 便捷构造（`(clock, scenario)` 与 `(clock, scenario, calendar)`）自建一份默认板块源，
  与既有的 `defaultBatchProvider` 同构；`SimulatedSectorProvider` 投影自
  `SimulatedSecurityMasterProvider`，而后者无状态且完全确定性，两份实例产出逐位相同
  （`BackendConfiguration` 对 `SimulatedSecurityQuoteProvider` 已有同样的说明）。

## 4. 验证

### 4.1 后端（TDD，先写失败测试）

| 测试 | 断言 |
| --- | --- |
| `sectorPreviewIdsAreResolvableByTheSectorDetailApi` | 预览每一行的 `sectorId` 都能被 `SectorQueryService.detail()` 解析（解析不了会抛 `SectorNotFoundException`），且 `sectorCode` / `sectorName` / `companyCount` / `changeRate` 与 SEC-03 详情一致 |
| `sectorPreviewMatchesTheSectorRankingTopThree` | 预览与 `SectorRankingQueryService.rank(GAINERS, page=1, size=3)` 的 `items` 逐字段一致 |
| `normalScenarioProducesDeterministicTradingSnapshot` | 由"首个板块是 `BK-AI`"改为钉住真实板块源的前三名全部字段 |

红灯实测（Step A：只加构造参数、保留写死的 `sectors()`）：

```
expected: "sim-bk0033"
 but was: "bk-ai"
cn.zhishi.stock.market.application.SectorNotFoundException: 板块 bk-ai 不存在
[ERROR]   SimulatedQuoteProviderTest.sectorPreviewIdsAreResolvableByTheSectorDetailApi:171 ? SectorNotFound
```

即测试确实复现了首页卡片的 404，而不是只比对了字符串。

### 4.2 前端

`OverviewSectorQuote.leadingStock` 改为 `string | null`（后端契约可空），页面渲染 `?? '--'`。
新增两项测试：

| 测试 | 断言 |
| --- | --- |
| `热点板块卡片用预览行的 sectorId 作为跳转主键` | 卡片 `href` 为 `/sectors/sim-bk0033`（而非 `/sectors/BK0033` 或名称），把"跳转主键是 `sectorId`"钉成契约 |
| `板块没有可统计行情时不把 null 渲染成空白` | `leadingStock: null` 时渲染 `领涨 --` |

### 4.3 已知限制

- 本机 Docker 未启动 → `InfrastructureIntegrationTest`（Testcontainers）仍报
  `Could not find a valid Docker environment`，属环境限制，由 CI 覆盖。
- 上述两条后端测试只覆盖"总览预览 ↔ SEC-02/SEC-03"的一致性。
  若将来新增第 4 条消费 `sectorId` 的链路，仍需要各自的测试。

## 5. 改动清单

### 后端

| 文件 | 改动 |
| --- | --- |
| `stock-market/domain/QuoteBatch.java` | **新增**（自 `stock-market/application/QuoteBatch.java` 移入）：包私有 → `public final`，成员方法公开，类注释补第 4 个消费方与下沉理由 |
| `stock-market/application/StockRankingQueryService.java` | 补 `QuoteBatch` import |
| `stock-market/application/SectorRankingQueryService.java` | 补 `QuoteBatch` import |
| `stock-market/application/SectorQueryService.java` | 补 `QuoteBatch` import |
| `stock-integration/market/SimulatedQuoteProvider.java` | 新增 `SectorProvider` 依赖；新增 `SimulatedSources`（整批快照源与板块源共用同一份证券主数据）；`fetch()` 只取一次整批快照；`sectors()` 改为投影；新增 `toSectorQuote`；`SECTOR_PREVIEW_SIZE = 3` |
| `stock-backend/config/BackendConfiguration.java` | `quoteProvider` 注入 `SectorProvider` |
| `stock-integration/test/SimulatedQuoteProviderTest.java` | 9 → 11 项；新增 `quoteProvider(...)` 装配 helper |
| `docs/superpowers/specs/2026-09-20-overview-sector-preview-truth.md` | **新增**本 spec |
| `docs/superpowers/specs/2026-09-20-market-status-truth.md` | M2-11 归属行标记为已完成 |

`stock-job/JobConfiguration.java` **无需改动**：它走 3 参数便捷构造，板块源由便捷构造按同一个
日历自建（见 §3.4）。

### 前端

| 文件 | 改动 |
| --- | --- |
| `src/types/domain.ts` | `OverviewSectorQuote.leadingStock` → `string \| null` |
| `src/pages/MarketOverview.vue` | `领涨 {{ sector.leadingStock ?? '--' }}` |
| `src/pages/MarketOverview.test.ts` | `RouterLink` 桩渲染 `href`；夹具改用 `sim-bk0033`；11 → 13 项 |
| `e2e/auth-market.mjs` | 夹具板块改用 `sim-bk0033` |

## 6. 验收结果（2026-09-20）

| 项 | 结果 |
| --- | --- |
| 后端 | **353 项通过**（全量 354 项；其中 `InfrastructureIntegrationTest` 因本机未启动 Docker 报错）。本次新增 2 项：`SimulatedQuoteProviderTest` 9 → 11 |
| 前端 | **19 文件 / 101 项通过**（基线 19 / 99），`TZ=UTC` 下同样全绿 |
| 类型 / 构建 | `npm run typecheck` 0 错误；`vite build --configLoader runner` 成功 |
| 环境限制 | `InfrastructureIntegrationTest` 报 `Could not find a valid Docker environment`，非回归，CI 覆盖 |
| 未做 | 端到端联调（需 Docker 起 MySQL/Redis 起后端），故"首页三张卡片点进去不再 404"仅由 `sectorPreviewIdsAreResolvableByTheSectorDetailApi` 断言，未在真实浏览器里点过 |

实测预览前三名（`normalScenarioProducesDeterministicTradingSnapshot` 钉住）：

| 名次 | sectorId | sectorCode | sectorName | changeRate | leadingStock | companyCount |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `sim-bk0033` | `BK0033` | 一带一路 | 0.0228 | 模拟证券830223 | 149 |
| 2 | `sim-bk0012` | `BK0012` | 通信设备 | 0.0220 | 模拟证券830127 | 247 |
| 3 | `sim-bk0010` | `BK0010` | 消费电子 | 0.0215 | 模拟证券830223 | 245 |

> 注意 `companyCount` 与 `changeRate` 的样本集不同：前者含停牌成分，后者不含。
> 前三名被概念板块与科技行业占据，与限幅档位（北交所 30%）叠加后的个股榜单头部构成一致。
