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

export interface SectorQuote {
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
  sectors: SectorQuote[]
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

export interface KlinePoint {
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
  kline: KlinePoint[]
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
