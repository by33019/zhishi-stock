import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { KlineQuery, KlineSeries, QuoteSnapshot } from '@/types/domain'

const securityApi = vi.hoisted(() => ({
  getSecurityQuote: vi.fn(),
  getSecurityKlines: vi.fn(),
}))
vi.mock('@/services/securityApi', () => securityApi)
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { id: 'sim-600000' } }) }))

import StockDetailPage from './StockDetailPage.vue'

const quote: QuoteSnapshot = {
  security: {
    securityId: 'sim-600000',
    fullSymbol: 'SH.600000',
    securityCode: '600000',
    securityName: '浦发银行',
    exchangeCode: 'SH',
    securityType: 'STOCK',
    boardCode: 'MAIN',
    listingStatus: 'LISTED',
    isSt: false,
    isSuspended: false,
    priceScale: 2,
  },
  previousClosePrice: '11.71',
  openPrice: '11.82',
  latestPrice: '12.35',
  highPrice: '12.48',
  lowPrice: '11.76',
  changeAmount: '0.64',
  changeRate: '0.0547',
  tradeVolume: '328000000',
  tradeAmount: '3982000000',
  turnoverRate: '0.0118',
  dataTime: '2026-09-20T06:32:00Z',
  serverTime: '2026-09-20T06:32:01Z',
  sequence: '900001',
  dataStatus: 'REALTIME',
  delaySeconds: null,
}

const klines: KlineSeries = {
  security: quote.security,
  period: 'DAY',
  adjustment: 'NONE',
  // 后端把它定义为最后一个点位的**收盘时刻**（sessionEndAt），不是日期零点
  dataCutoffAt: '2026-09-19T15:00:00+08:00',
  dataStatus: 'REALTIME',
  points: [
    {
      time: '2026-09-18',
      openPrice: '11.80',
      highPrice: '12.10',
      lowPrice: '11.70',
      closePrice: '12.05',
      previousClosePrice: '11.75',
      changeAmount: '0.30',
      changeRate: '0.0255',
      tradeVolume: '2100000',
      tradeAmount: '25200000',
      turnoverRate: '0.0100',
      qualityStatus: 'VALID',
    },
    {
      time: '2026-09-19',
      openPrice: '12.05',
      highPrice: '12.48',
      lowPrice: '12.00',
      closePrice: '12.35',
      previousClosePrice: '12.05',
      changeAmount: '0.30',
      changeRate: '0.0249',
      tradeVolume: '3280000',
      tradeAmount: '39820000',
      turnoverRate: '0.0118',
      qualityStatus: 'VALID',
    },
  ],
}

function mountPage() {
  return mount(StockDetailPage, {
    global: {
      stubs: {
        RouterLink: { template: '<a><slot /></a>' },
        BaseChart: { template: '<div data-testid="chart-stub" />' },
      },
    },
  })
}

function lastKlineQuery(): KlineQuery {
  const calls = securityApi.getSecurityKlines.mock.calls
  return calls[calls.length - 1]![1] as KlineQuery
}

describe('个股详情页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    securityApi.getSecurityQuote.mockResolvedValue(structuredClone(quote))
    securityApi.getSecurityKlines.mockResolvedValue(structuredClone(klines))
  })

  it('用快照的真实字段填充头部与指标条', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('浦发银行')
    expect(wrapper.text()).toContain('SH.600000')
    expect(wrapper.text()).toContain('12.35')
    expect(wrapper.text()).toContain('+5.47%')
    expect(wrapper.text()).toContain('今开')
    expect(wrapper.text()).toContain('11.82')
    expect(wrapper.text()).toContain('昨收')
    expect(wrapper.text()).toContain('11.71')
    // 数据截止按北京时间渲染
    expect(wrapper.text()).toContain('数据截止 09/20 14:32')
  })

  it('移除没有契约来源的市盈率，不再展示编造估值', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).not.toContain('市盈率')
    expect(wrapper.text()).not.toContain('6.21')
  })

  it('切换 K 线周期时把 period 作为服务端参数重新请求', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(lastKlineQuery().period).toBe('DAY')

    const week = wrapper.findAll('button').find((button) => button.text() === '周 K')
    await week!.trigger('click')
    await flushPromises()

    expect(lastKlineQuery().period).toBe('WEEK')
    expect(wrapper.find('[data-testid="chart-stub"]').exists()).toBe(true)
    // K 线的截止时间与快照的截止时间是两个独立字段，各自展示
    expect(wrapper.text()).toContain('数据截止 09/19 15:00')
  })

  it('K 线非实时点时给出数据质量提示', async () => {
    securityApi.getSecurityKlines.mockResolvedValue({
      ...structuredClone(klines),
      points: [{ ...klines.points[0]!, qualityStatus: 'DELAYED' }],
    })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.get('[data-testid="kline-quality"]').text()).toContain('非实时')
  })

  it('无后端实现的区块显示"尚未实现"而不是编造内容', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.get('[data-testid="profile-unavailable"]').text()).toContain('STK-08')
    expect(wrapper.get('[data-testid="news-unavailable"]').text()).toContain('STK-10')
    // 原型的"量价可信度：高"等 AI 速览内容必须消失
    expect(wrapper.text()).not.toContain('量价可信度')
  })

  it('未接入的操作按钮置为不可用，而不是点了没反应', async () => {
    const wrapper = mountPage()
    await flushPromises()

    // "加入自选" / "设预警"依赖 M3-01/M3-02，做成可点按钮会让人以为操作已生效
    const actions = wrapper.get('.stock-actions')
    expect(actions.findAll('button').every((button) => button.attributes('disabled') !== undefined))
      .toBe(true)
    expect(actions.text()).toContain('加入自选')
    expect(actions.text()).toContain('设预警')
  })

  it('快照失败时给出追踪编号并可重试', async () => {
    securityApi.getSecurityQuote.mockRejectedValue({
      code: 'SECURITY_NOT_FOUND',
      message: '证券不存在',
      traceId: 'trace-404',
    })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('证券不存在')
    expect(wrapper.get('[role="alert"]').text()).toContain('trace-404')

    securityApi.getSecurityQuote.mockResolvedValue(structuredClone(quote))
    await wrapper.get('[data-testid="stock-detail-retry"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('浦发银行')
  })
})
