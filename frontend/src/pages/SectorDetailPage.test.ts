import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { PageData, QuoteSnapshot, SectorConstituent, SectorDetail } from '@/types/domain'

const sectorApi = vi.hoisted(() => ({
  getSectorDetail: vi.fn(),
  getSectorConstituents: vi.fn(),
}))
vi.mock('@/services/sectorApi', () => sectorApi)
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { id: 'sim-bk0006' } }) }))

import SectorDetailPage from './SectorDetailPage.vue'

function constituent(
  code: string,
  changeRate: string | null,
  contributionRank: number,
): SectorConstituent {
  const quote: QuoteSnapshot = {
    security: {
      securityId: `sim-${code}`,
      fullSymbol: `SH.${code}`,
      securityCode: code,
      securityName: `证券${code}`,
      exchangeCode: 'SH',
      securityType: 'STOCK',
      boardCode: 'MAIN',
      listingStatus: changeRate === null ? 'SUSPENDED' : 'LISTED',
      isSt: false,
      isSuspended: changeRate === null,
      priceScale: 2,
    },
    previousClosePrice: '10.00',
    openPrice: changeRate === null ? null : '10.10',
    latestPrice: changeRate === null ? null : '10.20',
    highPrice: changeRate === null ? null : '10.30',
    lowPrice: changeRate === null ? null : '10.00',
    changeAmount: changeRate === null ? null : '0.20',
    changeRate,
    tradeVolume: changeRate === null ? null : '1000000',
    tradeAmount: changeRate === null ? null : '10200000',
    turnoverRate: changeRate === null ? null : '0.0100',
    dataTime: '2026-09-20T06:32:00Z',
    serverTime: '2026-09-20T06:32:01Z',
    sequence: '900001',
    dataStatus: 'REALTIME',
    delaySeconds: null,
  }
  return { quote, relationType: 'PRIMARY', isPrimary: true, contributionRank }
}

const detail: SectorDetail = {
  sector: {
    sectorId: 'sim-bk0006',
    sectorCode: 'BK0006',
    sectorName: '银行',
    sectorType: 'INDUSTRY',
    parentId: 'sim-bk0001',
    levelNo: 2,
  },
  parent: {
    sectorId: 'sim-bk0001',
    sectorCode: 'BK0001',
    sectorName: '金融',
    sectorType: 'INDUSTRY',
    parentId: null,
    levelNo: 1,
  },
  quote: {
    sectorId: 'sim-bk0006',
    sectorCode: 'BK0006',
    sectorName: '银行',
    sectorType: 'INDUSTRY',
    companyCount: 42,
    averagePrice: '12.35',
    changeRate: '0.0274',
    tradeVolume: '328000000',
    tradeAmount: '82100000000',
    leadingStock: null,
    laggingStock: null,
    dataTime: '2026-09-20T06:32:00Z',
    dataStatus: 'REALTIME',
  },
}

const constituents: PageData<SectorConstituent> = {
  items: [
    constituent('600000', '0.0547', 1),
    constituent('600001', '-0.0210', 2),
    constituent('600002', null, 3),
  ],
  page: 1,
  size: 100,
  total: 3,
  totalPages: 1,
  hasNext: false,
}

function mountPage() {
  return mount(SectorDetailPage, {
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
}

describe('板块详情页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    sectorApi.getSectorDetail.mockResolvedValue(structuredClone(detail))
    sectorApi.getSectorConstituents.mockResolvedValue(structuredClone(constituents))
  })

  it('头部展示板块代码、成分股数、所属大类与北京时间数据截止', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('BK0006')
    expect(wrapper.text()).toContain('银行')
    expect(wrapper.text()).toContain('42 家成分股')
    expect(wrapper.text()).toContain('金融')
    expect(wrapper.text()).toContain('数据截止 09/20 14:32')
    expect(wrapper.text()).toContain('+2.74%')
  })

  it('成分股表格使用服务端给的贡献度排名，而不是当前页内的序号', async () => {
    sectorApi.getSectorConstituents.mockResolvedValue({
      ...structuredClone(constituents),
      items: [constituent('600000', '0.0547', 7), constituent('600001', '-0.0210', 8)],
    })
    const wrapper = mountPage()
    await flushPromises()

    const ranks = wrapper.findAll('.rank-cell').map((cell) => cell.text())
    expect(ranks).toEqual(['7', '8'])
  })

  it('涨跌家数由真实成分股重新计数，停牌既不记涨也不记跌', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const structure = wrapper.get('.evidence-note').text()
    expect(structure).toContain('上涨 / 下跌')
    expect(structure).toContain('停牌')
    // 1 涨、1 跌、1 停牌；且必须写明分母，否则会被读成整个板块
    expect(structure).toContain('基于已取回的 3 只成分股统计')
  })

  it('板块走势区块不绘制示意曲线，而是说明接口尚未实现', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.get('[data-testid="sector-trend-unavailable"]').text()).toContain('SEC-05')
    expect(wrapper.find('canvas').exists()).toBe(false)
  })

  it('板块不存在时展示后端返回的 404 文案与追踪编号', async () => {
    sectorApi.getSectorDetail.mockRejectedValue({
      code: 'SECTOR_NOT_FOUND',
      message: '板块不存在',
      traceId: 'trace-404',
    })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('板块不存在')
    expect(wrapper.get('[role="alert"]').text()).toContain('trace-404')
  })
})
