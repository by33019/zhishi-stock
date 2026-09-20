import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { RankingQuery, QuoteSnapshot, StockRanking } from '@/types/domain'

const rankingApi = vi.hoisted(() => ({ getStockRankings: vi.fn() }))
vi.mock('@/services/rankingApi', () => rankingApi)

import RankingsPage from './RankingsPage.vue'

function snapshot(name: string, changeRate: string): QuoteSnapshot {
  return {
    security: {
      securityId: `sim-${name}`,
      fullSymbol: `SH.${name}`,
      securityCode: name,
      securityName: name,
      exchangeCode: 'SH',
      securityType: 'STOCK',
      boardCode: 'MAIN',
      listingStatus: 'LISTED',
      isSt: false,
      isSuspended: false,
      priceScale: 2,
    },
    previousClosePrice: '10.00',
    openPrice: '10.10',
    latestPrice: '10.20',
    highPrice: '10.30',
    lowPrice: '10.00',
    changeAmount: '0.20',
    changeRate,
    tradeVolume: '1000000',
    tradeAmount: '10200000',
    turnoverRate: '0.0100',
    dataTime: '2026-09-20T06:32:00Z',
    serverTime: '2026-09-20T06:32:01Z',
    sequence: '900001',
    dataStatus: 'REALTIME',
    delaySeconds: null,
  }
}

function ranking(overrides: Partial<StockRanking> = {}): StockRanking {
  return {
    items: [snapshot('600000', '0.0200'), snapshot('600001', '0.0100')],
    page: 1,
    size: 20,
    total: 45,
    totalPages: 3,
    hasNext: true,
    rankingType: 'GAINERS',
    snapshotVersion: '900001',
    dataTime: '2026-09-20T06:32:00Z',
    dataStatus: 'REALTIME',
    ...overrides,
  }
}

function mountPage() {
  return mount(RankingsPage, {
    global: {
      stubs: { RouterLink: { template: '<a><slot /></a>' } },
    },
  })
}

/** 取最近一次真实榜单请求（排除"成交额最高"卡片那次）。 */
function lastRankingQuery(): RankingQuery {
  const calls = rankingApi.getStockRankings.mock.calls
  return calls[calls.length - 1]![0] as RankingQuery
}

describe('行情榜单页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    rankingApi.getStockRankings.mockResolvedValue(ranking())
  })

  it('按北京时间展示数据截止时间，并显示服务端返回的全市场总数', async () => {
    const wrapper = mountPage()
    await flushPromises()

    // UTC 06:32 即北京时间 14:32；断言写死偏移是为了让 TZ=UTC 环境下跑出同样结果
    expect(wrapper.text()).toContain('数据截止 09/20 14:32')
    expect(wrapper.text()).toContain('共 45 个标的')
    expect(wrapper.text()).toContain('第 1 / 3 页')
  })

  it('切换口径时把 rankingType 作为服务端参数重新请求，并把页码归 1', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const losers = wrapper.findAll('button').find((button) => button.text() === '跌幅榜')
    await losers!.trigger('click')
    await flushPromises()

    expect(lastRankingQuery()).toMatchObject({ rankingType: 'LOSERS', page: 1 })
  })

  it('交易所筛选拼成 exchangeCodes 参数；选"沪深京"时不传该参数', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const sz = wrapper.findAll('button').find((button) => button.text() === '深市')
    await sz!.trigger('click')
    await flushPromises()
    expect(lastRankingQuery().exchangeCodes).toBe('SZ')

    const all = wrapper.findAll('button').find((button) => button.text() === '沪深京')
    await all!.trigger('click')
    await flushPromises()
    expect(lastRankingQuery().exchangeCodes).toBeUndefined()
  })

  it('翻页请求下一页并保留当前口径', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const next = wrapper.findAll('button').find((button) => button.text() === '下一页')
    await next!.trigger('click')
    await flushPromises()

    expect(lastRankingQuery()).toMatchObject({ page: 2, rankingType: 'GAINERS' })
  })

  it('失败时给出追踪编号并可重试', async () => {
    rankingApi.getStockRankings.mockRejectedValue({
      code: 'MARKET_DATA_UNAVAILABLE',
      message: '市场行情暂不可用',
      traceId: 'trace-503',
    })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('市场行情暂不可用')
    expect(wrapper.get('[role="alert"]').text()).toContain('trace-503')

    rankingApi.getStockRankings.mockResolvedValue(ranking())
    await wrapper.get('[data-testid="ranking-retry"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('共 45 个标的')
  })
})
