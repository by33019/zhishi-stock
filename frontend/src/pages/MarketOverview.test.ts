import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { MarketOverview as MarketOverviewData } from '@/types/domain'

const marketApi = vi.hoisted(() => ({ getMarketOverview: vi.fn() }))
vi.mock('@/services/marketApi', () => marketApi)

import MarketOverview from './MarketOverview.vue'

const overview: MarketOverviewData = {
  marketCode: 'CN',
  marketStatus: 'TRADING',
  tradeDate: '2026-09-13',
  dataTime: '2026-09-13T14:32:00+08:00',
  dataStatus: 'REALTIME',
  indices: [{ indexId: '1', indexCode: '000001', indexName: '上证指数', latestPoint: '3200', changeAmount: '10', changeRate: '0.005', region: 'DOMESTIC', sparkline: [3190, 3200] }],
  breadth: { riseCount: 2, fallCount: 1, flatCount: 0, limitUpCount: 1, limitDownCount: 0 },
  turnover: { amount: '100000000', previousAmount: '90000000', points: [1, 2] },
  sectors: [{ sectorId: '1', sectorCode: 'BK-AI', sectorName: '人工智能', changeRate: '0.02', tradeAmount: '100000000', leadingStock: '示例股份', companyCount: 20 }],
  rankings: [{ securityId: '1', securityCode: '600000', securityName: '示例股份', exchangeCode: 'SH', latestPrice: '10', changeAmount: '0.1', changeRate: '0.01', tradeVolume: '1000', tradeAmount: '100000000', turnoverRate: '0.02', sparkline: [9.9, 10] }],
  news: [{ newsId: '1', newsType: 'NEWS', title: '市场快讯', summary: '摘要', sourceName: '模拟资讯', publishedAt: '2026-09-13T14:20:00+08:00', relatedSymbols: [] }],
  componentStatus: { indices: 'REALTIME', breadth: 'REALTIME', turnover: 'REALTIME', sectors: 'REALTIME', rankings: 'REALTIME', news: 'REALTIME' },
  lastSuccessfulSyncAt: '2026-09-13T14:32:00+08:00',
  snapshotVersion: 'v1',
}

function mountPage() {
  return mount(MarketOverview, {
    global: {
      stubs: {
        RouterLink: { template: '<a><slot /></a>' },
        BaseChart: { template: '<div data-testid="chart-stub" />' },
      },
    },
  })
}

describe('市场总览页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    marketApi.getMarketOverview.mockResolvedValue(structuredClone(overview))
  })

  it('将市场证据、机会线索和数据时间组织在同一首屏', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('盘面温度')
    expect(wrapper.text()).toContain('上证指数')
    expect(wrapper.text()).toContain('市场广度')
    expect(wrapper.text()).toContain('热点板块')
    expect(wrapper.text()).toContain('行情热榜')
    expect(wrapper.text()).toContain('事件雷达')
    expect(wrapper.text()).toContain('14:32')
    expect(wrapper.findAll('[data-testid="chart-stub"]').length).toBeGreaterThanOrEqual(2)
  })

  it('明确提示延迟行情及最近成功同步时间', async () => {
    marketApi.getMarketOverview.mockResolvedValue({ ...structuredClone(overview), dataStatus: 'DELAYED' })
    const wrapper = mountPage()

    await flushPromises()

    expect(wrapper.get('[data-testid="market-data-status"]').text()).toContain('行情存在延迟')
    expect(wrapper.get('[data-testid="market-data-status"]').text()).toContain('最近同步')
  })

  it('组件部分失败时保留可用内容并标记失败组件', async () => {
    marketApi.getMarketOverview.mockResolvedValue({
      ...structuredClone(overview),
      dataStatus: 'DELAYED',
      sectors: [],
      componentStatus: { ...overview.componentStatus, sectors: 'UNAVAILABLE' },
    })
    const wrapper = mountPage()

    await flushPromises()

    expect(wrapper.text()).toContain('上证指数')
    expect(wrapper.text()).toContain('热点板块暂不可用')
  })

  it('全部核心行情不可用时展示可重试的完整失败状态', async () => {
    marketApi.getMarketOverview.mockRejectedValue({
      code: 'MARKET_DATA_UNAVAILABLE',
      message: '市场行情暂不可用',
      traceId: 'trace-503',
    })
    const wrapper = mountPage()

    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('市场行情暂不可用')
    expect(wrapper.get('[role="alert"]').text()).toContain('trace-503')
    expect(wrapper.get('[data-testid="market-retry"]').text()).toBe('重新加载')
  })
})
