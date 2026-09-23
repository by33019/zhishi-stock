import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { ExportJobView, RankingQuery, QuoteSnapshot, StockRanking } from '@/types/domain'

const rankingApi = vi.hoisted(() => ({ getStockRankings: vi.fn() }))
vi.mock('@/services/rankingApi', () => rankingApi)

const exportApi = vi.hoisted(() => ({
  createExportJob: vi.fn(),
  getExportJob: vi.fn(),
  downloadExportFile: vi.fn(),
}))
vi.mock('@/services/exportApi', () => exportApi)

// 真实的 `saveBlob` 要 `URL.createObjectURL`，jsdom 没有；这里换掉它，
// 顺带让"文件名到底传了什么"成为可断言的事实。
const download = vi.hoisted(() => ({ saveBlob: vi.fn() }))
vi.mock('@/utils/download', () => download)

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

function completedJob(overrides: Partial<ExportJobView> = {}): ExportJobView {
  return {
    exportId: '7332',
    exportType: 'STOCK_RANKING',
    status: 'COMPLETED',
    progress: 100,
    fileName: 'stock-ranking-gainers.xlsx',
    rowCount: 2079,
    createdAt: '2026-09-23T09:52:37+08:00',
    expiresAt: '2026-09-24T09:52:37+08:00',
    error: null,
    ...overrides,
  }
}

function exportButton(wrapper: ReturnType<typeof mountPage>, label = '导出 Excel') {
  return wrapper.findAll('button').find((button) => button.text().includes(label))
}

describe('行情榜单页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    rankingApi.getStockRankings.mockResolvedValue(ranking())
    exportApi.createExportJob.mockResolvedValue({ exportId: '7332' })
    exportApi.getExportJob.mockResolvedValue(completedJob())
    exportApi.downloadExportFile.mockResolvedValue({
      blob: new Blob(['xlsx']),
      fileName: '榜单-20260923.xlsx',
      dataCutoffAt: '2026-09-23T15:00+08:00',
    })
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

  it('导出把当前口径与交易所筛选发给 EXP-01，且**不带**分页参数', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text() === '跌幅榜')!.trigger('click')
    await wrapper.findAll('button').find((button) => button.text() === '深市')!.trigger('click')
    await flushPromises()

    await exportButton(wrapper)!.trigger('click')
    await flushPromises()

    const [request, idempotencyKey] = exportApi.createExportJob.mock.calls[0]!
    expect(request.exportType).toBe('STOCK_RANKING')
    expect(request.filters).toEqual({ rankingType: 'LOSERS', exchangeCodes: 'SZ' })
    // `page` / `size` 出现在导出请求里，用户会以为导出的是整份榜单，
    // 而文件里只有当前页那 20 行——这个错在文件打开前看不出来。
    expect(request.filters).not.toHaveProperty('page')
    expect(request.filters).not.toHaveProperty('size')
    expect(request).not.toHaveProperty('columns')
    // 幂等键必须由页面给出：service 里生成的话，401 自动重发会变成第二次导出。
    expect(idempotencyKey).toEqual(expect.any(String))

    expect(exportApi.downloadExportFile).toHaveBeenCalledWith('7332')
    expect(download.saveBlob).toHaveBeenCalledWith(expect.any(Blob), '榜单-20260923.xlsx')
  })

  it('导出进行中按钮禁用并显示排队中，避免连点产生第二个作业', async () => {
    let release: (value: ExportJobView) => void = () => {}
    exportApi.getExportJob.mockImplementation(
      () => new Promise<ExportJobView>((resolve) => {
        release = resolve
      }),
    )

    const wrapper = mountPage()
    await flushPromises()
    await exportButton(wrapper)!.trigger('click')
    await flushPromises()

    const busy = exportButton(wrapper, '排队中')
    expect(busy).toBeDefined()
    expect(busy!.attributes('disabled')).toBeDefined()

    release(completedJob())
    await flushPromises()
    // 结束后按钮回到可点状态，否则用户第二次导出就点不动了。
    expect(exportButton(wrapper)!.attributes('disabled')).toBeUndefined()
  })

  it('导出失败时给出原因与追踪编号，且不影响榜单本身的展示', async () => {
    exportApi.getExportJob.mockRejectedValue({
      code: 'EXPORT_RATE_LIMITED',
      message: '导出过于频繁，请稍后再试',
      traceId: 'trace-429',
    })

    const wrapper = mountPage()
    await flushPromises()
    await exportButton(wrapper)!.trigger('click')
    await flushPromises()

    const alert = wrapper.get('[data-testid="ranking-export-error"]')
    expect(alert.text()).toContain('导出过于频繁，请稍后再试')
    expect(alert.text()).toContain('trace-429')
    expect(wrapper.text()).toContain('共 45 个标的')
  })
})
