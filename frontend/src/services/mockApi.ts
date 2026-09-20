import type { ApiResponse, NewsItem } from '@/types/domain'

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

/**
 * 仅剩资讯这一条 mock 通路，归 M3-05（前端 news 接真实 API）——届时本文件整体删除。
 *
 * 原先还有一个 `getMarketOverview()` 与 `rankingRows`：前者的最后一个消费者是 M2-08
 * 把总览页接上 MKT-01 时消失的，后者被 `/watchlist` 读作榜单行（M3-03 改为读 WAT-11 的真实
 * 自选行情）。两者都只剩"自己的测试"在读，因此随 M3-03 一并移除——留着会让后来者以为
 * 总览页还在读它，而页面上的数字其实来自后端。
 */
export async function getNews(): Promise<ApiResponse<NewsItem[]>> {
  await pause()
  return respond(news)
}
