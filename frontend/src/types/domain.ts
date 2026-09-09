export type AccessLevel = 'PUBLIC' | 'USER' | 'ADMIN'
export type DataStatus = 'REALTIME' | 'DELAYED' | 'STALE' | 'UNAVAILABLE'
export type Trend = 'up' | 'down' | 'flat'

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
  marketStatus: 'TRADING' | 'CLOSED' | 'BREAK'
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
