import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'

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
  breadth: { riseCount: 2, fallCount: 1, flatCount: 0, suspendedCount: 0, limitUpCount: 1, limitDownCount: 0 },
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
    // 固定"今天"，否则交易日标记的断言会随运行日期漂移
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.setSystemTime(new Date('2026-09-18T02:00:00Z')) // 北京时间 09/18 10:00
    marketApi.getMarketOverview.mockResolvedValue(structuredClone(overview))
  })

  afterEach(() => vi.useRealTimers())

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

  it('非交易日标注交易日并说明展示的是上一交易日收盘数据', async () => {
    vi.setSystemTime(new Date('2026-09-20T02:00:00Z')) // 北京时间 09/20（周日）
    marketApi.getMarketOverview.mockResolvedValue({
      ...structuredClone(overview),
      marketStatus: 'CLOSED',
      tradeDate: '2026-09-18',
      dataTime: '2026-09-18T15:00:00+08:00',
    })
    const wrapper = mountPage()
    await flushPromises()

    const badge = wrapper.get('[data-testid="trade-date-badge"]').text()
    expect(badge).toContain('交易日 09/18')
    // 周日显示"已收盘"会让人以为今天开过市
    expect(badge).toContain('非交易日')
    expect(wrapper.get('h1').text()).toContain('今日休市')
    expect(wrapper.get('h1').text()).toContain('09/18')
  })

  it('交易日盘后标"已收盘"而不是"非交易日"', async () => {
    vi.setSystemTime(new Date('2026-09-18T12:00:00Z')) // 北京时间 09/18 20:00
    marketApi.getMarketOverview.mockResolvedValue({
      ...structuredClone(overview),
      marketStatus: 'CLOSED',
      tradeDate: '2026-09-18',
      dataTime: '2026-09-18T15:00:00+08:00',
    })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.get('[data-testid="trade-date-badge"]').text()).toContain('已收盘')
    expect(wrapper.get('[data-testid="trade-date-badge"]').text()).not.toContain('非交易日')
    expect(wrapper.get('h1').text()).toContain('今日已收盘')
  })

  it('盘中不显示交易日标记', async () => {
    marketApi.getMarketOverview.mockResolvedValue({
      ...structuredClone(overview),
      marketStatus: 'TRADING',
      tradeDate: '2026-09-18',
      dataTime: '2026-09-18T10:00:00+08:00',
    })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.find('[data-testid="trade-date-badge"]').exists()).toBe(false)
    expect(wrapper.get('h1').text()).toContain('今日市场')
  })

  it('首屏导语由真实广度数据拼出，而不是写死一段行情判断', async () => {
    marketApi.getMarketOverview.mockResolvedValue({
      ...structuredClone(overview),
      breadth: { riseCount: 3200, fallCount: 1500, flatCount: 300, suspendedCount: 149, limitUpCount: 42, limitDownCount: 7 },
    })
    const wrapper = mountPage()
    await flushPromises()

    const summary = wrapper.get('[data-testid="market-lead-summary"]').text()
    expect(summary).toContain('5000 只交易标的')
    expect(summary).toContain('3200 只上涨')
    expect(summary).toContain('涨多跌少')
    expect(summary).toContain('涨停 42 只')
    // 原型那段"金融与科技方向形成共振"没有任何数据支撑
    expect(wrapper.text()).not.toContain('温和放量')
    expect(wrapper.text()).not.toContain('形成共振')
  })

  it('较昨日涨跌幅由响应里的成交额算出，不是写死的常量', async () => {
    marketApi.getMarketOverview.mockResolvedValue({
      ...structuredClone(overview),
      turnover: { amount: '100000000000', previousAmount: '80000000000', points: [1, 2] },
    })
    const wrapper = mountPage()
    await flushPromises()

    const change = wrapper.get('[data-testid="turnover-change"]').text()
    expect(change).toBe('较昨日 +25.00%')
    // 原型写死 +8.69%，与它自己引用的 amount / previousAmount 矛盾
    expect(wrapper.text()).not.toContain('8.69%')
  })

  it('缺少上一期成交额时显示 -- 而不是编一个涨跌幅', async () => {
    marketApi.getMarketOverview.mockResolvedValue({
      ...structuredClone(overview),
      turnover: { amount: '100000000000', previousAmount: '0', points: [1, 2] },
    })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.get('[data-testid="turnover-change"]').text()).toBe('较昨日 --')
  })

  it('未接入的 AI 解读按钮是禁用态，而不是点了没反应的死按钮', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const button = wrapper.get('.market-lead__aside button')
    expect(button.attributes('disabled')).toBeDefined()
    expect(button.attributes('title')).toContain('M3-06')
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
