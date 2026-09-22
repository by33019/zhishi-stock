import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import HistoryPage from './HistoryPage.vue'
import {
  deleteSession,
  getSession,
  getSessionMessages,
  getSessions,
  updateSession,
} from '@/services/historyApi'
import type { AiSessionDetail, AiSessionSummary, PageData } from '@/types/domain'

vi.mock('@/services/historyApi', () => ({
  getSessions: vi.fn(),
  getSession: vi.fn(),
  getSessionMessages: vi.fn(),
  updateSession: vi.fn(),
  deleteSession: vi.fn(),
}))

const NOW = '2026-09-22T15:00:00+08:00'

function pageOf<T>(items: T[], extra: Partial<PageData<T>> = {}): PageData<T> {
  return { items, page: 1, size: 20, total: items.length, totalPages: 1, hasNext: false, ...extra }
}

function session(overrides: Partial<AiSessionSummary> = {}): AiSessionSummary {
  return {
    sessionId: '6001',
    scene: 'STOCK',
    title: '请说明这只股票近期的量价特征',
    status: 'ACTIVE',
    isFavorite: false,
    lastTask: { taskId: '7001', status: 'COMPLETED' },
    lastActivityAt: NOW,
    createdAt: NOW,
    version: 1,
    ...overrides,
  }
}

function detail(overrides: Partial<AiSessionDetail> = {}): AiSessionDetail {
  return {
    sessionId: '6001',
    scene: 'STOCK',
    title: '请说明这只股票近期的量价特征',
    status: 'ACTIVE',
    isFavorite: false,
    targets: [
      {
        targetType: 'SECURITY',
        targetId: 'sim-600519',
        targetCode: '600519',
        targetName: '模拟证券600519',
        targetRole: 'PRIMARY',
      },
    ],
    lastTask: null,
    lastReport: null,
    lastActivityAt: NOW,
    createdAt: NOW,
    version: 1,
    ...overrides,
  }
}

function mountPage() {
  return mount(HistoryPage, {
    global: {
      // 桩把 `to` 渲染成属性：断言"跳转目标是可解析的对外标识"只能靠它——
      // 界面上显示的是名称与代码，内部 ID 只出现在链接里。
      stubs: { RouterLink: { template: '<a :data-to="to"><slot /></a>', props: ['to'] } },
    },
  })
}

beforeEach(() => {
  vi.mocked(getSessions).mockResolvedValue(pageOf([session()]))
  vi.mocked(getSession).mockResolvedValue(detail())
  vi.mocked(getSessionMessages).mockResolvedValue(pageOf([]))
})

describe('分析历史页', () => {
  it('首屏只按默认条件请求一次列表，不做逐条详情请求', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(getSessions).toHaveBeenCalledTimes(1)
    expect(vi.mocked(getSessions).mock.calls[0][0]).toMatchObject({ page: 1, size: 20 })
    // 首屏不拉详情：进页面就对每条记录发一次请求就是 N+1
    expect(getSession).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请说明这只股票近期的量价特征')
    expect(wrapper.text()).toContain('个股研究')
    expect(wrapper.text()).toContain('COMPLETED')
  })

  it('切换研究类型带 scene 重新请求，并把页码重置为 1', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const marketButton = wrapper
      .findAll('.history-filter button')
      .find((button) => button.text() === '市场解读')
    await marketButton?.trigger('click')
    await flushPromises()

    expect(getSessions).toHaveBeenCalledTimes(2)
    expect(vi.mocked(getSessions).mock.calls[1][0]).toMatchObject({ scene: 'MARKET', page: 1 })
  })

  it('关键字显式提交才生效（回车确认），不做输入即搜', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const input = wrapper.get('input[type="search"]')
    await input.setValue('茅台')
    // 只输入、未回车：不应触发新请求
    expect(getSessions).toHaveBeenCalledTimes(1)

    await input.trigger('keyup.enter')
    await flushPromises()
    expect(getSessions).toHaveBeenCalledTimes(2)
    expect(vi.mocked(getSessions).mock.calls[1][0]).toMatchObject({ keyword: '茅台', page: 1 })
  })

  it('没有记录时明说"还没有分析记录"，而不是渲染成出错', async () => {
    vi.mocked(getSessions).mockResolvedValue(pageOf([]))
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('还没有分析记录')
  })

  it('从未跑过任务的会话显示"尚未分析"，不编造任务状态', async () => {
    vi.mocked(getSessions).mockResolvedValue(pageOf([session({ lastTask: null })]))
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('尚未分析')
    expect(wrapper.text()).toContain('还没有发起过分析')
  })

  it('点击一条记录才请求详情与消息，并渲染正文与数据截止时间', async () => {
    vi.mocked(getSession).mockResolvedValue(
      detail({
        lastTask: {
          taskId: '7001',
          sessionId: '6001',
          scene: 'STOCK',
          status: 'COMPLETED',
          targets: [],
          question: '怎么看？',
          progressStage: '已完成',
          createdAt: NOW,
          firstChunkAt: NOW,
          completedAt: NOW,
          reportId: '8001',
          error: null,
        },
        lastReport: { reportId: '8001', qualityStatus: 'LIMITED', isLimited: true, generatedAt: NOW },
      }),
    )
    vi.mocked(getSessionMessages).mockResolvedValue(
      pageOf([
        {
          messageId: '5002',
          taskId: '7001',
          roleType: 'ASSISTANT',
          sequenceNo: 2,
          content: '## 核心结论\n\n模拟证券600519 小幅上涨。',
          dataCutoffAt: NOW,
          createdAt: NOW,
        },
      ]),
    )

    const wrapper = mountPage()
    await flushPromises()
    await wrapper.get('.report-info h2 button').trigger('click')
    await flushPromises()

    expect(getSession).toHaveBeenCalledWith('6001')
    expect(getSessionMessages).toHaveBeenCalledWith('6001', { page: 1, size: 50 })
    expect(wrapper.text()).toContain('模拟证券600519（600519）')
    // 跳转目标必须是可被个股接口解析的对外标识；用代理键会 404。
    // 用 find 而不是 get：get 找不到就抛，拿不到 exists() 这个判断。
    expect(wrapper.find('a[data-to="/stocks/sim-600519"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('报告 8001')
    // 受限报告必须说出来，不能只给一个质量码
    expect(wrapper.text()).toContain('受限')
    expect(wrapper.text()).toContain('核心结论')
    expect(wrapper.text()).toContain('数据截止')
  })

  it('列表失败时给出后端文案与追踪号，并可重试', async () => {
    vi.mocked(getSessions).mockRejectedValueOnce({
      message: '服务暂时不可用',
      traceId: 'trace-1',
    })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('服务暂时不可用')
    expect(wrapper.text()).toContain('trace-1')

    vi.mocked(getSessions).mockResolvedValue(pageOf([session()]))
    await wrapper.get('.state-note--error .link-button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('请说明这只股票近期的量价特征')
  })

  // ---------- HIS-03 / HIS-04 ----------

  async function openDetail() {
    const wrapper = mountPage()
    await flushPromises()
    await wrapper.get('.report-info h2 button').trigger('click')
    await flushPromises()
    return wrapper
  }

  it('收藏用当前渲染的那一版提交（If-Match 必须是这一版，不是最新版）', async () => {
    vi.mocked(getSession).mockResolvedValue(detail({ version: 7, isFavorite: false }))
    vi.mocked(updateSession).mockResolvedValue({
      sessionId: '6001',
      title: '请说明这只股票近期的量价特征',
      isFavorite: true,
      version: 8,
    })

    const wrapper = await openDetail()
    const favorite = wrapper
      .findAll('.history-actions button')
      .find((button) => button.text() === '收藏')
    await favorite?.trigger('click')
    await flushPromises()

    expect(updateSession).toHaveBeenCalledWith('6001', 7, { isFavorite: true })
    expect(wrapper.text()).toContain('已加入收藏')
    // 写成功后必须重新拉取：服务端会改写 version，本地那份已经过期
    expect(vi.mocked(getSessions).mock.calls.length).toBeGreaterThan(1)
  })

  it('重命名成功后提示并刷新；空标题被前端拦下，不发请求', async () => {
    vi.mocked(getSession).mockResolvedValue(detail({ version: 3 }))
    vi.mocked(updateSession).mockResolvedValue({
      sessionId: '6001',
      title: '新标题',
      isFavorite: false,
      version: 4,
    })

    const wrapper = await openDetail()
    await wrapper
      .findAll('.history-actions button')
      .find((button) => button.text() === '重命名')
      ?.trigger('click')

    const input = wrapper.get('.history-rename input')
    await input.setValue('   ')
    await wrapper.get('.history-rename').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('标题不能为空')
    expect(updateSession).not.toHaveBeenCalled()

    await input.setValue('  新标题  ')
    await wrapper.get('.history-rename').trigger('submit')
    await flushPromises()
    // 前后空白由服务端裁剪，前端原样提交即可
    expect(updateSession).toHaveBeenCalledWith('6001', 3, { title: '新标题' })
    expect(wrapper.text()).toContain('标题已更新')
  })

  it('版本冲突（409）不是「操作非法」：提示已刷新并重新拉取，请用户再试', async () => {
    vi.mocked(getSession).mockResolvedValue(detail({ version: 2, isFavorite: false }))
    vi.mocked(updateSession).mockRejectedValue(
      Object.assign(new Error('会话已被修改'), { status: 409 }),
    )

    const wrapper = await openDetail()
    const before = vi.mocked(getSessions).mock.calls.length
    await wrapper
      .findAll('.history-actions button')
      .find((button) => button.text() === '收藏')
      ?.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('刚被修改过，已为你刷新')
    expect(vi.mocked(getSessions).mock.calls.length).toBeGreaterThan(before)
  })

  it('删除要两步确认，成功后清空详情并告知彻底清理时间', async () => {
    vi.mocked(getSession).mockResolvedValue(detail({ version: 1 }))
    vi.mocked(deleteSession).mockResolvedValue({
      deleted: true,
      purgeAfter: '2026-10-22T15:00:00+08:00',
    })

    const wrapper = await openDetail()
    const del = wrapper
      .findAll('.history-actions button')
      .find((button) => button.text() === '删除')
    await del?.trigger('click')
    await flushPromises()

    // 第一次点击只是展开确认区，不能已经发出删除请求
    expect(deleteSession).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('确定删除')

    await wrapper.get('.history-confirm button').trigger('click')
    await flushPromises()

    expect(deleteSession).toHaveBeenCalledWith('6001', 1)
    // 格式跟随 formatDate（MM/DD），与列表里的时间列一致
    expect(wrapper.text()).toContain('10/22 之后彻底清理')
    // 详情应当收起来：那个会话已经不在列表里了
    expect(wrapper.find('.history-detail').exists()).toBe(false)
  })
})
