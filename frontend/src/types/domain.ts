export type AccessLevel = 'PUBLIC' | 'USER' | 'ADMIN'
export type DataStatus = 'REALTIME' | 'DELAYED' | 'STALE' | 'UNAVAILABLE'
export type Trend = 'up' | 'down' | 'flat'

/** 成交趋势档位；TODAY 为分钟粒度，5D / 20D 为日粒度。 */
export type TurnoverRange = 'TODAY' | '5D' | '20D'

/** 粗粒度市场状态，跨市场通用，用于视觉与文案分支。 */
export type MarketSessionStatus = 'PRE_OPEN' | 'CALL_AUCTION' | 'TRADING' | 'BREAK' | 'CLOSED'

/** 细粒度交易时段阶段，与后端 TradingSession 一一对应。 */
export type TradingSession =
  | 'PRE_OPEN'
  | 'OPENING_CALL_AUCTION'
  | 'MORNING_CONTINUOUS'
  | 'LUNCH_BREAK'
  | 'AFTERNOON_CONTINUOUS'
  | 'CLOSING_CALL_AUCTION'
  | 'CLOSED'

/**
 * MKT-02 响应体：市场交易状态。
 *
 * `isTradingDay` 的命名是刻意的：后端 Java 字段叫 `tradingDay`，靠
 * `@JsonProperty("isTradingDay")` 显式改了 JSON 名以与契约逐字一致。
 * 写成 `tradingDay` 不会报错，只会静默拿到 `undefined`——
 * 于是"是不是交易日"的分支永远走 else。
 */
export interface MarketStatus {
  marketCode: string
  tradeDate: string
  isTradingDay: boolean
  sessionStatus: MarketSessionStatus
  currentSession: TradingSession
  /** 下一时段开始时间；日历未给出未来交易日时为 null。 */
  nextSessionAt: string | null
  calendarSourceTime: string
}

export interface ApiResponse<T> {
  success: true
  code: 'SUCCESS'
  message: string
  data: T
  traceId: string
  timestamp: string
}

/** 通用分页响应数据，对应后端 PageData。 */
export interface PageData<T> {
  items: T[]
  page: number
  size: number
  total: number
  totalPages: number
  hasNext: boolean
}

export interface MarketIndex {
  indexId: string
  indexCode: string
  indexName: string
  latestPoint: string
  changeAmount: string
  changeRate: string
  region: 'DOMESTIC' | 'OVERSEAS'
  sparkline: number[]
}

export interface BreadthData {
  riseCount: number
  fallCount: number
  flatCount: number
  suspendedCount: number
  limitUpCount: number
  limitDownCount: number
}

/**
 * 市场总览里的板块预览行（对应后端 `MarketOverview.SectorPreview`）。
 *
 * **它不是 mock 数据**：`MarketOverview.vue` 已经接真实接口，本类型就是那一段的契约。
 * 字段比 §10 SEC-02 的 {@link SectorQuote} 少，且 `leadingStock` 只有名称、没有可跳转的
 * `securityId`——总览的预览卡片不需要跳转，详情与完整行情走 `/sector-rankings`。
 *
 * 两个类型刻意不同名：同名会触发 TypeScript 的声明合并，
 * 让其中一方因"缺少另一方字段"而报错。
 */
export interface OverviewSectorQuote {
  sectorId: string
  sectorCode: string
  sectorName: string
  changeRate: string
  tradeAmount: string
  /**
   * 领涨股名称；板块内没有任何可统计行情的成分股时为 `null`（后端不编造）。
   * 当前预览口径（GAINERS）下它必非空——排序键可用即意味着至少有一只成分股有有效行情——
   * 但类型照实写可空，免得换口径时前端把 `null` 渲染成空白。
   */
  leadingStock: string | null
  companyCount: number
}

export interface QuoteRow {
  securityId: string
  securityCode: string
  securityName: string
  exchangeCode: 'SH' | 'SZ' | 'BJ'
  latestPrice: string
  changeAmount: string
  changeRate: string
  tradeVolume: string
  tradeAmount: string
  turnoverRate: string
  sparkline: number[]
}

/**
 * MKT-01 首页快讯（`MarketOverview.news`）的形状，**不是**资讯域的 `NewsSummary`。
 *
 * 差别是真实的：这里用 `relatedSymbols: string[]`（只有符号），资讯域用
 * `relations: NewsRelationSummary[]`（带目标类型、名称与关联方式）。总览页的 `news`
 * 目前仍是单条模拟资讯（见 `TASKS.md` 的 MKT-01 交付详情），改它等于改 MKT-01 已冻结的
 * `componentStatus` 口径，属于另一个里程碑。
 *
 * **不要**为了让两处类型名统一而合并它们——合并会让「总览页的资讯到底来自哪里」看不出来。
 */
export interface NewsItem {
  newsId: string
  newsType: 'NEWS' | 'ANNOUNCEMENT' | 'RESEARCH'
  title: string
  summary: string
  sourceName: string
  publishedAt: string
  relatedSymbols: string[]
}

export interface MarketOverview {
  marketCode: 'CN'
  marketStatus: MarketSessionStatus
  tradeDate: string
  dataTime: string
  dataStatus: DataStatus
  indices: MarketIndex[]
  breadth: BreadthData
  turnover: {
    amount: string
    previousAmount: string
    points: number[]
  }
  sectors: OverviewSectorQuote[]
  rankings: QuoteRow[]
  news: NewsItem[]
  componentStatus: Record<string, DataStatus>
  lastSuccessfulSyncAt: string
  snapshotVersion: string
}

/** MKT-04 成交趋势的单位声明：金额为人民币元，成交量为股。 */
export interface TurnoverTrendUnit {
  tradeAmount: string
  tradeVolume: string
}

/**
 * 单个趋势点。盘中档位 time 为带时区偏移的分钟边界、数值为开盘以来的累计值；
 * 跨日档位 time 为交易日 yyyy-MM-dd、数值为该日全天总量。
 */
export interface TurnoverTrendPoint {
  time: string
  tradeAmount: string
  tradeVolume: string
}

export interface TurnoverTrend {
  marketCode: string
  range: TurnoverRange
  /** 盘中档位为 1m / 5m / 15m / 30m / 60m；跨日档位为 null。 */
  interval: string | null
  unit: TurnoverTrendUnit
  dataCutoffAt: string
  points: TurnoverTrendPoint[]
}

export interface AiEvidence {
  evidenceNo: number
  evidenceType: 'QUOTE' | 'KLINE' | 'NEWS' | 'ANNOUNCEMENT' | 'SECTOR'
  sourceTitle: string
  evidenceSummary: string
  dataTime: string
}

export interface AiReport {
  reportId: string
  qualityStatus: 'VALID' | 'LIMITED'
  coreConclusion: string
  quoteEvidence: string
  eventClues: string
  riskAndUncertainty: string
  generatedAt: string
  evidence: AiEvidence[]
}

/** 证券类别，对应 stock_security.security_type。 */
export type SecurityType = 'STOCK' | 'ETF' | 'INDEX'

/** 上市状态，对应 stock_security.listing_status。 */
export type ListingStatus = 'LISTED' | 'SUSPENDED' | 'DELISTED' | 'PRELISTED'

/** 搜索命中的字段；后端按 CODE → NAME → PINYIN → PINYIN_ABBR 的优先级取第一个命中。 */
export type SecurityMatchedField = 'CODE' | 'NAME' | 'PINYIN' | 'PINYIN_ABBR'

/** 证券摘要，对应后端 SecuritySummary 与 RESTful-API.md §4.1。 */
export interface SecuritySummary {
  securityId: string
  fullSymbol: string
  securityCode: string
  securityName: string
  exchangeCode: string
  securityType: SecurityType
  boardCode: string | null
  listingStatus: ListingStatus
  isSt: boolean
  isSuspended: boolean
  priceScale: number
}

/** 一条搜索命中。`highlight` 是命中字段中的原文子串，不含任何标记。 */
export interface SecuritySearchMatch {
  security: SecuritySummary
  matchedField: SecurityMatchedField
  highlight: string | null
}

/** STK-01 搜索建议响应。 */
export interface SecuritySearchResult {
  items: SecuritySearchMatch[]
}

/** STK-02 列表查询参数。 */
export interface SecurityListQuery {
  keyword?: string
  securityType?: SecurityType
  exchangeCode?: string
  boardCode?: string
  listingStatus?: ListingStatus
  sectorId?: string
  page?: number
  size?: number
  sort?: string
}

/**
 * 个股行情快照，对应后端 QuoteSnapshot 与 RESTful-API.md §4.2。
 *
 * 价格与量额一律是十进制定点数字符串：前端不做浮点运算，
 * 字符串是唯一能保证「展示值 === 服务端值」的载体。
 */
export interface QuoteSnapshot {
  security: SecuritySummary
  previousClosePrice: string | null
  openPrice: string | null
  latestPrice: string | null
  highPrice: string | null
  lowPrice: string | null
  changeAmount: string | null
  /** 小数比例，`0.10` 即 10%。 */
  changeRate: string | null
  tradeVolume: string | null
  tradeAmount: string | null
  turnoverRate: string | null
  dataTime: string | null
  serverTime: string
  sequence: string
  dataStatus: DataStatus
  delaySeconds: number | null
}

/** K 线周期。 */
export type KlinePeriod = 'DAY' | 'WEEK' | 'MONTH'

/** 复权方式；MVP 仅支持 `NONE`，前复权与后复权纳入 V1.2。 */
export type KlineAdjustment = 'NONE'

/** K 线点的数据质量状态。 */
export type KlineQualityStatus = 'VALID' | 'DELAYED' | 'CORRECTED'

/**
 * 单个 K 线点，对应 RESTful-API.md §8.2。
 *
 * `time` 是交易日；周 K / 月 K 取该周期**最后一个交易日**。
 */
export interface KlinePoint {
  time: string
  openPrice: string
  highPrice: string
  lowPrice: string
  closePrice: string
  previousClosePrice: string | null
  changeAmount: string | null
  changeRate: string | null
  tradeVolume: string
  tradeAmount: string
  turnoverRate: string | null
  qualityStatus: KlineQualityStatus
}

/** STK-07 响应。 */
export interface KlineSeries {
  security: SecuritySummary
  period: KlinePeriod
  adjustment: KlineAdjustment
  /** 数据可信边界，恒等于最后一个点位的时刻。 */
  dataCutoffAt: string | null
  dataStatus: DataStatus
  points: KlinePoint[]
}

/** STK-07 查询参数。 */
export interface KlineQuery {
  period: KlinePeriod
  /** 缺省为最近 120 个交易日。 */
  startDate?: string
  endDate?: string
  adjustment?: KlineAdjustment
}

/** QTE-01 榜单口径。 */
export type RankingType = 'GAINERS' | 'LOSERS' | 'TURNOVER'

/**
 * QTE-01 榜单响应。
 *
 * 外层刻意是**扁平**的（`items` 与分页字段同级），与后端 `StockRanking` 一致。
 *
 * `snapshotVersion` 等于每一行的 `sequence`，`dataTime` 等于每一行的 `dataTime`——
 * 整个榜单来自同一批快照，因此这两项可以直接用于页面级的数据截止提示。
 */
export interface StockRanking {
  items: QuoteSnapshot[]
  page: number
  size: number
  total: number
  totalPages: number
  hasNext: boolean
  rankingType: RankingType
  snapshotVersion: string
  dataTime: string | null
  dataStatus: DataStatus
}

/** QTE-01 查询参数。 */
export interface RankingQuery {
  rankingType: RankingType
  /** 逗号分隔的多值筛选，如 `SH,SZ`；取值不存在时返回空页而非报错。 */
  exchangeCodes?: string
  /** 逗号分隔的多值筛选，如 `MAIN,GEM`。 */
  boardCodes?: string
  /** 按板块成分关系筛选；板块 ID 不存在时返回空页。 */
  sectorId?: string
  /** 默认 `false`。 */
  excludeSt?: boolean
  /** 默认 `true`：PRD 要求停牌与无有效价格的证券默认排除。 */
  excludeSuspended?: boolean
  page?: number
  /** 1 至 100，默认 20。 */
  size?: number
}

/** 板块类型，取值与 `stock_sector.sector_type` 的 CHECK 约束同集合。 */
export type SectorType = 'INDUSTRY' | 'CONCEPT' | 'REGION'

/** 板块状态。它是服务端的筛选规则输入，**不进任何响应体**。 */
export type SectorStatus = 'ACTIVE' | 'INACTIVE'

/** 证券与板块的关系类型，对应 `stock_security_sector.relation_type`。 */
export type SectorRelationType = 'PRIMARY' | 'SECONDARY' | 'MEMBER'

/**
 * 板块主数据，对应 RESTful-API.md §10 SEC-01。
 *
 * `levelNo` 从 1 开始；`parentId` 为 `null` 表示一级板块。
 * 注意**地域与概念板块的 `levelNo` 也是 1**——它们本就没有父级，
 * 因此判断"是不是一级大类"必须同时看 `sectorType === 'INDUSTRY'`。
 */
export interface Sector {
  sectorId: string
  sectorCode: string
  sectorName: string
  sectorType: SectorType
  parentId: string | null
  levelNo: number
}

/** SEC-01 查询参数。 */
export interface SectorListQuery {
  sectorType?: SectorType
  parentId?: string
  /** 对 `sectorCode` / `sectorName` 做包含匹配，大小写不敏感。 */
  keyword?: string
  /** 默认 `ACTIVE`：普通用户默认只能查询有效板块。 */
  status?: SectorStatus
}

/** SEC-01 响应。板块不分页（39 个），因此不带分页字段。 */
export interface SectorList {
  items: Sector[]
}

/**
 * 板块内的领涨 / 领跌股，对应 §10 SEC-02 / SEC-04。
 *
 * 内嵌完整 `SecuritySummary` 而不是只给名称：前端要能直接跳转个股页，
 * 把名称当 ID 用会跳到不存在的证券。
 */
export interface SectorLeaderStock {
  security: SecuritySummary
  latestPrice: string | null
  changeRate: string | null
}

/**
 * 板块行情统计，同时是 SEC-02 的 `items[]` 行与 SEC-03 / SEC-04 的核心内容。
 *
 * `averagePrice` / `changeRate` 可为 `null`：板块全部成分股停牌时"平均涨跌幅"没有定义。
 * 展示层不得把 `null` 补成 `0`——那会被读成"板块平盘"。
 *
 * `companyCount` 含停牌成分股，而 `averagePrice` / `changeRate` 的样本**不含**停牌，
 * 因此不能用 `companyCount` 反推均值。
 */
export interface SectorQuote {
  sectorId: string
  sectorCode: string
  sectorName: string
  sectorType: SectorType
  companyCount: number
  averagePrice: string | null
  /** 小数比例，`0.10` 即 10%。 */
  changeRate: string | null
  tradeVolume: string
  tradeAmount: string
  leadingStock: SectorLeaderStock | null
  laggingStock: SectorLeaderStock | null
  dataTime: string | null
  dataStatus: DataStatus
}

/**
 * SEC-02 板块排行响应。
 *
 * 与 {@link StockRanking} 一样是**扁平**的（`items` 与分页字段同级），
 * 因此前端可以用同一套解引用逻辑处理两个排行榜。
 */
export interface SectorRanking {
  items: SectorQuote[]
  page: number
  size: number
  total: number
  totalPages: number
  hasNext: boolean
  /** 未指定时为 `null`（表示未按类型筛选）。 */
  sectorType: SectorType | null
  rankingType: RankingType
  snapshotVersion: string
  dataTime: string | null
  dataStatus: DataStatus
}

/** SEC-02 查询参数。 */
export interface SectorRankingQuery {
  sectorType?: SectorType
  /** 默认 `GAINERS`。 */
  rankingType?: RankingType
  page?: number
  /** 1 至 100，默认 20。 */
  size?: number
}

/** SEC-03 响应。`parent` 在一级板块或父板块不存在时为 `null`。 */
export interface SectorDetail {
  sector: Sector
  parent: Sector | null
  quote: SectorQuote
}

/**
 * SEC-06 的成分股行。
 *
 * `contributionRank` 是**数据属性**（板块内按涨跌幅降序的序号，从 1 开始），
 * 不随查询的 `rankingType` 变化：否则按跌幅排序时"第 1 名"看起来会变成板块龙头。
 * 无有效行情的成分股（停牌等）排在有效项之后，按代码升序续号。
 */
export interface SectorConstituent {
  quote: QuoteSnapshot
  relationType: SectorRelationType
  isPrimary: boolean
  contributionRank: number
}

/** SEC-06 查询参数。 */
export interface ConstituentQuery {
  /** `yyyy-MM-dd`；缺省表示按当前有效关系解析。 */
  effectiveDate?: string
  /** 成分股排序口径，默认 `GAINERS`。 */
  rankingType?: RankingType
  page?: number
  /** 1 至 100，默认 20。 */
  size?: number
}

/**
 * 自选分组摘要，对应 WAT-01 / WAT-11 的 `groups[]`。
 *
 * WAT-11 的 `groups[]` **不带** `items`（那是 WAT-01 `includeItems=true` 才有的字段），
 * 所以这里不声明它——声明成可选会让调用方写出 `group.items?.length ?? group.itemCount`，
 * 于是两处口径分叉且都不会报错。
 */
export interface WatchlistGroup {
  groupId: string
  groupName: string
  sortNo: number
  isDefault: boolean
  /** 由服务端统计。**不要**用客户端列表长度替代：那会让侧栏与列表互相矛盾。 */
  itemCount: number
  version: number
}

/**
 * 自选项行，对应 WAT-06 / WAT-11 的 `items[]`。
 *
 * `security` 与 `quote` 都可能为 `null`：前者是"证券不在主数据里"，后者是"该证券当前没有快照"。
 * 契约要求"行情不可用时仍返回自选关系"，因此**条目一定在**，调用方不得因此过滤掉它。
 *
 * `latestNewsCount` 在资讯 Provider 就位（M3-04）之前恒为 `null`。
 * 渲染成 `0` 就是编造"这只股票今天没有新闻"。
 */
export interface WatchlistItem {
  itemId: string
  groupId: string
  security: SecuritySummary | null
  sortNo: number
  version: number
  createdAt: string
  quote: QuoteSnapshot | null
  latestNewsCount: number | null
}

/**
 * WAT-11 自选中心首屏聚合响应。
 *
 * 一次请求同时给出分组、自选行情与市场状态，且 `snapshotVersion` / `dataTime` / `dataStatus`
 * 描述的是**同一批**自选行情——因此页面可以放心把它们并排展示，不存在"两个时刻混在一屏"。
 *
 * `limitations` 是契约"数据状态字段"的落点：人可读的降级说明，空数组表示没有任何降级。
 * 资讯域就位前它必然非空。
 */
export interface WatchlistOverview {
  groups: WatchlistGroup[]
  items: WatchlistItem[]
  marketStatus: MarketStatus
  /** 整批为空时为 `''`（后端不编造版本号），展示层据此显示 `--`。 */
  snapshotVersion: string
  dataStatus: DataStatus
  dataTime: string | null
  limitations: string[]
}

/**
 * WAT-07 的响应。
 *
 * 契约在这里列了 `createdAt`、**没有** `quote` 与 `latestNewsCount`（与 WAT-06 的差异是契约明写的）。
 * 因此它**不是** {@link WatchlistItem} 的别名，也不能写成 `Partial<WatchlistItem>`。
 */
export interface CreatedWatchlistItem {
  itemId: string
  groupId: string
  security: SecuritySummary | null
  sortNo: number
  version: number
  createdAt: string
}

/**
 * WAT-09 的响应。
 *
 * `merged=true` 表示目标组已有同证券：**源行被删除**，这里返回的是目标组那一行
 * （它的 `version` 不变）。前端不得假设"返回的一定是刚移动的那一行"。
 */
export interface MovedWatchlistItem {
  itemId: string
  groupId: string
  sortNo: number
  version: number
  merged: boolean
}

/** WAT-10 响应元素：更新后的项顺序与版本。 */
export interface WatchlistItemOrder {
  itemId: string
  groupId: string
  sortNo: number
  version: number
}

/** WAT-08 响应。`deleted` 反映真实影响行数（重复删除是幂等成功，但 `deleted` 为 `false`）。 */
export interface DeletedWatchlistItem {
  deleted: boolean
}

/**
 * WAT-02 的响应。
 *
 * 契约在这里列了 `createdAt`、**没有** `itemCount`——新建分组必然为空，
 * 服务端不返回这个字段，前端也不该自己填 `0`。
 */
export interface CreatedWatchlistGroup {
  groupId: string
  groupName: string
  sortNo: number
  isDefault: boolean
  version: number
  createdAt: string
}

/** WAT-04 响应。`movedItemCount` 是搬移到目标组的条数（合并掉的重复项不计入）。 */
export interface DeletedWatchlistGroup {
  deleted: boolean
  movedItemCount: number
}

/**
 * 资讯域契约类型（NEWS-01~04）。
 *
 * 与 MKT-01 的 `NewsItem` **不是**同一形状：后者是总览页的首页快讯（`relatedSymbols: string[]`），
 * 这里是资讯域的 `NewsSummary`（`relations: NewsRelationSummary[]`）。理由见 `NewsItem` 的注释。
 */

/** 契约 §4.3 的 `newsType`，与后端 `NewsType` 同集合。 */
export type NewsType = 'NEWS' | 'ANNOUNCEMENT' | 'RESEARCH' | 'OTHER'

/** 契约 §4.3 的 `relations[].targetType`，与后端 `NewsTargetType` 同集合。 */
export type NewsTargetType = 'SECURITY' | 'SECTOR' | 'MARKET'

/** 契约 §4.3 的 `originalAccessStatus`：原文是否可访问。 */
export type NewsOriginalAccessStatus = 'AVAILABLE' | 'UNAVAILABLE' | 'UNKNOWN'

/** 关联的产生方式，与后端 `NewsRelationMethod` 同集合。 */
export type NewsRelationMethod = 'EXPLICIT' | 'RULE' | 'MODEL' | 'MANUAL'

/**
 * 资讯的确认关联（`relationStatus = CONFIRMED`）。
 *
 * `targetId` 是**对外标识**（`sim-600519` / `sim-bk0025` / `CN`），可直接用于路由；
 * 库里的 bigint 代理键不会出现在响应里。
 */
export interface NewsRelationSummary {
  targetType: NewsTargetType
  targetId: string
  targetCode: string
  targetName: string
  relationMethod: NewsRelationMethod
  confidenceScore: number
}

/** 契约 §4.3 `NewsSummary`。`summary` 是**授权范围内**摘要，可能为 `null`。 */
export interface NewsSummary {
  newsId: string
  newsType: NewsType
  title: string
  summary: string | null
  sourceName: string
  authorName: string | null
  publishedAt: string
  collectedAt: string
  originalUrl: string
  originalAccessStatus: NewsOriginalAccessStatus
  relations: NewsRelationSummary[]
}

/**
 * NEWS-01 / STK-10 / SEC-07 的响应：**扁平**封套（`items` 与分页字段同级），
 * 与 QTE-01 的 `StockRanking` 同形。
 *
 * `lastSuccessfulSyncAt` / `dataStatus` 描述的是**整批资讯**的新鲜度，不是这一页的属性。
 */
export interface NewsPage {
  items: NewsSummary[]
  page: number
  size: number
  total: number
  totalPages: number
  hasNext: boolean
  lastSuccessfulSyncAt: string | null
  dataStatus: DataStatus
}

/** NEWS-04 的可用时间范围。库里没有资讯时两端都是 `null`——**不要**用「今天」填充。 */
export interface NewsTimeRange {
  startAt: string | null
  endAt: string | null
}

/**
 * NEWS-04 受控筛选项。
 *
 * `newsTypes` / `sourceTypes` 的取值**来自服务端**，前端据此渲染标签，
 * 这样服务端新增一种资讯类型时前端会自动多出一个标签，而不是静默丢掉。
 * `filterRules` 是当前生效的筛选规则说明，用于回答「为什么列表里只有这几条」。
 */
export interface NewsOptions {
  newsTypes: string[]
  sourceTypes: string[]
  availableTimeRange: NewsTimeRange
  filterRules: string
}

/**
 * NEWS-01 查询参数。
 *
 * `newsTypes` 为空时**不传**该参数（`toQueryString` 会丢弃空串），表示「不限类型」，
 * 而不是传空串让服务端去猜。
 */
export interface NewsQuery {
  newsTypes?: string
  keyword?: string
  page?: number
  size?: number
}

// ---------------------------------------------------------------------------
// AI 会话历史（契约 §13.3 HIS-01 / HIS-02 / HIS-05、§4.4 AiTaskSummary）
//
// 字段名一律照契约，包括 `isFavorite` / `isLimited`：后端用 `@JsonProperty` 对齐过，
// 前端这里也按契约写，两边都改才叫"对齐"，只有一边改是漂移。
// ---------------------------------------------------------------------------

/** 场景码，与后端 `AiScene` 枚举同集合。 */
export type AiScene = 'MARKET' | 'SECTOR' | 'STOCK' | 'STOCK_RISK' | 'COMPARE'

/** 会话状态。只声明前端会走到的取值；服务端新增状态时 UI 落到默认分支而不是编译失败。 */
export type AiSessionStatus = 'ACTIVE' | 'DELETED'

/** 会话内最近任务的精简视图（HIS-01 列表用）。 */
export interface AiSessionLastTask {
  taskId: string
  status: string
}

/** HIS-01 列表项。 */
export interface AiSessionSummary {
  sessionId: string
  scene: AiScene
  title: string
  status: AiSessionStatus
  isFavorite: boolean
  /** 从未跑过任务时为 `null`——据此渲染"还没有分析"，而不是"状态未知"。 */
  lastTask: AiSessionLastTask | null
  lastActivityAt: string
  createdAt: string
  version: number
}

/**
 * HIS-01 查询参数。
 *
 * `keyword` **只匹配会话标题**（服务端实现口径）。所以界面的提示语必须说"搜索标题"，
 * 不能写成"搜索问题、标的或报告内容"——那会让用户以为能搜正文。
 */
export interface AiSessionQuery {
  scene?: AiScene | ''
  keyword?: string
  favorite?: boolean
  startAt?: string
  endAt?: string
  page?: number
  size?: number
}

/** 分析目标。`targetId` 是可解析的对外标识（`sim-600519`），不是数据库代理键。 */
export interface AiContextTarget {
  targetType: string
  targetId: string
  targetCode: string
  targetName: string
  targetRole: string
}

/** 任务摘要（契约 §4.4）。HIS-02 的 `lastTask` 用它。 */
export interface AiTaskSummary {
  taskId: string
  sessionId: string
  scene: AiScene
  status: string
  targets: AiContextTarget[]
  question: string | null
  progressStage: string
  createdAt: string
  firstChunkAt: string | null
  completedAt: string | null
  reportId: string | null
  error: { category: string; code: string; message: string; retryable: boolean } | null
}

/** 报告摘要（HIS-02 的 `lastReport`）。六章节正文属 HIS-06，这里只给"是哪份、质量如何"。 */
export interface AiReportBrief {
  reportId: string
  qualityStatus: string
  isLimited: boolean
  generatedAt: string
}

/** HIS-02 会话详情。 */
export interface AiSessionDetail {
  sessionId: string
  scene: AiScene
  title: string
  status: AiSessionStatus
  isFavorite: boolean
  targets: AiContextTarget[]
  lastTask: AiTaskSummary | null
  lastReport: AiReportBrief | null
  lastActivityAt: string
  createdAt: string
  version: number
}

export type AiMessageRole = 'USER' | 'ASSISTANT' | 'SYSTEM'

/**
 * HIS-05 消息。
 *
 * 服务端已按契约排除 `SYSTEM` 行，因此前端拿不到内部 Prompt；保留该取值只是让类型
 * 穷尽后端枚举，渲染时对未知角色不输出任何内容。
 *
 * 契约 §HIS-05 还列了 `contentFormat` / `status`，但 `ai_message` 表没有这两列，
 * 服务端刻意不返回（不编造）。因此这里也没有它们——哪天补上，类型要一起改。
 */
export interface AiMessage {
  messageId: string
  taskId: string | null
  roleType: AiMessageRole
  sequenceNo: number
  content: string
  dataCutoffAt: string | null
  createdAt: string
}

/**
 * HIS-03 的响应：更新后的会话与**新版本**。
 *
 * 服务端回传新版本，是为了让下一次提交能直接用它——否则只能重新拉一次详情，
 * 而那次拉取与本次写入之间又可能被别人改动，把乐观锁的收益丢掉一半。
 */
export interface AiSessionUpdated {
  sessionId: string
  title: string
  isFavorite: boolean
  version: number
}

/** HIS-04 的响应。`purgeAfter` 是数据彻底消失的时间（默认删除后 30 天）。 */
export interface AiSessionDeletion {
  deleted: boolean
  purgeAfter: string
}
