import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { SectorQuote, SectorRanking, SectorRankingQuery } from '@/types/domain'

const sectorApi = vi.hoisted(() => ({ getSectorRankings: vi.fn() }))
vi.mock('@/services/sectorApi', () => sectorApi)

import SectorsPage from './SectorsPage.vue'

function quote(overrides: Partial<SectorQuote> = {}): SectorQuote {
  return {
    sectorId: 'sim-bk0006',
    sectorCode: 'BK0006',
    sectorName: '银行',
    sectorType: 'INDUSTRY',
    companyCount: 42,
    averagePrice: '12.35',
    changeRate: '0.0274',
    tradeVolume: '328000000',
    tradeAmount: '82100000000',
    leadingStock: {
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
      latestPrice: '12.35',
      changeRate: '0.0547',
    },
    laggingStock: null,
    dataTime: '2026-09-20T06:32:00Z',
    dataStatus: 'REALTIME',
    ...overrides,
  }
}

function ranking(items: SectorQuote[], overrides: Partial<SectorRanking> = {}): SectorRanking {
  return {
    items,
    page: 1,
    size: 100,
    total: items.length,
    totalPages: 1,
    hasNext: false,
    sectorType: null,
    rankingType: 'GAINERS',
    snapshotVersion: '900001',
    dataTime: '2026-09-20T06:32:00Z',
    dataStatus: 'REALTIME',
    ...overrides,
  }
}

function mountPage() {
  return mount(SectorsPage, {
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
}

describe('板块分析页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    sectorApi.getSectorRankings.mockImplementation((query: SectorRankingQuery) =>
      Promise.resolve(query.rankingType === 'TURNOVER'
        ? ranking([quote({ sectorId: 'sim-bk0007', sectorName: '半导体设备', tradeAmount: '116300000000' })])
        : ranking([
          quote(),
          quote({ sectorId: 'sim-bk0008', sectorName: '白酒', changeRate: '-0.0128', leadingStock: null }),
        ])))
  })

  it('卡片展示接口返回的成分股数、代码、成交额与领涨股', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const card = wrapper.get('.sector-card')
    expect(card.text()).toContain('银行')
    expect(card.text()).toContain('42 家成分股 · BK0006')
    expect(card.text()).toContain('821.00亿')
    expect(card.text()).toContain('浦发银行')
  })

  it('领涨股为空时渲染占位符，不保留任何编造名称', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const cards = wrapper.findAll('.sector-card')
    expect(cards[1]!.text()).toContain('--')
    expect(cards[1]!.text()).not.toContain('浦发银行')
  })

  it('摘要由真实数据驱动：涨幅第一、成交额第一与上涨板块计数', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const brief = wrapper.get('.sector-brief').text()
    expect(brief).toContain('银行')
    expect(brief).toContain('半导体设备')
    expect(brief).toContain('2 个板块中 1 个上涨')
  })

  it('失败时给出追踪编号并可重试', async () => {
    sectorApi.getSectorRankings.mockRejectedValue({
      code: 'SECTOR_QUOTE_NOT_AVAILABLE',
      message: '板块行情暂不可用',
      traceId: 'trace-503',
    })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('板块行情暂不可用')
    expect(wrapper.get('[role="alert"]').text()).toContain('trace-503')
    expect(wrapper.get('[data-testid="sectors-retry"]').exists()).toBe(true)
  })
})
