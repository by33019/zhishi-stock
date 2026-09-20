import type { ApiResponse, MarketOverview, NewsItem, QuoteRow } from '@/types/domain'

const now = '2026-09-08T14:32:18+08:00'

const news: NewsItem[] = [
  {
    newsId: '30010001',
    newsType: 'ANNOUNCEMENT',
    title: '浦发银行发布上半年经营数据，净息差环比企稳',
    summary: '公告显示资产质量保持稳定，零售业务结构继续调整。',
    sourceName: '上海证券交易所',
    publishedAt: '2026-09-08T13:56:00+08:00',
    relatedSymbols: ['SH.600000'],
  },
  {
    newsId: '30010002',
    newsType: 'NEWS',
    title: '大金融板块午后活跃，银行与保险成交同步放大',
    summary: '板块涨幅扩大，但北向资金与量能持续性仍需观察。',
    sourceName: '证券时报',
    publishedAt: '2026-09-08T13:42:00+08:00',
    relatedSymbols: ['BK0475', 'SH.600000'],
  },
  {
    newsId: '30010003',
    newsType: 'RESEARCH',
    title: '半导体设备订单能见度提升，行业分化仍然明显',
    summary: '成熟制程与先进封装方向景气度不同，需结合订单兑现节奏判断。',
    sourceName: '授权研究摘要',
    publishedAt: '2026-09-08T11:20:00+08:00',
    relatedSymbols: ['BK1036'],
  },
]

export const rankingRows: QuoteRow[] = [
  { securityId: '19876543210001', securityCode: '600000', securityName: '浦发银行', exchangeCode: 'SH', latestPrice: '12.35', changeAmount: '0.64', changeRate: '0.0547', tradeVolume: '328000000', tradeAmount: '3982000000', turnoverRate: '0.0118', sparkline: [11.8, 11.9, 12.0, 11.96, 12.12, 12.25, 12.35] },
  { securityId: '19876543210002', securityCode: '300750', securityName: '宁德时代', exchangeCode: 'SZ', latestPrice: '286.40', changeAmount: '11.22', changeRate: '0.0408', tradeVolume: '48200000', tradeAmount: '13520000000', turnoverRate: '0.0154', sparkline: [276, 278, 277, 281, 282, 285, 286.4] },
  { securityId: '19876543210003', securityCode: '688981', securityName: '中芯国际', exchangeCode: 'SH', latestPrice: '91.76', changeAmount: '3.12', changeRate: '0.0352', tradeVolume: '97600000', tradeAmount: '8920000000', turnoverRate: '0.0241', sparkline: [88.4, 89.1, 90.5, 89.8, 90.9, 91.2, 91.76] },
  { securityId: '19876543210004', securityCode: '600519', securityName: '贵州茅台', exchangeCode: 'SH', latestPrice: '1441.20', changeAmount: '-18.80', changeRate: '-0.0129', tradeVolume: '3180000', tradeAmount: '4590000000', turnoverRate: '0.0025', sparkline: [1460, 1456, 1459, 1450, 1448, 1443, 1441.2] },
  { securityId: '19876543210005', securityCode: '000858', securityName: '五粮液', exchangeCode: 'SZ', latestPrice: '137.68', changeAmount: '-2.46', changeRate: '-0.0176', tradeVolume: '24100000', tradeAmount: '3330000000', turnoverRate: '0.0063', sparkline: [140.1, 139.8, 139.2, 139.5, 138.4, 137.9, 137.68] },
]

const marketOverview: MarketOverview = {
  marketCode: 'CN',
  marketStatus: 'TRADING',
  tradeDate: '2026-09-08',
  dataTime: now,
  dataStatus: 'REALTIME',
  indices: [
    { indexId: '10001', indexCode: '000001', indexName: '上证指数', latestPoint: '3728.42', changeAmount: '21.86', changeRate: '0.0059', region: 'DOMESTIC', sparkline: [3706, 3714, 3709, 3718, 3721, 3728] },
    { indexId: '10002', indexCode: '399001', indexName: '深证成指', latestPoint: '11984.16', changeAmount: '102.28', changeRate: '0.0086', region: 'DOMESTIC', sparkline: [11881, 11902, 11910, 11954, 11942, 11984] },
    { indexId: '10003', indexCode: '399006', indexName: '创业板指', latestPoint: '2586.73', changeAmount: '31.28', changeRate: '0.0122', region: 'DOMESTIC', sparkline: [2555, 2564, 2561, 2574, 2580, 2586] },
    { indexId: '10004', indexCode: 'HSI', indexName: '恒生指数', latestPoint: '25714.32', changeAmount: '-86.42', changeRate: '-0.0033', region: 'OVERSEAS', sparkline: [25800, 25782, 25810, 25750, 25736, 25714] },
  ],
  breadth: { riseCount: 3140, fallCount: 1905, flatCount: 206, suspendedCount: 51, limitUpCount: 51, limitDownCount: 51 },
  turnover: { amount: '1023000000000', previousAmount: '941200000000', points: [612, 648, 701, 742, 805, 872, 936, 1023] },
  sectors: [
    { sectorId: '20001', sectorCode: 'BK0475', sectorName: '银行', changeRate: '0.0274', tradeAmount: '82100000000', leadingStock: '浦发银行', companyCount: 42 },
    { sectorId: '20002', sectorCode: 'BK1036', sectorName: '半导体设备', changeRate: '0.0231', tradeAmount: '116300000000', leadingStock: '中微公司', companyCount: 37 },
    { sectorId: '20003', sectorCode: 'BK0958', sectorName: '算力基础设施', changeRate: '0.0196', tradeAmount: '93200000000', leadingStock: '工业富联', companyCount: 54 },
    { sectorId: '20004', sectorCode: 'BK0438', sectorName: '白酒', changeRate: '-0.0128', tradeAmount: '61700000000', leadingStock: '金徽酒', companyCount: 20 },
  ],
  rankings: rankingRows,
  news,
  componentStatus: {
    indices: 'REALTIME',
    breadth: 'REALTIME',
    turnover: 'REALTIME',
    sectors: 'REALTIME',
    rankings: 'REALTIME',
    news: 'REALTIME',
  },
  lastSuccessfulSyncAt: now,
  snapshotVersion: 'mock-cn-20260908-143218',
}

function respond<T>(data: T, message = '查询成功'): ApiResponse<T> {
  return {
    success: true,
    code: 'SUCCESS',
    message,
    data,
    traceId: `mock-${crypto.randomUUID()}`,
    timestamp: now,
  }
}

async function pause() {
  await new Promise((resolve) => setTimeout(resolve, 40))
}

export async function getMarketOverview(): Promise<ApiResponse<MarketOverview>> {
  await pause()
  return respond(marketOverview)
}

export async function getNews(): Promise<ApiResponse<NewsItem[]>> {
  await pause()
  return respond(news)
}
