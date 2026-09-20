import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/services/apiClient'
import type {
  MarketStatus,
  QuoteSnapshot,
  SecuritySummary,
  WatchlistGroup,
  WatchlistItem,
  WatchlistOverview,
} from '@/types/domain'

const watchlistApi = vi.hoisted(() => ({
  getWatchlistOverview: vi.fn(),
  createWatchlistGroup: vi.fn(),
  renameWatchlistGroup: vi.fn(),
  deleteWatchlistGroup: vi.fn(),
  reorderWatchlistGroups: vi.fn(),
  addWatchlistItem: vi.fn(),
  removeWatchlistItem: vi.fn(),
  moveWatchlistItem: vi.fn(),
  reorderWatchlistItems: vi.fn(),
}))
vi.mock('@/services/watchlistApi', () => watchlistApi)

const securityApi = vi.hoisted(() => ({ searchSecurities: vi.fn() }))
vi.mock('@/services/securityApi', () => securityApi)

import WatchlistPage from './WatchlistPage.vue'

const GROUP_MAIN = '7001'
const GROUP_FINANCE = '7002'
const GROUP_EMPTY = '7003'
const ITEM_A = '8001'
const ITEM_B = '8002'

function summary(code: string, overrides: Partial<SecuritySummary> = {}): SecuritySummary {
  return {
    securityId: `sim-${code}`,
    fullSymbol: `SH.${code}`,
    securityCode: code,
    securityName: `模拟证券${code}`,
    exchangeCode: 'SH',
    securityType: 'STOCK',
    boardCode: 'MAIN',
    listingStatus: 'LISTED',
    isSt: false,
    isSuspended: false,
    priceScale: 2,
    ...overrides,
  }
}

function quote(code: string, changeRate: string | null): QuoteSnapshot {
  return {
    security: summary(code),
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
    // UTC 07:00 即北京时间 15:00；写死偏移是为了让 TZ=UTC 下跑出同样结果。
    dataTime: '2026-09-20T07:00:00Z',
    serverTime: '2026-09-20T07:00:01Z',
    sequence: '900001',
    dataStatus: 'REALTIME',
    delaySeconds: null,
  }
}

function group(overrides: Partial<WatchlistGroup> & { groupId: string }): WatchlistGroup {
  return {
    groupName: `分组${overrides.groupId}`,
    sortNo: 0,
    isDefault: false,
    itemCount: 0,
    version: 0,
    ...overrides,
  }
}

function item(
  overrides: Partial<WatchlistItem> & { itemId: string; groupId: string },
): WatchlistItem {
  return {
    security: summary('600519'),
    sortNo: 0,
    version: 0,
    createdAt: '2026-09-20T07:00:00Z',
    quote: quote('600519', '0.0200'),
    latestNewsCount: null,
    ...overrides,
  }
}

function marketStatus(): MarketStatus {
  return {
    marketCode: 'CN',
    tradeDate: '2026-09-20',
    isTradingDay: false,
    sessionStatus: 'CLOSED',
    currentSession: 'CLOSED',
    nextSessionAt: null,
    calendarSourceTime: '2026-09-20T07:00:00Z',
  }
}

/** 默认夹具：默认分组 2 条（其中一条无行情）+ 金融组 1 条 + 空组 1 个。 */
function overview(overrides: Partial<WatchlistOverview> = {}): WatchlistOverview {
  return {
    groups: [
      group({ groupId: GROUP_MAIN, groupName: '默认分组', isDefault: true, itemCount: 2 }),
      group({ groupId: GROUP_FINANCE, groupName: '金融', sortNo: 1, itemCount: 1 }),
      group({ groupId: GROUP_EMPTY, groupName: '高股息', sortNo: 2, itemCount: 0 }),
    ],
    items: [
      item({ itemId: ITEM_A, groupId: GROUP_MAIN, sortNo: 0, version: 3 }),
      item({
        itemId: ITEM_B,
        groupId: GROUP_MAIN,
        sortNo: 1,
        security: summary('300750', { exchangeCode: 'SZ' }),
        quote: null,
      }),
      item({
        itemId: '8003',
        groupId: GROUP_FINANCE,
        security: summary('600000'),
        quote: quote('600000', '-0.0100'),
      }),
    ],
    marketStatus: marketStatus(),
    snapshotVersion: 'sim-20260920',
    dataStatus: 'REALTIME',
    dataTime: '2026-09-20T07:00:00Z',
    limitations: ['最新资讯数尚未实现（资讯 Provider 见 M3-04），latestNewsCount 恒为 null'],
    ...overrides,
  }
}

function mountPage() {
  return mount(WatchlistPage, {
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
}

async function mountReady() {
  const wrapper = mountPage()
  await flushPromises()
  return wrapper
}

function cards(wrapper: Awaited<ReturnType<typeof mountPage>>) {
  return wrapper.findAll('[data-testid="watch-card"]')
}

describe('我的自选页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    watchlistApi.getWatchlistOverview.mockResolvedValue(overview())
    watchlistApi.createWatchlistGroup.mockResolvedValue({})
    watchlistApi.renameWatchlistGroup.mockResolvedValue({})
    watchlistApi.deleteWatchlistGroup.mockResolvedValue({ deleted: true, movedItemCount: 0 })
    watchlistApi.reorderWatchlistGroups.mockResolvedValue([])
    watchlistApi.addWatchlistItem.mockResolvedValue({})
    watchlistApi.removeWatchlistItem.mockResolvedValue({ deleted: true })
    watchlistApi.moveWatchlistItem.mockResolvedValue({ merged: false })
    watchlistApi.reorderWatchlistItems.mockResolvedValue([])
    securityApi.searchSecurities.mockResolvedValue({ items: [] })
  })

  it('首屏只发一次 WAT-11，不额外请求分组或市场状态', async () => {
    await mountReady()

    expect(watchlistApi.getWatchlistOverview).toHaveBeenCalledTimes(1)
    expect(securityApi.searchSecurities).not.toHaveBeenCalled()
  })

  it('侧栏分组名与数量来自服务端，列表行数与该组 itemCount 一致', async () => {
    const wrapper = await mountReady()

    const tabs = wrapper.findAll('[data-testid="group-tab"]')
    expect(tabs.map((tab) => tab.text())).toEqual([
      '默认分组2',
      '金融1',
      '高股息0',
    ])
    // 默认分组 itemCount=2 → 列表必须有 2 张卡片；不能出现"侧栏写 5、列表 2 条"
    expect(cards(wrapper)).toHaveLength(2)
  })

  it('按北京时间展示数据截止时间，并逐条展示后端给的 limitations', async () => {
    const wrapper = await mountReady()

    expect(wrapper.text()).toContain('数据截止 09/20 15:00')
    const limitations = wrapper.get('[data-testid="watchlist-limitations"]')
    expect(limitations.text()).toContain('M3-04')
  })

  it('不渲染分时 sparkline，卡片里也不出现 latestNewsCount（两者都没有数据源）', async () => {
    const wrapper = await mountReady()

    expect(wrapper.find('.watch-sparkline').exists()).toBe(false)
    // limitations 里会出现"最新资讯数尚未实现"，因此只断言卡片本身不含任何资讯字段
    cards(wrapper).forEach((card) => {
      expect(card.text()).not.toContain('资讯')
    })
  })

  it('导语的涨跌只数取自真实行情，无行情的标的不计入涨跌', async () => {
    const wrapper = await mountReady()

    const summaryText = wrapper.get('[data-testid="watch-summary"]').text()
    expect(summaryText).toContain('共 2 只标的')
    expect(summaryText).toContain('1 只上涨')
    expect(summaryText).toContain('1 只无有效行情')
    // 无行情的标的既不是上涨也不是平盘——把它算成平盘是最容易犯的错
    expect(summaryText).not.toContain('平盘')
  })

  it('切换分组不重新请求，只改当前显示的那一组', async () => {
    const wrapper = await mountReady()

    const finance = wrapper
      .findAll('[data-testid="group-tab"]')
      .find((tab) => tab.text().startsWith('金融'))!
    await finance.trigger('click')
    await flushPromises()

    expect(watchlistApi.getWatchlistOverview).toHaveBeenCalledTimes(1)
    expect(cards(wrapper)).toHaveLength(1)
    expect(cards(wrapper)[0]!.attributes('data-item-id')).toBe('8003')
  })

  it('无行情的卡片保留在列表里，价格显示 --，但仍可跳转详情', async () => {
    const wrapper = await mountReady()

    const missing = cards(wrapper).find((card) => card.attributes('data-item-id') === ITEM_B)!
    expect(missing.text()).toContain('--')
    // 缺的是**行情**不是标的：详情页读 STK-04/STK-07，与这条快照无关，因此不该失去入口
    expect(missing.findAll('a').length).toBeGreaterThan(0)
  })

  it('主数据缺失的卡片保留在列表里，名称显示占位且不渲染跳转链接', async () => {
    watchlistApi.getWatchlistOverview.mockResolvedValue(
      overview({
        items: [item({ itemId: ITEM_B, groupId: GROUP_MAIN, security: null, quote: null })],
      }),
    )
    const wrapper = await mountReady()

    const dangling = cards(wrapper).find((card) => card.attributes('data-item-id') === ITEM_B)!
    expect(dangling.get('[data-testid="card-missing-security"]').text()).toContain('证券主数据缺失')
    // 没有 securityId 就拼不出路由，宁可不可点也不要给出一个指向 /stocks/undefined 的链接
    expect(dangling.findAll('a')).toHaveLength(0)
  })

  it('停牌标的单独计数，不算平盘也不参与涨幅居首', async () => {
    // 真实数据里 sim-300750 就是 isSuspended:true 且**有**快照、changeRate 为 "0.0000"
    watchlistApi.getWatchlistOverview.mockResolvedValue(
      overview({
        items: [
          item({ itemId: ITEM_A, groupId: GROUP_MAIN, quote: quote('600519', '0.0200') }),
          item({
            itemId: ITEM_B,
            groupId: GROUP_MAIN,
            security: summary('300750', { isSuspended: true, listingStatus: 'SUSPENDED' }),
            quote: quote('300750', '0.0000'),
          }),
        ],
      }),
    )
    const wrapper = await mountReady()

    const summaryText = wrapper.get('[data-testid="watch-summary"]').text()
    expect(summaryText).toContain('共 2 只标的')
    expect(summaryText).toContain('1 只上涨')
    expect(summaryText).toContain('1 只无有效行情')
    // 把停牌算成平盘是最容易犯的错：0.0000 在数值上确实是平盘
    expect(summaryText).not.toContain('平盘')
  })

  it('未接入的 AI 解读按钮保持 disabled 并写明归属任务', async () => {
    const wrapper = await mountReady()

    const ai = cards(wrapper)[0]!.get('[data-action="ai"]')
    expect(ai.attributes('disabled')).toBeDefined()
    expect(ai.attributes('title')).toContain('M3-10')
  })

  it('添加自选：提交选股面板的搜索词，选中后用幂等键调用 WAT-07 并重新拉取', async () => {
    securityApi.searchSecurities.mockResolvedValue({
      items: [{ security: summary('600519'), matchedField: 'CODE', highlight: '600519' }],
    })
    const wrapper = await mountReady()

    await wrapper.get('[data-testid="add-item-open"]').trigger('click')
    await wrapper.get('[data-testid="add-item-input"]').setValue('600519')
    await wrapper.get('[data-testid="add-item-search"]').trigger('submit')
    await flushPromises()

    expect(securityApi.searchSecurities).toHaveBeenCalledWith('600519', 10)

    await wrapper.get('[data-testid="add-item-option"]').trigger('click')
    await flushPromises()

    const [groupId, securityId, key] = watchlistApi.addWatchlistItem.mock.calls[0]!
    expect(groupId).toBe(GROUP_MAIN)
    expect(securityId).toBe('sim-600519')
    expect(key).toBeTruthy()
    // 写操作后必须重新拉取，而不是本地推断新状态
    expect(watchlistApi.getWatchlistOverview).toHaveBeenCalledTimes(2)
  })

  it('移出自选：带上该项所在的分组与项 ID，成功后重新拉取', async () => {
    const wrapper = await mountReady()

    await cards(wrapper)[0]!.get('[data-action="remove"]').trigger('click')
    await flushPromises()

    expect(watchlistApi.removeWatchlistItem).toHaveBeenCalledWith(GROUP_MAIN, ITEM_A)
    expect(watchlistApi.getWatchlistOverview).toHaveBeenCalledTimes(2)
  })

  it('移动到其他分组：If-Match 用该项自己的 version', async () => {
    const wrapper = await mountReady()

    await cards(wrapper)[0]!.get('[data-testid="item-actions-toggle"]').trigger('click')
    const target = wrapper
      .findAll('[data-testid="item-move-target"]')
      .find((button) => button.attributes('data-group-id') === GROUP_FINANCE)!
    await target.trigger('click')
    await flushPromises()

    expect(watchlistApi.moveWatchlistItem).toHaveBeenCalledWith(
      GROUP_MAIN,
      ITEM_A,
      GROUP_FINANCE,
      3,
    )
  })

  it('组内重排：itemIds 是组内全部项拖拽后的顺序', async () => {
    const wrapper = await mountReady()

    await cards(wrapper)[0]!.trigger('dragstart')
    await cards(wrapper)[1]!.trigger('drop')
    await flushPromises()

    expect(watchlistApi.reorderWatchlistItems).toHaveBeenCalledWith(GROUP_MAIN, [ITEM_B, ITEM_A])
  })

  it('分组重排：groupIds 是本人全部有效分组的新排列', async () => {
    const wrapper = await mountReady()

    const tabs = wrapper.findAll('[data-testid="group-tab"]')
    await tabs[0]!.trigger('dragstart')
    await tabs[1]!.trigger('drop')
    await flushPromises()

    expect(watchlistApi.reorderWatchlistGroups).toHaveBeenCalledWith([
      GROUP_FINANCE,
      GROUP_MAIN,
      GROUP_EMPTY,
    ])
  })

  it('重命名分组：If-Match 用该组的 version', async () => {
    const wrapper = await mountReady()

    await wrapper
      .findAll('[data-testid="group-actions-toggle"]')
      .find((button) => button.attributes('data-group-id') === GROUP_FINANCE)!
      .trigger('click')
    await wrapper.get('[data-testid="group-rename"]').trigger('click')
    await wrapper.get('[data-testid="group-rename-input"]').setValue('大金融')
    await wrapper.get('[data-testid="group-rename-form"]').trigger('submit')
    await flushPromises()

    expect(watchlistApi.renameWatchlistGroup).toHaveBeenCalledWith(GROUP_FINANCE, '大金融', 0)
  })

  it('删除非空分组：带上目标分组，且目标不等于被删的分组自身', async () => {
    const wrapper = await mountReady()

    await wrapper
      .findAll('[data-testid="group-actions-toggle"]')
      .find((button) => button.attributes('data-group-id') === GROUP_FINANCE)!
      .trigger('click')
    await wrapper.get('[data-testid="group-delete"]').trigger('click')
    await wrapper.get('[data-testid="group-delete-target"]').setValue(GROUP_MAIN)
    await wrapper.get('[data-testid="group-delete-submit"]').trigger('click')
    await flushPromises()

    expect(watchlistApi.deleteWatchlistGroup).toHaveBeenCalledWith(GROUP_FINANCE, 0, GROUP_MAIN)
  })

  it('删除空分组：不传目标分组（没有自选项要搬移）', async () => {
    const wrapper = await mountReady()

    await wrapper
      .findAll('[data-testid="group-actions-toggle"]')
      .find((button) => button.attributes('data-group-id') === GROUP_EMPTY)!
      .trigger('click')
    await wrapper.get('[data-testid="group-delete"]').trigger('click')

    // itemCount 为 0 时连目标选择器都不该出现——让用户选一个用不上的目标就是误导
    expect(wrapper.find('[data-testid="group-delete-target"]').exists()).toBe(false)

    await wrapper.get('[data-testid="group-delete-submit"]').trigger('click')
    await flushPromises()

    expect(watchlistApi.deleteWatchlistGroup).toHaveBeenCalledWith(GROUP_EMPTY, 0, undefined)
  })

  it('删除非空分组时未选目标分组则不发请求，只提示', async () => {
    const wrapper = await mountReady()

    await wrapper
      .findAll('[data-testid="group-actions-toggle"]')
      .find((button) => button.attributes('data-group-id') === GROUP_FINANCE)!
      .trigger('click')
    await wrapper.get('[data-testid="group-delete"]').trigger('click')
    await wrapper.get('[data-testid="group-delete-submit"]').trigger('click')
    await flushPromises()

    // 后端 TARGET_GROUP_REQUIRED 是 400；本地先拦住可以省掉一次必然失败的往返
    expect(watchlistApi.deleteWatchlistGroup).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="watchlist-action-error"]').text()).toContain('目标分组')
  })

  it('默认分组不可删除', async () => {
    const wrapper = await mountReady()

    await wrapper
      .findAll('[data-testid="group-actions-toggle"]')
      .find((button) => button.attributes('data-group-id') === GROUP_MAIN)!
      .trigger('click')

    expect(wrapper.get('[data-testid="group-delete"]').attributes('disabled')).toBeDefined()
  })

  it('写操作失败时展示后端文案与追踪编号，且页面数据保留', async () => {
    watchlistApi.removeWatchlistItem.mockRejectedValue(
      new ApiError('WATCHLIST_VERSION_CONFLICT', '自选项已变化，请刷新后重试', 409, 'trace-9'),
    )
    const wrapper = await mountReady()

    await cards(wrapper)[0]!.get('[data-action="remove"]').trigger('click')
    await flushPromises()

    const alert = wrapper.get('[data-testid="watchlist-action-error"]')
    expect(alert.text()).toContain('自选项已变化，请刷新后重试')
    expect(alert.text()).toContain('trace-9')
    // 一次写失败不该把整页数据清空
    expect(cards(wrapper)).toHaveLength(2)
  })

  it('快照不是实时且有卡片时，提示条说明这批数据的截止时间', async () => {
    watchlistApi.getWatchlistOverview.mockResolvedValue(overview({ dataStatus: 'STALE' }))
    const wrapper = await mountReady()

    const notice = wrapper.get('[data-testid="watchlist-data-status"]')
    expect(notice.text()).toContain('最近有效快照')
    expect(notice.text()).toContain('09/20 15:00')
  })

  it('自选为空时不显示数据时效提示条（根本没有快照，说了就是编造）', async () => {
    // 这是空自选账号的真实响应：后端不发起整批取数，于是批次为 null
    // → dataStatus=UNAVAILABLE、snapshotVersion=''、dataTime=null
    watchlistApi.getWatchlistOverview.mockResolvedValue(
      overview({
        groups: [group({ groupId: GROUP_MAIN, groupName: '默认分组', isDefault: true })],
        items: [],
        dataStatus: 'UNAVAILABLE',
        snapshotVersion: '',
        dataTime: null,
      }),
    )
    const wrapper = await mountReady()

    expect(wrapper.find('[data-testid="watchlist-data-status"]').exists()).toBe(false)
    // 空列表有它自己的说法，不该再挂一条"数据截止 --"
    expect(wrapper.get('[data-testid="watch-empty"]').text()).toContain('还没有自选标的')
  })

  it('首屏读失败时整页替换为错误页，并可重试', async () => {
    watchlistApi.getWatchlistOverview.mockRejectedValueOnce(
      new ApiError('NETWORK_ERROR', '暂时无法连接服务', 0),
    )
    const wrapper = await mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('暂时无法连接服务')

    watchlistApi.getWatchlistOverview.mockResolvedValue(overview())
    await wrapper.get('[data-testid="watchlist-retry"]').trigger('click')
    await flushPromises()

    expect(cards(wrapper)).toHaveLength(2)
  })

  it('卡片次要操作收拢为语义清晰的统一操作组', async () => {
    const wrapper = await mountReady()

    cards(wrapper).forEach((card) => {
      const actionGroup = card.get('[data-testid="watch-card-actions"]')
      const actions = actionGroup.findAll('button')

      expect(actionGroup.attributes('role')).toBe('group')
      expect(actionGroup.attributes('aria-label')).toBe('股票快捷操作')
      expect(actions).toHaveLength(2)
      expect(actionGroup.get('[data-action="ai"] span').text()).toBe('AI 解读')
      expect(actionGroup.get('[data-action="ai"] svg').attributes('width')).toBe('15')
      expect(actionGroup.get('[data-action="remove"]').attributes('aria-label')).toBe('移出自选')
      expect(actionGroup.get('[data-action="remove"] svg').attributes('width')).toBe('15')
    })
  })
})
