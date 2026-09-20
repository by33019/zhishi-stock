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
 * 板块卡片原型数据（字段是简化版，`leadingStock` 只有名称、没有可跳转的 `securityId`）。
 *
 * 名字带 `Mock` 前缀是为了与契约类型 {@link SectorQuote}（对应 RESTful-API.md §10 SEC-02）
 * 区分：两者字段不同，同名会触发 TypeScript 的声明合并，
 * 让 mock 数据因"缺少契约字段"而报错。M2-08 接入真实接口后，本类型随
 * `/sectors` 页面一并由契约类型取代（同 {@link MockKlinePoint}）。
 */
export interface MockSectorQuote {
  sectorId: string
  sectorCode: string
  sectorName: string
  changeRate: string
  tradeAmount: string
  leadingStock: string
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
  sectors: MockSectorQuote[]
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

/**
 * 个股详情原型用的 K 线点（简化字段名，价格是 number）。
 *
 * 名字带 `Mock` 前缀是为了与契约类型 {@link KlinePoint}（对应 RESTful-API.md §8.2）
 * 区分：两者字段不同，同名会触发 TypeScript 的声明合并，
 * 让 mock 数据因"缺少契约字段"而报错。M2-08 接入真实接口后，本类型随
 * `StockDetail` 一并由契约类型取代。
 */
export interface MockKlinePoint {
  time: string
  open: number
  close: number
  low: number
  high: number
  volume: number
}

export interface StockDetail extends QuoteRow {
  fullSymbol: string
  previousClosePrice: string
  openPrice: string
  highPrice: string
  lowPrice: string
  peRatio: string
  marketCap: string
  businessDescription: string
  sectors: string[]
  dataTime: string
  dataStatus: DataStatus
  kline: MockKlinePoint[]
  news: NewsItem[]
  aiPrompts: string[]
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
