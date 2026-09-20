# M3-04 资讯 Provider 抽象 + 模拟源 + 去重 + 标的关联

> 里程碑：`TASKS.md` M3-04（P0，依赖 M2-04）
> 路线图验收口径：**来源 ID 或内容指纹幂等；低置信关联不进 AI 证据**
> 表结构：`sql/flyway/V4__create_news_domain.sql`（`news_source` / `stock_news` / `stock_news_relation`，V1 起就绪、至今空置）

---

## 1. 背景

### 1.1 为什么现在做

M3-04 在路线图里排得比 M3-06（AI Provider）早，理由写得很直白：

> **M3-04 早于 M3-06**：AI 的证据链依赖资讯关联，先有资讯再有 AI，避免 AI 报告无据可引。

在此之前，仓库里所有"资讯"都是假的，且**假的形态各不相同**：

| 位置 | 现状 | 问题 |
| --- | --- | --- |
| `MarketOverview.NewsItem`（MKT-01 响应里的 `news`） | `SimulatedQuoteProvider.news(now)` 返回**一条**写死的 `news-sim-1` | 无人反查 id，属"模拟源数据自洽"；但内容不来自任何来源 |
| WAT-06 / WAT-11 的 `latestNewsCount` | 恒为 `null`，并在 `limitations` 里说明"资讯 Provider 见 M3-04" | 契约字段长期缺席 |
| WAT-11 的 `newsSince` | **未声明**（`WatchlistOverviewController` 不接受该参数） | 契约 §12.2 明写有此 query 参数 |
| `/news` 页面 | 读 `mockApi.getNews()`（前端本地假数据） | M3-05 的欠账 |

V4 三张表自 M1 就绪，`news_source` / `stock_news` / `stock_news_relation` 至今**零行零引用**。
本轮把它们真正用起来。

### 1.2 数据来源

真实资讯源**未就位**（用户已确认，见路线图"暂不做什么"）。因此：

- 定义 `NewsProvider` 端口（`stock-news/domain`），与真实供应商适配器同构；
- 模拟实现 `SimulatedNewsProvider` 落在 `stock-integration`（与 `SimulatedQuoteProvider` 等同层），
  **确定性**：同一 `(since, 时钟)` 产出逐位相同的批次。

### 1.3 可直接复用的既有代码

| 复用对象 | 位置 | 复用方式 |
| --- | --- | --- |
| `SecurityIdentityProvider` | `stock-market/domain` | `stock_news_relation.target_id` 是 bigint，与 `user_watchlist_item.security_id` **必须同一套代理键**（"同一个事实只允许一处实现"） |
| `SecurityMasterProvider` / `SectorProvider` | `stock-market/domain` | 模拟源生成资讯时的标的池；关联解析的匹配目录 |
| `TradingSessions` | `stock-market/domain` | 发布时间落在交易时段内，否则"盘后发布的公告"会出现在盘中 |
| `SimulatedHashing`（SplitMix64） | `stock-integration/market` | 模拟源的确定性随机，**不新写第二份哈希** |
| 端口 + MyBatis 实现范式 | `stock-system/watchlist`（21 个文件） | 端口与实现同模块、Mapper 用注解 SQL、`@Mapper` + `@MapperScan` |
| `PageData.slice` | `stock-common` | 分页 |
| `IdempotencyGuard` | `stock-system/idempotency` | 资讯接口都是读接口，本轮不需要；写入走 job 内部 |

---

## 2. 目标与非目标

### 2.1 目标

1. **资讯域独立成模块 `stock-news`**（见 §3.1 的决策），含 domain / application / infrastructure 三层。
2. **采集与去重**：`NewsProvider` → `NewsIngestionService` → 三张表；去重同时覆盖
   *来源 ID 幂等*（`uk_stock_news_source_content`）与*内容指纹幂等*（`dedup_status=DUPLICATE` + `canonical_news_id`）。
3. **标的关联**：纯函数 `NewsRelationResolver` 从标题/摘要/来源结构化提示产出
   `SECURITY` / `SECTOR` / `MARKET` 三类关联，带 `relation_method`、`confidence_score`、`relation_status`。
4. **低置信不进默认视图**：查询层只返回 `relation_status=CONFIRMED`；`CANDIDATE` / `REJECTED`
   在库里可查（供后台复核），但**不出现在任何前台接口**。
5. **查询接口**：NEWS-01~04、STK-10、SEC-07 六个契约接口。
6. **定时采集**：`stock-job` 新增 `ScheduledNewsCollector`（架构 §"新闻/公告增量采集 每 2 分钟"）。
7. **关闭 M3-03 的欠账**：WAT-06 / WAT-11 的 `latestNewsCount` 取真实值；WAT-11 接受 `newsSince`。

### 2.2 非目标（写明归属，不在本轮偷偷扩大范围）

| 不做 | 归属 | 原因 |
| --- | --- | --- |
| 真实资讯源适配器 | 用户已确认延后 | 授权源未就位 |
| `ADM-NEWS-01~08` 后台来源管理 | M3-11 | 需要 RBAC + 审计基础设施 |
| AI 证据链消费资讯（`allow_ai_analysis` 的消费侧） | M3-06 / M3-07 | AI 域尚不存在；本轮**只落库这个标记**，并保证查询层可据此过滤 |
| 资讯关联重试定时任务（架构 §"资讯关联重试 每 10 分钟"） | 后续 | 模拟源不产生"待重试"状态；先有真实源再谈重试 |
| Redis 最新资讯列表缓存（架构 §5.3 "MySQL 后刷新 Redis 最新资讯列表"） | 不做，记入已知问题 | 会为"最新资讯列表"造出第二处真相；当前量级直读 MySQL 足够 |
| 前端 `/news` 接入 | M3-05 | 前端里程碑 |
| 相似度去重（编辑距离 / 向量） | 不做，记入已知问题 | 精确指纹先跑通链路；相似度去重是独立课题 |
| 资讯全文正文抓取与清洗 | 不做 | 契约 §11.2 明写"不返回未经授权的完整正文"；`authorized_summary` 即全部可展示内容 |
| WebSocket 资讯推送 | 不做 | 架构 §394：MVP 不通过 WebSocket 主动推送新闻提醒 |

---

## 3. 设计

### 3.1 关键决策：资讯域落在**新模块 `stock-news`**

`docs/Architecture.md` §5.1 规划 10 个 Maven 模块，仓库实际只有 6 个——
`stock-watchlist` 已被折进 `stock-system`（用户域）。那么资讯域该折进谁？

**决策：新建第 7 个模块 `stock-news`。** 理由：

1. **它有自己的持久化**。3 张表、3 个仓储、1 个采集事务，与 `stock-market` 的
   "行情不落库"路线**恰好相反**（见 §3.2）。把落库逻辑塞进 `stock-market`
   会让一个模块同时存在两种数据生命周期。
2. **它有自己的外部端口**（`NewsProvider`）与适配器，与 `stock-market` 的
   `QuoteProvider` 平级而非从属。
3. **它有 7 个契约接口**（NEWS-01~04 / STK-10 / SEC-07），不是某人的附属功能。
4. 折进 `stock-system` 更不合适：资讯不是用户域数据。

代价（如实记录）：`backend/pom.xml` 与 4 个模块的 `pom.xml` 需要各加一行；
`@MapperScan` 需要覆盖新包。这些都是**一次性的机械改动**，且 CI 跑
`mvn -f backend/pom.xml verify`，新模块会被自动纳入，无需改 CI。

### 3.2 关键决策：资讯**落库**，与行情域"不落库"相反

`stock-market` 的模拟 Provider 一律不落库（市场广度、成交趋势、证券主数据、
榜单与板块预览全是即时计算）。资讯域**必须落库**，原因不是"架构文档这么写"，而是：

- **去重需要跨批次记忆**。"这条内容三分钟前从另一家媒体来过"是**历史事实**，
  内存里的无状态计算无法回答。
- **`content_fingerprint` 是唯一索引的一部分**，幂等由数据库保证，不靠应用层约定。
- **关联有生命周期**（`CONFIRMED` → 人工复核 → `REJECTED`），必须持久。

因此本轮是仓库里**第一个把模拟 Provider 的产出写进 MySQL 的里程碑**。
`stock-job` 的采集事务与 `MarketIngestionService` 同形（validate → save），
但多了去重与关联两步。

### 3.3 新增 / 修改文件

**新模块 `backend/stock-news`**

```
domain/
  NewsType.java                    NEWS / ANNOUNCEMENT / RESEARCH / OTHER
  NewsContentStatus.java           PUBLISHED / WITHDRAWN / DELETED
  NewsOriginalAccessStatus.java    AVAILABLE / UNAVAILABLE / UNKNOWN
  NewsDedupStatus.java             ORIGINAL / DUPLICATE
  NewsRelationStatus.java          CONFIRMED / CANDIDATE / REJECTED
  NewsRelationMethod.java          EXPLICIT / RULE / MODEL / MANUAL
  NewsTargetType.java              SECURITY / SECTOR / MARKET
  NewsSource.java                  来源（含授权状态、allowAiAnalysis、健康状态）
  NewsArticle.java                 稿件（含指纹、去重状态、canonicalNewsId）
  NewsRelation.java                关联（target + method + confidence + status）
  NewsFeedItem.java                Provider 返回的**原始**条目（未去重、未解析）
  NewsFeed.java                    一次采集的批次
  NewsProvider.java                采集端口
  NewsFingerprint.java             【纯函数】清洗 + SHA-256
  NewsDeduplicator.java            【纯函数】ORIGINAL / DUPLICATE 判定
  NewsRelationResolver.java        【纯函数】关联解析与置信度
  NewsSummary.java                 契约 §4.3 的对外视图
  NewsRelationSummary.java         NewsSummary.relations 的元素
  NewsSyncStatus.java              NEWS-03 响应
  NewsOptions.java                 NEWS-04 响应
  NewsArticleStore.java            稿件仓储端口
  NewsSourceStore.java             来源仓储端口
  NewsRelationStore.java           关联仓储端口
  NewsCountProvider.java           给自选域用的"某证券最近资讯数"端口
application/
  NewsQueryService.java            六个查询接口的业务规则
  NewsIngestionService.java        采集 + 去重 + 关联 + 落库
  NewsNotFoundException.java       NEWS_NOT_FOUND
  InvalidNewsQueryException.java   INVALID_REQUEST
infrastructure/
  NewsSourceMapper.java / NewsSourceRow.java / MyBatisNewsSourceStore.java
  NewsArticleMapper.java / NewsArticleRow.java / MyBatisNewsArticleStore.java
  NewsRelationMapper.java / NewsRelationRow.java / MyBatisNewsRelationStore.java
```

**`stock-integration`**：`news/SimulatedNewsProvider.java`（+ `SimulatedNewsFeed.java` 数据池）

**`stock-backend`**
- 新增 `web/NewsController.java`（NEWS-01~04）
- 改 `web/SecurityController.java`（+STK-10）、`web/SectorController.java`（+SEC-07）
- 改 `config/BackendConfiguration.java`（新增 Bean）
- 改 `security/SecurityConfiguration.java`（`GET /api/v1/news/**` 放开为 PUBLIC）
- 改 `StockBackendApplication.java`（`@MapperScan` 覆盖 `cn.zhishi.stock.news`）

**`stock-job`**：新增 `ScheduledNewsCollector.java`；改 `JobConfiguration.java`

**`stock-system`**：`watchlist/WatchlistItemService.java` 接 `NewsCountProvider`；
`watchlist/WatchlistEntry.java` 的 `latestNewsCount` 注释更新；`pom.xml` 加 `stock-news`

**`pom.xml`**：`backend/pom.xml` 加 `<module>stock-news</module>`；
`stock-integration` / `stock-backend` / `stock-job` 各加 `stock-news` 依赖

### 3.4 去重：两条幂等路径

契约与架构的要求是"**来源 ID 或内容指纹幂等**"，两者语义不同，必须都实现：

| 路径 | 判据 | 结果 | 谁保证 |
| --- | --- | --- | --- |
| **来源 ID 幂等** | `(source_id, source_content_id)` 已存在 | **整条跳过**，不新增记录 | `uk_stock_news_source_content` 唯一索引 + 撞索引后静默跳过 |
| **内容指纹幂等** | `content_fingerprint` 命中一条已有 `ORIGINAL` | 新增一条 `dedup_status=DUPLICATE` + `canonical_news_id` 指向主记录 | 应用层判定 + `ck_stock_news_canonical` CHECK |

**为什么来源 ID 幂等要"跳过"而不是"标重复"**：同一条 `source_content_id` 是
**同一份稿件被重复投递**（采集重试、游标回退），不是"另一家媒体也发了"。
为它造一条 DUPLICATE 记录会让 `stock_news` 里堆满同一来源的自我重复，
而它不携带任何新信息。DUPLICATE 专指**跨来源**的重复。

**指纹口径**（`NewsFingerprint`）：`SHA-256(清洗(title) + "\n" + 清洗(summary))`，十六进制小写 64 字符。
清洗 = 去 HTML 标签 → 去零宽字符（U+200B~U+200D、U+FEFF）→ 空白折叠为单空格 → trim。
`summary` 为 `null` 时按空串参与。

**已知局限（如实记录）**：真实世界里两家媒体报同一件事，标题措辞几乎不会逐字相同，
精确指纹抓不到。模拟源刻意让重复对**逐字相同**，因此本轮能验证的是"链路正确"
而不是"去重效果"。相似度去重记入已知问题。

### 3.5 关联解析：置信度与"低置信不进证据"

`NewsRelationResolver` 是**纯函数**：输入一条 `NewsFeedItem` + 证券目录 + 板块目录，
输出 `List<NewsRelation>`。规则逐条可断言：

| # | 触发条件 | `relation_method` | `confidence_score` | `relation_status` |
| --- | --- | --- | --- | --- |
| R1 | 来源结构化提示里的证券代码，且存在于主数据 | `EXPLICIT` | `1.00000` | `CONFIRMED` |
| R2 | 来源结构化提示里的 `marketCode` | `EXPLICIT` | `1.00000` | `CONFIRMED` |
| R3 | **标题**含证券简称（简称长度 ≥ 2） | `RULE` | `0.80000` | `CONFIRMED` |
| R4 | **仅摘要**含证券简称 | `RULE` | `0.60000` | `CANDIDATE` |
| R5 | **标题**含板块名 | `RULE` | `0.75000` | `CONFIRMED` |
| R6 | **仅摘要**含板块名 | `RULE` | `0.50000` | `CANDIDATE` |

阈值 `CONFIRMED_THRESHOLD = 0.70`（`NewsConfidence.classify`）：`>= 0.70` 为 `CONFIRMED`，
否则为 `CANDIDATE`。刻意**没有**"候选下限"——规则表里最低的一条是 `0.50000`，
再加一个 `0.40` 的下限只会得到一段永远不会被触发的代码。噪音过滤由规则的**触发条件**
承担（名称长度、最长匹配），而不是由一个空转的阈值承担。

**R4/R6 是"低置信"的来源**，也正是"低置信关联不进 AI 证据"这条验收标准的被验对象：
它们在库里可见（后台复核用），但**所有前台查询一律过滤 `relation_status='CONFIRMED'`**。

**为什么"仅摘要命中"算低置信**：标题是编辑对这条稿件"关于谁"的判断，摘要里顺带提到
一家公司可能只是同题材类比。这不是拍脑袋的阈值，而是**编辑判断权的建模**。

**同一标的命中多次只产出一条关系**（取置信度最高者）：`uk_news_relation_target`
是唯一索引，解析器必须在内存里去重，否则插入撞索引——且"哪条留下"取决于插入顺序，
是典型的"看起来对、其实不确定"。

### 3.6 采集流程（`NewsIngestionService`）

```
feed = provider.fetch(since)
for item in feed.items():
    source = sourceStore.findByCode(item.sourceCode())
    if source == null            → 记入 skipped（来源未登记），继续
    if !source.usable()          → 记入 skipped（未授权 / 已停用），继续   ← 授权闸门
    if articleStore.existsBySourceContent(source.id(), item.sourceContentId())
                                 → 记入 skipped（来源 ID 幂等），继续
    fingerprint = NewsFingerprint.of(item.title(), item.summary())
    canonical = articleStore.findOriginalByFingerprint(fingerprint)
    decision  = NewsDeduplicator.decide(fingerprint, canonical)
    article   = new NewsArticle(新 id, source.id(), ..., fingerprint, decision, ...)
    try { articleStore.insert(article) }
    catch DuplicateKeyException  → 记入 skipped（并发下另一个采集进程先写了），继续
    if decision.status() == ORIGINAL:
        relationStore.insertAll(resolver.resolve(item, securityCatalog, sectorCatalog))
return new NewsIngestionResult(collected, deduplicated, skipped, relationCount)
```

**只有 `ORIGINAL` 才解析关联**：DUPLICATE 会被折叠到主记录，给重复稿也挂关联
会让"某公司最近资讯"出现两条内容完全相同的条目。

**授权闸门放在采集侧**（`source.usable()` = `authorization_status='AUTHORIZED'`
且 `status != 'DISABLED'` 且授权期未过）：未经授权的来源**连库都不该进**。
查询侧还会再过滤一次（纵深防御），但采集侧是第一道。

**单条失败不阻塞整批**：一条稿件的关联解析异常不该让整批采集回滚。
`insertAll` 之外的每条处理都包在 `try/catch` 里记入 `skipped`。
（架构 §"指数退避，单来源失败不阻塞其他来源"的同一条原则。）

### 3.7 查询口径（`NewsQueryService`）

**所有前台查询共用的过滤链**（顺序即实现顺序）：

1. `stock_news.content_status = 'PUBLISHED'`（契约 §11.2）
2. `stock_news.dedup_status = 'ORIGINAL'`（契约 §11.2：重复稿折叠到主记录）
3. `news_source.authorization_status = 'AUTHORIZED'`（授权）
4. `news_source.status != 'DISABLED'`
5. `stock_news.rights_expire_at IS NULL OR rights_expire_at > now`（单条授权失效）
6. `news_source.rights_valid_to IS NULL OR rights_valid_to >= 今天`
7. 关联过滤：`relation_status = 'CONFIRMED'`
8. 排序：`published_at DESC, id DESC`（契约 §11.2"按发布时间倒序"）

**"资讯为空"不等于"没有风险"**（契约 §11.2 明写）。因此
`dataStatus` 取 `UNAVAILABLE` 时页面必须显示"当前授权范围内无结果"，
**不得**渲染成"该公司无负面资讯"。本轮把这个口径写进 `NewsSyncStatus` / 列表响应的注释，
前端文案在 M3-05 落地。

**六个接口的差异只在过滤条件**，共用同一个查询方法：

| 接口 | 附加条件 |
| --- | --- |
| NEWS-01 `/news` | `newsTypes`、`securityId`、`sectorId`、`marketCode`、`startAt`、`endAt`、`keyword` |
| NEWS-02 `/news/{newsId}` | 按 id 取单条；**不**要求它有关联 |
| NEWS-03 `/news/sync-status` | 无过滤，聚合来源健康状态 |
| NEWS-04 `/news/options` | 无过滤，返回受控筛选项 |
| STK-10 `/securities/{id}/news` | 固定 `target_type='SECURITY'` + 该证券 |
| SEC-07 `/sectors/{id}/news` | 固定 `target_type='SECTOR'` + 该板块 |

**NEWS-02 的"不要求有关联"是刻意的**：一条没有任何确认关联的稿件仍然应该能被打开，
否则列表里出现过的条目会点不进去。契约 §11.2 只要求"不返回未经授权的完整正文"。

**`securityId` / `sectorId` 筛选走字符串 ↔ bigint 桥接**：
`NewsQueryService` 依赖 `SecurityIdentityProvider`（行情域），
STK-10 的 `securityId` 解析不到时返回 **404 `SECURITY_NOT_FOUND`** 而不是空列表——
"这只证券不存在"与"这只证券没有资讯"是两件事。

### 3.8 `latestNewsCount` 与 `newsSince`

`WatchlistItemService` 新增构造参数 `NewsCountProvider`（`stock-news/domain` 的端口）：

```java
/** 一次全取：给定证券集合，返回每只证券自 since 起的资讯条数。缺失的证券不出现在结果里。 */
Map<String, Integer> countSince(Collection<String> securityIds, OffsetDateTime since);
```

- WAT-06 / WAT-11 的 `latestNewsCount` = `countSince(本页证券, now - 7d)` 的结果，
  **未命中时为 `null`**（不是 `0`）——"没有资讯"与"不知道"必须可区分，
  这是 M3-03 已经立下的口径。
- WAT-11 新增 `newsSince` query 参数（契约 §12.2 有），默认 7 天前；非法值 → 400。
- `limitations` 里的 `NEWS_NOT_IMPLEMENTED` 常量删除。
- 空自选时**不发起**资讯计数查询（与"空自选不触发整批取数"同一条原则）。

**为什么 `stock-system` 新增对 `stock-news` 的依赖**：与它对 `stock-market` 的依赖同因——
自选项要回显一个属于另一个域的事实，只能靠端口。**只依赖 `domain` 包**，
不依赖 `application`。`stock-news` 不依赖 `stock-system`，无环。

### 3.9 `MarketOverview.NewsItem` 与资讯域的关系

**本轮不动** `MarketOverview.news`（MKT-01 响应里的单条模拟资讯）。
它是首页"市场快讯"的占位，`news-sim-1` 无人反查（不构成"需要被别处解析的标识符"）。
把它改成投影自 `stock_news` 需要把 MKT-01 的组装从"整批快照"改成"快照 + 资讯两次取数"，
影响 MKT-01 的 `componentStatus` 口径——那是 M2 已交付并冻结的语义。
记入已知问题，归属 **M3-05**（前端接资讯时一并评估）。

---

## 4. 一致性约束（逐条写成测试）

| # | 约束 | 测试位置 |
| --- | --- | --- |
| C1 | 同一 `(source, sourceContentId)` 采集两次，`stock_news` 只有一行 | `NewsIngestionServiceTest` |
| C2 | 两个来源发同一内容，第二条是 `DUPLICATE` 且 `canonicalNewsId` 指向第一条 | `NewsIngestionServiceTest` |
| C3 | `ORIGINAL` 的 `canonicalNewsId` 恒为 `null`（与 `ck_stock_news_canonical` 同口径） | `NewsDeduplicatorTest` |
| C4 | `DUPLICATE` 稿件不产出任何关联 | `NewsIngestionServiceTest` |
| C5 | 同一标的被标题与摘要同时命中，只产出一条关联，取置信度最高者 | `NewsRelationResolverTest` |
| C6 | R4/R6 产出 `CANDIDATE`；**所有**前台查询结果里不含 `CANDIDATE` / `REJECTED` | `NewsQueryServiceTest` + 契约测试 |
| C7 | 未授权来源的稿件不落库，也不出现在任何查询结果里 | `NewsIngestionServiceTest` + `NewsQueryServiceTest` |
| C8 | `content_status != PUBLISHED` 的稿件不出现在列表里（但 NEWS-02 按 id 取时返回 `NEWS_WITHDRAWN`） | `NewsQueryServiceTest` |
| C9 | `rights_expire_at` 已过的稿件不出现在任何查询结果里 | `NewsQueryServiceTest` |
| C10 | `dedup_status = DUPLICATE` 不出现在列表里 | `NewsQueryServiceTest` |
| C11 | 列表按 `published_at DESC, id DESC` 稳定排序（同秒时 id 决定顺序） | `NewsQueryServiceTest` |
| C12 | STK-10 的 `securityId` 解析不到 → 404 `SECURITY_NOT_FOUND`（不是空列表） | `SecurityControllerContractTest` |
| C13 | STK-10 只返回该证券的 `CONFIRMED` 关联；**不**因板块关系泛化 | `NewsQueryServiceTest` |
| C14 | 自选 `latestNewsCount` 未命中时为 `null`，不是 `0` | `WatchlistItemServiceTest` |
| C15 | 空自选不发起资讯计数查询 | `WatchlistItemServiceTest` |
| C16 | 采集服务对单条异常不整批回滚（其余条目仍落库） | `NewsIngestionServiceTest` |
| C17 | 指纹对"仅空白差异 / HTML 标签差异"不敏感，对实质内容差异敏感 | `NewsFingerprintTest` |
| C18 | 来源授权期已过（`rights_valid_to < 今天`）时 `usable()` 为假 | `NewsSourceTest` |

---

## 5. 验证方式

```bash
# 后端
export JAVA_HOME="D:/idea/JDK17"
"D:/maven/.../mvn.cmd" -s backend/settings.xml -f backend/pom.xml verify
TZ=UTC "D:/maven/.../mvn.cmd" -s backend/settings.xml -f backend/pom.xml test

# 单模块调试（必须 -am）
"...mvn.cmd" -f backend/pom.xml -pl stock-news -am test
```

- **红灯优先**：`NewsFingerprint` / `NewsDeduplicator` / `NewsRelationResolver` 三个纯函数类
  先落 `throw new UnsupportedOperationException("尚未实现")` 空壳，让测试**编译通过但断言失败**。
- **契约测试**照抄 `MarketControllerContractTest` 的 `mvc()`（Jackson 必须
  `featuresToDisable(WRITE_DATES_AS_TIMESTAMPS)`，否则 `OffsetDateTime` 会序列化成 epoch 数字）。
- **Testcontainers**：`InfrastructureIntegrationTest` 已覆盖 V1–V8 迁移；本轮新增的
  `NewsRepositoryIntegrationTest` 也走真实 MySQL（Docker 可用时实测，否则写"CI 覆盖"）。

---

## 6. 改动清单

> 实现完成后回填。两点与 §3.3 的预估不同，先写在前面：
>
> 1. **V4 迁移不是本轮新增的。** `sql/flyway/V4__create_news_domain.sql` 与
>    `sql/checks/post_migration_validation.sql` 里的资讯表 / 唯一索引 / 孤儿检查
>    在 `0eeee92`（建立 CI 与旧库升级路径）时就已经落盘，本轮只是**开始使用**它们。
>    因此本轮没有新增任何迁移文件，`post_migration_validation.sql` 也不用改。
> 2. **`stock-backend` 多改了 3 个文件**（`StockBackendApplication` / `GlobalExceptionHandler` /
>    `WatchlistOverviewController`），且资讯六个端点**没有**拆进 `SecurityController` / `SectorController`
>    （理由见 §6.2）。`stock-job` 还多了一个 `@MapperScan` 与一个测试类（见 §6.3）。

### 6.1 新模块 `backend/stock-news`（50 个主代码文件 + 7 个测试文件）

| 层 | 文件数 | 内容 |
| --- | --- | --- |
| `domain` | 36 | 7 个枚举：`NewsType` / `NewsContentStatus` / `NewsOriginalAccessStatus` / `NewsDedupStatus` / `NewsRelationStatus` / `NewsRelationMethod` / `NewsTargetType`；5 个实体与视图：`NewsSource` / `NewsArticle` / `NewsRelation` / `NewsDetail` / `NewsRecord`；5 个对外响应类型：`NewsSummary` / `NewsRelationSummary` / `NewsPage` / `NewsOptions` / `NewsSyncStatus`；2 个采集输入：`NewsFeedItem` / `NewsFeed` / `NewsIngestionResult`；3 个纯函数：`NewsFingerprint` / `NewsDeduplicator` / `NewsRelationResolver`；5 个端口：`NewsArticleStore` / `NewsSourceStore` / `NewsRelationStore` / `NewsProvider` / `RelationCatalogProvider` / `NewsCountProvider`；4 个共享口径：`NewsConfidence` / `NewsUrlPolicy` / `NewsTimestamps` / `NewsTimeRange` / `NewsMarketTargets` / `RelationCatalog` |
| `application` | 5 | `NewsIngestionService`（采集 + 去重 + 关联 + 落库，含 `REQUIRES_NEW` 的 `recordSyncFailure`）、`NewsQueryService`（六个查询接口的业务规则，**兼作** `NewsCountProvider`）、`NewsQuery`、`NewsNotFoundException`、`InvalidNewsQueryException` |
| `infrastructure` | 9 | `NewsSourceMapper` / `NewsSourceRow` / `MyBatisNewsSourceStore`；`NewsArticleMapper` / `NewsArticleRow` / `MyBatisNewsArticleStore`；`NewsRelationMapper` / `NewsRelationRow` / `MyBatisNewsRelationStore` |
| `src/test` | 7 | `NewsFingerprintTest`(9) / `NewsDeduplicatorTest`(5) / `NewsRelationResolverTest`(17) / `NewsIngestionServiceTest`(19) / `NewsQueryServiceTest`(39) + `NewsFixtures` / `InMemoryNewsStores` |

### 6.2 修改的既有文件

| 文件 | 改动 |
| --- | --- |
| `backend/pom.xml` | 加 `<module>stock-news</module>` |
| `stock-integration/pom.xml` | 加 `stock-news` 依赖 |
| `stock-backend/pom.xml` | 加 `stock-news` 依赖 |
| `stock-job/pom.xml` | 加 `stock-news` 依赖；**另加 `mybatis-plus-spring-boot3-starter`**（`StockJobApplication` 直接用了 `@MapperScan` / `@Mapper`，按"显式声明而不是蹭传递依赖"的既有规矩补上） |
| `stock-system/pom.xml` | 加 `stock-news` 依赖 |
| `stock-market/domain/SectorIdentity.java`（新）<br>`stock-market/domain/SectorIdentityProvider.java`（新） | 板块侧的身份桥接。与 `SecurityIdentityProvider` 同因同形：`stock_news_relation.target_id` 是 bigint，板块也需要一个"契约字符串 ↔ 代理键"的解析入口 |
| `stock-integration/market/SimulatedSectorIds.java`（新）<br>`SimulatedSectorIdentityProvider.java`（新） | 构词规则的**唯一**定义处 |
| `stock-integration/market/SimulatedHashing.java` | `SplitMix64` 从包内可见放开为 `public`——资讯 Provider 要复用它，**不新写第二份哈希** |
| `stock-integration/market/SimulatedSectorProvider.java` | 改为投影自 `SecurityMasterProvider`（板块预览此前用了一份自造的名字表） |
| `stock-integration/news/SimulatedNewsProvider.java`（新）<br>`SimulatedRelationCatalogProvider.java`（新） | 确定性模拟源；关联解析的证券/板块目录 |
| `stock-backend/.../web/NewsController.java`（新） | **六个端点写在同一个控制器里**：NEWS-01~04 + STK-10 + SEC-07 |
| `stock-backend/.../security/SecurityConfiguration.java` | `GET /api/v1/news`、`GET /api/v1/news/**` 放开为 PUBLIC |
| `stock-backend/.../web/GlobalExceptionHandler.java` | 加两个处理器：`NewsNotFoundException` → 404（业务码由异常携带）、`InvalidNewsQueryException` → 400 `INVALID_REQUEST` |
| `stock-backend/.../config/BackendConfiguration.java` | 加 9 个资讯 Bean；`watchlistItemService` 构造参数插入 `NewsCountProvider` |
| `stock-backend/.../StockBackendApplication.java` | `@MapperScan` 覆盖 `cn.zhishi.stock.news` |
| `stock-system/.../watchlist/WatchlistItemService.java` | 接 `NewsCountProvider`；`overview(...)` 新增 `newsSince` 参数；删掉 `NEWS_NOT_IMPLEMENTED` 占位 |
| `stock-system/.../watchlist/WatchlistEntry.java` | 重写 `latestNewsCount` 的注释（不再是"恒为 null"） |
| `stock-backend/.../web/WatchlistOverviewController.java` | 删掉 `newsSince` 的占位 400 分支，改为透传给用例 |
| `stock-job/.../JobConfiguration.java` | 补资讯采集链：`LimitRuleProvider` / `SecurityQuoteProvider` / `SecurityMasterProvider` / `SectorProvider` / `SecurityIdentityProvider` / `SectorIdentityProvider` / `NewsProvider` / 三个 Store / `RelationCatalogProvider` / `NewsIngestionService`。**刻意不声明 `NewsQueryService`**——定时任务不查询 |
| `stock-job/.../ScheduledNewsCollector.java`（新） | `@Scheduled` fixedDelay 2 分钟（`stock.news.collect-delay-ms`，默认 `120000`） |
| `stock-job/.../StockJobApplication.java` | 加 `@MapperScan(basePackages = "cn.zhishi.stock.news", annotationClass = Mapper.class)` |
| `stock-job/src/main/resources/application.yml` | 加 `stock.news.collect-initial-delay-ms` / `collect-delay-ms` |

**资讯六个端点没有拆进 `SecurityController` / `SectorController`。** STK-10 / SEC-07 的路径虽挂在
`securities` / `sectors` 下，但资源属于资讯域；写进行情侧控制器会让 `stock-backend` 的证券/板块 Web 层
**反向依赖资讯域**，而这两个控制器目前只依赖 `stock-market`。

### 6.3 测试

| 文件 | 内容 |
| --- | --- |
| `stock-backend/.../web/NewsControllerContractTest.java`（新，16 项） | 桩的是 `NewsQueryService`（业务规则已由 `NewsQueryServiceTest` 的 39 项覆盖），此处只钉 HTTP 面：路由、参数绑定、响应封套、异常→状态码 |
| `stock-backend/.../InfrastructureIntegrationTest.java` | 新增 `@Nested class News`（7 项，真实 MySQL）。**没有**新建 spec §5 里写的 `NewsRepositoryIntegrationTest`——既有文件已经有 3 个 `@Nested` 域，再开一个平行文件会让"迁移 + 仓储"两处装配逻辑重复 |
| `stock-backend/.../config/BackendConfigurationTest.java` | 补 3 个 Mapper 桩与 9 条资讯 Bean 断言，含 `assertThat(context.getBean(NewsQueryService.class)).isInstanceOf(NewsCountProvider.class)` |
| `stock-backend/.../security/SecurityConfigurationTest.java` | 公开路径数组补 6 条资讯路径 |
| `stock-backend/.../web/WatchlistOverviewControllerContractTest.java` | `overview(...)` 补第三参数；改写 `newsSince` 两个用例；新增 `latestNewsCount` 的真实值 / 未知值两个用例 |
| `stock-system/.../WatchlistItemServiceTest.java` | 装配加 `NewsCountProvider` 桩；`limitations` 断言由"含 M3-04 占位"改为 `isEmpty()`；新增 6 个资讯数用例 |
| `stock-job/.../ScheduledNewsCollectorTest.java`（新，5 项） | 不传游标、成功不留痕、失败留痕的时刻、失败向上抛、标记自身失败时保留原始异常 |
| `stock-job/.../JobConfigurationTest.java` | 由 1 项扩到 4 项：行情采集、资讯采集链、行情主数据 Provider 链、采集器本身可装配 |
| `stock-job/.../StockJobApplicationTest.java`（新，2 项） | 钉住 `@MapperScan` 覆盖 `cn.zhishi.stock.news` 且带 `annotationClass = Mapper.class` |

**`StockJobApplicationTest` 是本轮唯一能发现"`@MapperScan` 漏了新模块"的地方**：
`ApplicationContextRunner` 直接注册配置类，根本不走 `@MapperScan`，`JobConfigurationTest`
永远看不到这个问题。而 `annotationClass` 不能省——只给 `basePackages` 会让 MyBatis 把包下
**所有**接口注册成 Mapper，资讯域有三十多个领域端口接口，一旦被代理，正常装配就会被搅坏。

---

## 7. 已知取舍

| 取舍 | 代价 | 为什么仍然这样做 |
| --- | --- | --- |
| 新建第 7 个模块 `stock-news` | 5 处 pom/配置机械改动；与架构文档"10 模块"仍不一致（差 `stock-watchlist` / `stock-ai` / `stock-ai-worker`） | 资讯有独立持久化、独立端口、7 个契约接口，折进任何既有模块都会造成职责混杂 |
| 资讯落库，行情不落库 | 两种数据生命周期并存，新人需要读 spec 才能理解 | 去重需要跨批次记忆，这是内存无状态计算做不到的 |
| 精确指纹去重 | 抓不到"措辞不同的同一事件" | 先把链路跑通；相似度去重是独立课题，提前做会让本轮的验收标准失去焦点 |
| 模拟源刻意产出逐字相同的重复对 | 去重效果无法被真实评估 | 验收标准要求"指纹幂等"，必须有可复现的重复对才能证明它生效 |
| 关联阈值 `0.70` / `0.40` 是拍定的 | 阈值不可调（无配置项） | 模拟源下没有真实分布可供调参；做成配置项会掩盖"这个数还没有依据"这件事 |
| `MARKET` 关联只来自结构化提示，不做文本推断 | 大盘类资讯的关联覆盖不足 | "标题里出现'市场'就关联整个市场"是噪音生成器 |
| `MarketOverview.news` 本轮不动 | 首页快讯仍是写死的一条 | 改动会牵动 MKT-01 已冻结的 `componentStatus` 口径；归属 M3-05 |
| `stock-system` → `stock-news` 编译依赖 | 用户域多了一个跨域依赖 | 与既有 `stock-system` → `stock-market` 同因同形；只依赖 `domain`，无环 |
| 不做 Redis 最新资讯列表 | 每次查询直读 MySQL | 会为"最新资讯"造出第二处真相，与"同一个事实只允许一处实现"冲突 |

---

## 8. 验收结果

### 8.1 测试数（本机实测）

| 模块 | 用例数 | 说明 |
| --- | --- | --- |
| `stock-common` | 1 | — |
| `stock-market` | 156 | — |
| `stock-news` | 89 | 新模块：`NewsFingerprintTest` 9 / `NewsDeduplicatorTest` 5 / `NewsRelationResolverTest` 17 / `NewsIngestionServiceTest` 19 / `NewsQueryServiceTest` 39 |
| `stock-system` | 89 | `WatchlistItemServiceTest` 含 6 个资讯数用例 |
| `stock-integration` | 111 | — |
| `stock-backend` | 181 | 含 `NewsControllerContractTest` 16、`InfrastructureIntegrationTest$News` 7 |
| `stock-job` | 12 | 由 2 扩到 12 |
| **后端合计** | **639** | |
| 前端 | 133 / 20 文件 | 本轮未改前端，仅确认未回归 |

| 命令 | 结果 |
| --- | --- |
| `mvn verify` | **BUILD SUCCESS**，8 个模块全绿（含 Testcontainers 真实 MySQL） |
| `TZ=UTC mvn test` | **BUILD SUCCESS**，计数与上表一致 |

### 8.2 红灯记录

`stock-job` 三个测试类（11 项）首跑 **8 项失败**，全部是断言级失败：`JobConfigurationTest`
报 `no beans of that type`、`ScheduledNewsCollectorTest` 报 `Wanted but not invoked` 与
`Expecting actual not to be null`、`StockJobApplicationTest` 报 `Expecting actual not to be null`
（`@MapperScan` 尚未加）。**不是编译错误**——先落了一个只有签名的空壳 `collect()`，
让测试能编译，从而拿到真实的红灯。

### 8.3 真实端到端联调（本机 Docker，本轮实测通过）

`docker-compose up mysql redis flyway stock-api stock-job`（MySQL 8.4 + Redis 8.2，空库 Flyway V1→V8），
`curl` 按前端完全相同的请求形状走完六个接口 + WAT-11。**库里的数据全部由真实运行的定时任务采集而来**，
不是夹具：

| 项 | 实测结果 |
| --- | --- |
| 落库 | `news_source` 5 / `stock_news` 9（8 ORIGINAL + 1 DUPLICATE）/ `stock_news_relation` 8（6 CONFIRMED + 2 CANDIDATE） |
| **来源 ID 幂等** | 21:06:05 首采、21:08:05 第二轮（`fixedDelay` 120s 生效），第二轮**零新增行**（仍 9 / 8），`last_success_at` 刷新到 21:08:05 |
| **停用来源不被复活** | `SIM_MEDIA_C`（`authorization_status=SUSPENDED`）`last_success_at` 始终 `NULL`；NEWS-03 报 `availableSourceCount=4`（5 家里 1 家不可用） |
| **内容指纹去重** | `7331469565956110` 判为 `DUPLICATE`，`canonical_news_id=7331469565956100`，两者 `content_fingerprint` **逐位相同**（跨来源重复对） |
| **重复稿折叠** | NEWS-02 取重复稿 id 返回主记录（`newsId=7331469565956100`、来源换成媒体A、带上主记录的关联）；该重复稿在库里的关联数 **0** |
| **重复稿不进列表** | NEWS-01 `total=8`（库里 9 条），差的就是那条 DUPLICATE |
| **低置信不进默认视图** | 稿件 `7331469565956106` 有一条 `SECTOR`/`CANDIDATE` 关联；NEWS-02 的 `relations` 为 `[]`，SEC-07 `sim-bk0032` 的 `total=0`。**本轮最关键的一条验收，只有真库能验** |
| **对外标识** | 关联 `targetId` 分别是 `sim-002343` / `sim-bk0025` / `CN`（不是 bigint）；板块名 `公用事业` 由板块目录解析得出 |
| **纯日期端点** | `endAt=2026-09-18` → `total=8`（含当天 14:00 的稿件）；`endAt=2026-09-18T11:00:00+08:00` → `total=4`，**包含**恰好 11:00 那条、排除 11:30 那条 ⇒ 上界是闭区间 |
| **筛选** | `newsTypes=NEWS`→6、`NEWS,ANNOUNCEMENT`→7、`securityId=sim-002342`→1、`sectorId=sim-bk0025`→1、`marketCode=CN`→1、`keyword=公用事业`→1；`size=3&page=2` → `pages=3 hasNext=true` 且第二页首条正是全量第 4 条（`published_at DESC` 稳定） |
| **400** | 6 种非法输入全部 `INVALID_REQUEST`：`newsTypes=NEWSX`、`startAt=2026-13-99`、`page=0`、`size=101`、`marketCode=US`、`keyword` 51 字符（最后一条正是"修掉真实死代码"的证据） |
| **404** | `/news/999999999` 与 `/news/abc` 都是 `NEWS_NOT_FOUND`（不是 400）；未知证券 `SECURITY_NOT_FOUND`；未知板块 `SECTOR_NOT_FOUND` |
| **WAT-11 真实资讯数** | `sim-002342`→`1`、`sim-002340`→`1`、`sim-600519`（无资讯）→`null`；`limitations` 为 `[]`（M3-04 占位已删） |
| **WAT-11 的 `newsSince`** | `2026-09-18`→1/1；`2026-09-18T11:00:00+08:00`→`1` / `null`（该证券的稿件在 11:00 前）；`2026-09-19`→全 `null`；`abc`→400 |
| **WAT-11 空自选** | `snapshotVersion=""` / `dataStatus=UNAVAILABLE` / `dataTime=null` / `limitations=[]`——M3-03 的取舍保持不变 |

### 8.4 联调查出的问题

**1（已记入已知问题 #20，本轮记录不修）：`latestNewsCount` 无法表达"0 条"。**

`NewsCountProvider` 的端口注释写着"未命中的证券不出现在结果里，而不是映射成 `0`——
'这只证券最近没有资讯'与'我们不知道它有没有资讯'必须可区分"；
`WatchlistItemService.newsCountsOf` 的注释进一步说"后者在**资讯源不可用时**才是真相"。
但实现里**没有任何代码路径产生"未知"**：`NewsQueryService.countSince` 无论资讯源是否可用，
都只返回"有条数"的证券。于是：

- `sim-600519` 确实没有资讯 → `latestNewsCount = null`
- `newsSince=2026-09-19`（所有稿件都在 09-18）→ 三只全部 `null`

即 **"0 条"这个事实被写成了"不知道"**。前端拿到 `null` 只能渲染"—"或隐藏徽标，
无法显示"0 条"。这属于"数字对、结论错"的同一类问题，但**本轮不改**：

- 契约 §12.2 没有规定 `latestNewsCount` 的 `null` / `0` 语义，这是本仓库自定的口径；
- 要真正区分，需要让 WAT-11 也带上资讯域的新鲜度（`NewsSyncStatus.dataStatus`），
  那是给契约加字段，属于增量；
- M3-03 已立下"缺失即 `null`"的口径，且它与"口径宁可留空也不编造"一致。

**建议 M3-05 一并决定**：要么给 WAT-11 补一个资讯新鲜度字段，要么把缺失当 `0`
（此时"未知"只剩"悬空证券"一种）。**在那之前，前端不要把 `null` 渲染成"0 条"。**

**2（不是缺陷，但值得记住）：周末的资讯"看起来很旧"，`dataStatus` 仍然是 `REALTIME`。**

联调当天是 2026-09-20（周日），最新稿件是 2026-09-18（周五）的，而 NEWS-03 报 `overallStatus=OK`、
NEWS-01 报 `dataStatus=REALTIME`。这不是 bug：模拟源按**最近一个已完成交易日**生成稿件，
周末本来就没有新资讯，"刚刚成功采集过、且没有更新的资讯存在"就是 `REALTIME`。
**前端不要按"稿件日期不是今天"自行降级**（与已知问题 #19 同源）。

### 8.5 不在本轮范围

见 §7 与 `TASKS.md` 的「M3-04 交付详情 → 不在本轮范围」；两处必须一致。
