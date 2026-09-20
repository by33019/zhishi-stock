import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

// 类型 NewsPage 与页面组件同名，这里必须起别名——否则是「标识符重复声明」而不是测试失败。
import type { NewsOptions, NewsPage as NewsFeed, NewsQuery, NewsSummary } from '@/types/domain'

const newsApi = vi.hoisted(() => ({ getNews: vi.fn(), getNewsOptions: vi.fn() }))
vi.mock('@/services/newsApi', () => newsApi)

import NewsPage from './NewsPage.vue'

function article(overrides: Partial<NewsSummary> = {}): NewsSummary {
  return {
    newsId: '7331469565956100',
    newsType: 'NEWS',
    title: '半导体设备订单能见度提升',
    summary: '成熟制程与先进封装方向景气度不同，需结合订单兑现节奏判断。',
    sourceName: '证券时报',
    authorName: null,
    publishedAt: '2026-09-18T11:00:00+08:00',
    collectedAt: '2026-09-18T11:05:00+08:00',
    originalUrl: 'https://example.com/news/7331469565956100',
    originalAccessStatus: 'AVAILABLE',
    relations: [],
    ...overrides,
  }
}

function feed(overrides: Partial<NewsFeed> = {}): NewsFeed {
  return {
    items: [article()],
    page: 1,
    size: 20,
    total: 45,
    totalPages: 3,
    hasNext: true,
    lastSuccessfulSyncAt: '2026-09-18T15:00:00+08:00',
    dataStatus: 'REALTIME',
    ...overrides,
  }
}

const OPTIONS: NewsOptions = {
  newsTypes: ['NEWS', 'ANNOUNCEMENT', 'RESEARCH', 'OTHER'],
  sourceTypes: ['MEDIA', 'EXCHANGE', 'COMPANY', 'REGULATOR'],
  availableTimeRange: { startAt: null, endAt: null },
  filterRules: '默认仅返回主记录、已发布内容与已确认关联。',
}

function mountPage() {
  return mount(NewsPage, {
    global: {
      stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } },
    },
  })
}

async function mountReady() {
  const wrapper = mountPage()
  await flushPromises()
  return wrapper
}

function lastNewsQuery(): NewsQuery {
  const calls = newsApi.getNews.mock.calls
  return calls[calls.length - 1]![0] as NewsQuery
}

function typeTabs(wrapper: ReturnType<typeof mountPage>) {
  return wrapper.findAll('[data-testid="news-type-tab"]')
}

describe('资讯中心页', () => {
  beforeEach(() => {
    newsApi.getNews.mockReset()
    newsApi.getNewsOptions.mockReset()
    newsApi.getNewsOptions.mockResolvedValue(OPTIONS)
    newsApi.getNews.mockResolvedValue(feed())
  })

  it('首屏只发一次列表与一次选项', async () => {
    await mountReady()
    expect(newsApi.getNews).toHaveBeenCalledTimes(1)
    expect(newsApi.getNewsOptions).toHaveBeenCalledTimes(1)
  })

  it('首屏不传 newsTypes（不限类型 = 不传，而不是传空串）', async () => {
    await mountReady()
    expect(lastNewsQuery().newsTypes).toBeUndefined()
    expect(lastNewsQuery().page).toBe(1)
  })

  it('类型标签来自服务端选项，未知取值回退为原值', async () => {
    newsApi.getNewsOptions.mockResolvedValue({ ...OPTIONS, newsTypes: ['NEWS', 'OTHER', 'FORECAST'] })
    const wrapper = await mountReady()
    expect(typeTabs(wrapper).map((tab) => tab.text())).toEqual([
      '全部', '快讯', '其他', 'FORECAST',
    ])
  })

  it('切换类型只重发列表（带 newsTypes），不重发选项', async () => {
    const wrapper = await mountReady()
    await typeTabs(wrapper)[2]!.trigger('click')
    await flushPromises()

    expect(lastNewsQuery().newsTypes).toBe('ANNOUNCEMENT')
    expect(newsApi.getNews).toHaveBeenCalledTimes(2)
    expect(newsApi.getNewsOptions).toHaveBeenCalledTimes(1)
  })

  it('切换类型把页码归 1', async () => {
    const wrapper = await mountReady()
    await wrapper.find('[data-testid="news-next"]').trigger('click')
    await flushPromises()
    expect(lastNewsQuery().page).toBe(2)

    await typeTabs(wrapper)[2]!.trigger('click')
    await flushPromises()
    expect(lastNewsQuery().page).toBe(1)
  })

  it('翻页保留当前筛选条件', async () => {
    const wrapper = await mountReady()
    await typeTabs(wrapper)[2]!.trigger('click')
    await flushPromises()

    await wrapper.find('[data-testid="news-next"]').trigger('click')
    await flushPromises()

    expect(lastNewsQuery()).toMatchObject({ newsTypes: 'ANNOUNCEMENT', page: 2 })
  })

  it('关键字是显式提交：输入不发请求，回车才发', async () => {
    const wrapper = await mountReady()
    const input = wrapper.find('[data-testid="news-keyword"]')

    await input.setValue('半导体')
    await flushPromises()
    expect(newsApi.getNews).toHaveBeenCalledTimes(1)

    await input.trigger('keyup.enter')
    await flushPromises()
    expect(lastNewsQuery().keyword).toBe('半导体')
    expect(newsApi.getNews).toHaveBeenCalledTimes(2)
  })

  it('原文可访问时渲染真实外链，不是占位符', async () => {
    const wrapper = await mountReady()
    const link = wrapper.find('[data-testid="news-original"]')

    expect(link.attributes('href')).toBe('https://example.com/news/7331469565956100')
    expect(link.attributes('rel')).toContain('noopener')
    expect(link.attributes('target')).toBe('_blank')
  })

  it('原文不可访问时不渲染链接，也不留 href="#" 占位', async () => {
    newsApi.getNews.mockResolvedValue(feed({
      items: [article({ originalAccessStatus: 'UNAVAILABLE' })],
    }))
    const wrapper = await mountReady()

    expect(wrapper.find('[data-testid="news-original"]').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('href="#"')
    expect(wrapper.text()).toContain('原文不可用')
  })

  it('原文状态未知时仍给出已校验的地址，只额外标注状态未知', async () => {
    newsApi.getNews.mockResolvedValue(feed({
      items: [article({ originalAccessStatus: 'UNKNOWN' })],
    }))
    const wrapper = await mountReady()

    // 契约 §4.3：originalUrl 是「经协议和安全校验的原文地址」，originalAccessStatus
    // 描述的是**内容**可访问性。模拟源目前一律产出 UNKNOWN，若只在 AVAILABLE 时给链接，
    // 真实环境里「查看原文」会全部消失——这是 e2e 才发现的（见 spec §8）。
    expect(wrapper.find('[data-testid="news-original"]').attributes('href'))
      .toBe('https://example.com/news/7331469565956100')
    expect(wrapper.text()).toContain('原文状态未知')
    expect(wrapper.text()).not.toContain('原文不可用')
  })

  it('确认关联渲染为可跳转标签，市场关联不跳转', async () => {
    newsApi.getNews.mockResolvedValue(feed({
      items: [article({
        relations: [
          {
            targetType: 'SECURITY',
            targetId: 'sim-600519',
            targetCode: '600519',
            targetName: '贵州茅台',
            relationMethod: 'EXPLICIT',
            confidenceScore: 1,
          },
          {
            targetType: 'MARKET',
            targetId: 'CN',
            targetCode: 'CN',
            targetName: 'A 股市场',
            relationMethod: 'RULE',
            confidenceScore: 0.9,
          },
        ],
      })],
    }))
    const wrapper = await mountReady()
    const tags = wrapper.findAll('[data-testid="news-relation"]')

    expect(tags).toHaveLength(2)
    expect(tags[0]!.find('a').attributes('href')).toBe('/stocks/sim-600519')
    expect(tags[1]!.find('a').exists()).toBe(false)
  })

  it('关联目标标识为空时不渲染链接（拼不出路由）', async () => {
    newsApi.getNews.mockResolvedValue(feed({
      items: [article({
        relations: [{
          targetType: 'SECTOR',
          targetId: '',
          targetCode: 'BK0025',
          targetName: '半导体',
          relationMethod: 'RULE',
          confidenceScore: 0.8,
        }],
      })],
    }))
    const wrapper = await mountReady()

    expect(wrapper.find('[data-testid="news-relation"]').find('a').exists()).toBe(false)
  })

  it('空列表且资讯源不可用时，说明的是取不到数而不是没有数据', async () => {
    newsApi.getNews.mockResolvedValue(feed({
      items: [], total: 0, totalPages: 0, hasNext: false, dataStatus: 'UNAVAILABLE',
    }))
    const wrapper = await mountReady()

    expect(wrapper.find('[data-testid="news-empty"]').text()).toContain('资讯源暂不可用')
  })

  it('空列表但资讯源正常时，说明的是筛选结果为空', async () => {
    newsApi.getNews.mockResolvedValue(feed({
      items: [], total: 0, totalPages: 0, hasNext: false,
    }))
    const wrapper = await mountReady()

    expect(wrapper.find('[data-testid="news-empty"]').text()).toContain('暂无符合条件')
  })

  it('侧栏不渲染原型里的编造数字', async () => {
    const wrapper = await mountReady()
    const sidebar = wrapper.find('.news-sidebar').text()

    expect(sidebar).not.toContain('286')
    expect(sidebar).not.toContain('42')
    expect(sidebar).not.toContain('18%')
    expect(sidebar).toContain('尚未实现')
  })

  it('数据截止时间来自 lastSuccessfulSyncAt，并钉在 Asia/Shanghai', async () => {
    const wrapper = await mountReady()
    expect(wrapper.text()).toContain('数据截止 09/18 15:00')
  })

  it('加载失败时展示错误信息与追踪编号', async () => {
    newsApi.getNews.mockRejectedValue(
      Object.assign(new Error('资讯暂时无法加载'), { traceId: 'trace-news-1' }),
    )
    const wrapper = await mountReady()

    expect(wrapper.text()).toContain('资讯暂时无法加载')
    expect(wrapper.text()).toContain('trace-news-1')
  })

  it('时间筛选按钮置灰并写明归属', async () => {
    const wrapper = await mountReady()
    const button = wrapper.find('[data-testid="news-time-filter"]')

    expect(button.attributes('disabled')).toBeDefined()
    expect(button.attributes('title')).toContain('时间筛选')
  })
})
