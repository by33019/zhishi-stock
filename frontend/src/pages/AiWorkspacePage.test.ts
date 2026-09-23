import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import AiWorkspacePage from './AiWorkspacePage.vue'
import type { StreamHandle } from '@/services/apiClient'
import {
  cancelTask,
  createFollowUpTask,
  createTask,
  getMyAiQuota,
  getReport,
  getReportEvidence,
  getScenes,
  getTask,
  previewContext,
  retryTask,
  streamTaskEvents,
} from '@/services/aiApi'
import { getSectorRankings } from '@/services/sectorApi'
import { searchSecurities } from '@/services/securityApi'
import type {
  AiReportDetail,
  AiReportEvidence,
  AiSceneDefinition,
  AiStreamEvent,
  AiTaskAccepted,
  AiTaskSummary,
} from '@/types/domain'

vi.mock('@/services/aiApi', () => ({
  getScenes: vi.fn(),
  previewContext: vi.fn(),
  createTask: vi.fn(),
  getTask: vi.fn(),
  getReport: vi.fn(),
  getReportEvidence: vi.fn(),
  getMyAiQuota: vi.fn(),
  cancelTask: vi.fn(),
  retryTask: vi.fn(),
  createFollowUpTask: vi.fn(),
  streamTaskEvents: vi.fn(),
}))
vi.mock('@/services/securityApi', () => ({ searchSecurities: vi.fn() }))
vi.mock('@/services/sectorApi', () => ({ getSectorRankings: vi.fn() }))

/**
 * streamTaskEvents 的桩：页面拿到的是 handle，测试拿到的是 handlers，
 * 于是可以按 SSE 的事件顺序手动驱动页面（snapshot → chunk → … → done）。
 */
function capturedStream() {
  let handlers: { onEvent: (event: AiStreamEvent) => void; onError: (error: Error) => void } | undefined
  const handle: StreamHandle = { close: vi.fn() }
  vi.mocked(streamTaskEvents).mockImplementation((_url, onEvent, onError) => {
    handlers = { onEvent, onError }
    return handle
  })
  return {
    emit: (event: AiStreamEvent) => handlers?.onEvent(event),
    fail: () => handlers?.onError(new Error('流不可用')),
    handle: () => handle,
  }
}

const NOW = '2026-09-22T15:00:00+08:00'

/** 每次挂载前重建的 SSE 桩；测试用它按事件顺序驱动页面。 */
let stream: ReturnType<typeof capturedStream>

function acceptedFor(taskId: string): AiTaskAccepted {
  return {
    task: { ...task('QUEUED'), taskId },
    statusUrl: `/api/v1/ai/tasks/${taskId}`,
    streamUrl: `/api/v1/ai/tasks/${taskId}/stream`,
    quota: {
      date: '2026-09-22',
      dailyLimit: 20,
      usedCount: 1,
      remainingCount: 19,
      runningCount: 1,
      concurrentLimit: 2,
      resetsAt: '2026-09-23T00:00:00+08:00',
    },
  }
}

function scene(overrides: Partial<AiSceneDefinition> = {}): AiSceneDefinition {
  return {
    scene: 'STOCK',
    name: '个股研究',
    description: '研究单只证券近期表现。',
    allowedTargetTypes: ['SECURITY'],
    minTargets: 1,
    maxTargets: 1,
    defaultRange: {
      presets: ['LAST_1_TRADING_DAY', 'LAST_5_TRADING_DAYS'],
      defaultPreset: 'LAST_5_TRADING_DAYS',
      maxCustomDays: 365,
    },
    questionMaxLength: 500,
    ...overrides,
  }
}

function task(status: string, reportId: string | null = null): AiTaskSummary {
  return {
    taskId: '7001',
    sessionId: '6001',
    scene: 'STOCK',
    status,
    targets: [],
    question: null,
    progressStage: '已完成',
    createdAt: NOW,
    firstChunkAt: NOW,
    completedAt: NOW,
    reportId,
    error: null,
  }
}

function report(): AiReportDetail {
  return {
    reportId: '8001',
    taskId: '7001',
    sessionId: '6001',
    coreConclusion: '该股短期偏强，但样本不足。',
    quoteEvidence: '最新价 13.96。',
    comparisonAnalysis: null,
    eventClues: null,
    riskAndUncertainty: '单点快照无法验证趋势。',
    disclaimer: '本内容不构成投资建议。',
    renderedMarkdown: '# 报告',
    qualityStatus: 'LIMITED',
    isLimited: true,
    limitedReason: '分析区间内没有可用资讯',
    marketDataCutoffAt: NOW,
    newsDataCutoffAt: null,
    contentSchemaVersion: 'v1',
    promptVersion: 'p2',
    providerCode: 'DASHSCOPE',
    modelCode: 'qwen3.8-max-0902',
    generatedAt: NOW,
    feedback: null,
  }
}

function evidence(overrides: Partial<AiReportEvidence> = {}): AiReportEvidence {
  return {
    evidenceNo: 1,
    evidenceType: 'QUOTE',
    sourceTitle: '模拟证券600519 行情快照',
    sourceUrl: null,
    evidenceSummary: '最新价 13.96，涨跌幅 +1.20%',
    sourcePublishedAt: NOW,
    dataTime: NOW,
    accessStatus: 'AVAILABLE',
    ...overrides,
  }
}

function mountPage() {
  return mount(AiWorkspacePage, {
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
}

beforeEach(() => {
  stream = capturedStream()
  vi.mocked(getScenes).mockResolvedValue([
    scene(),
    scene({ scene: 'COMPARE', name: '多标的对比', allowedTargetTypes: ['SECURITY'], minTargets: 2, maxTargets: 3 }),
    scene({ scene: 'SECTOR', name: '板块研究', allowedTargetTypes: ['SECTOR'], minTargets: 1, maxTargets: 1 }),
  ])
  vi.mocked(searchSecurities).mockResolvedValue({
    items: [
      {
        security: {
          securityId: 'sim-600519',
          fullSymbol: 'SH600519',
          securityCode: '600519',
          securityName: '模拟证券600519',
          exchangeCode: 'SH',
          securityType: 'STOCK',
          boardCode: 'MAIN',
          listingStatus: 'LISTED',
          isSt: false,
          isSuspended: false,
          priceScale: 2,
        },
        matchedField: 'CODE',
        highlight: '600519',
      },
      {
        security: {
          securityId: 'sim-000001',
          fullSymbol: 'SZ000001',
          securityCode: '000001',
          securityName: '模拟证券000001',
          exchangeCode: 'SZ',
          securityType: 'STOCK',
          boardCode: 'MAIN',
          listingStatus: 'LISTED',
          isSt: false,
          isSuspended: false,
          priceScale: 2,
        },
        matchedField: 'CODE',
        highlight: '000001',
      },
    ],
  })
  vi.mocked(getSectorRankings).mockResolvedValue({
    items: [
      {
        sectorId: 'bk-ai',
        sectorCode: 'BKAI',
        sectorName: '人工智能',
        sectorType: 'CONCEPT',
        companyCount: 50,
        averagePrice: '10.00',
        changeRate: '0.02',
        tradeVolume: '1000',
        tradeAmount: '2000',
        leadingStock: null,
        laggingStock: null,
        dataTime: NOW,
        dataStatus: 'REALTIME',
      },
    ],
    page: 1,
    size: 100,
    total: 1,
    totalPages: 1,
    hasNext: false,
    sectorType: null,
    rankingType: 'GAINERS',
    snapshotVersion: 'v1',
    dataTime: NOW,
    dataStatus: 'REALTIME',
  })
  vi.mocked(previewContext).mockResolvedValue({
    targets: [],
    canGenerate: true,
    dataCategories: [{ category: 'QUOTE', dataCutoffAt: NOW }],
    newsCount: 0,
    limitations: ['模拟证券600519 在分析区间内没有可用资讯，报告将为受限分析'],
  })
  vi.mocked(createTask).mockResolvedValue(acceptedFor('7001'))
  vi.mocked(getTask).mockResolvedValue(task('COMPLETED', '8001'))
  vi.mocked(getReport).mockResolvedValue(report())
  vi.mocked(getReportEvidence).mockResolvedValue([evidence()])
  vi.mocked(cancelTask).mockResolvedValue({
    taskId: '7001',
    status: 'CANCELING',
    cancelRequested: true,
    effectiveImmediately: true,
  })
  vi.mocked(retryTask).mockResolvedValue(acceptedFor('7002'))
  vi.mocked(createFollowUpTask).mockResolvedValue(acceptedFor('7003'))
  // USER-07：进页面就取一次配额。给一个与提交后**不同**的数，用来证明
  // 页面上显示的是 AI-03 覆盖后的值，而不是这个初始值。
  vi.mocked(getMyAiQuota).mockResolvedValue({
    date: '2026-09-22',
    dailyLimit: 20,
    usedCount: 0,
    remainingCount: 20,
    runningCount: 0,
    concurrentLimit: 2,
    resetsAt: '2026-09-23T00:00:00+08:00',
  })
})

/** 走一遍"检索标的 → 选标的 → 提交 → SSE 到完成"的最短路径。 */
async function runAnalysis(wrapper: ReturnType<typeof mountPage>) {
  await flushPromises()
  await wrapper.get('.inline-search input').setValue('600519')
  await wrapper.get('.inline-search input').trigger('keyup.enter')
  await flushPromises()
  await wrapper.get('.target-candidates button').trigger('click')
  await flushPromises()
  await wrapper.get('.question-composer button').trigger('click')
  await flushPromises()
  driveStreamToDone()
  await flushPromises()
}

/** 按真实 SSE 的事件顺序把任务推到完成：快照 → 状态 → 片段 → 报告 → 完成。 */
function driveStreamToDone(taskId = '7001') {
  stream.emit({
    kind: 'snapshot',
    task: { ...task('RUNNING'), taskId },
    lastSequence: 0,
    partialContent: '',
  })
  stream.emit({ kind: 'status', taskId, status: 'RUNNING', progressStage: '正在生成', sequence: 1 })
  stream.emit({ kind: 'chunk', taskId, section: 'coreConclusion', delta: '第一段。', sequence: 2 })
  stream.emit({
    kind: 'report',
    taskId,
    reportId: '8001',
    qualityStatus: 'LIMITED',
    isLimited: true,
    sequence: 3,
  })
  stream.emit({ kind: 'done', taskId, finalStatus: 'COMPLETED', sequence: 4 })
}

describe('AI 研究工作台', () => {
  it('场景清单来自 AI-01，而不是前端硬编码', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(getScenes).toHaveBeenCalledOnce()
    const options = wrapper.findAll('select option').map((option) => option.text())
    expect(options).toContain('个股研究')
    expect(options).toContain('多标的对比')
  })

  it('进页面就用 USER-07 读到配额：不点提交也知道还剩几次', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(getMyAiQuota).toHaveBeenCalledOnce()
    expect(wrapper.get('.quota-note').text()).toContain('今日剩余 20 / 20 次')
    expect(wrapper.get('.quota-note').text()).toContain('重置于')
  })

  it('配额读不到时如实说明，不编一个"还剩 20 次"', async () => {
    vi.mocked(getMyAiQuota).mockRejectedValueOnce({ message: '配额服务暂时不可用' })

    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('配额服务暂时不可用')
    expect(wrapper.find('.quota-note').exists()).toBe(false)
  })

  it('预览展示数据截止与数据缺口（缺口与报告的受限原因同源）', async () => {
    const wrapper = mountPage()
    await flushPromises()
    await wrapper.get('.inline-search input').setValue('600519')
    await wrapper.get('.inline-search input').trigger('keyup.enter')
    await flushPromises()
    await wrapper.get('.target-candidates button').trigger('click')
    await flushPromises()

    await wrapper.get('.research-setup .secondary-button').trigger('click')
    await flushPromises()

    expect(previewContext).toHaveBeenCalledWith({
      scene: 'STOCK',
      targets: [
        {
          targetType: 'SECURITY',
          targetId: 'sim-600519',
          targetCode: '600519',
          targetName: '模拟证券600519',
          targetRole: 'PRIMARY',
        },
      ],
    })
    expect(wrapper.text()).toContain('QUOTE · 数据截止')
    expect(wrapper.text()).toContain('没有可用资讯')
  })

  it('提交后经 SSE 收到临时文本与报告，渲染六章节、受限原因与免责声明', async () => {
    const wrapper = mountPage()
    await flushPromises()
    await wrapper.get('.inline-search input').setValue('600519')
    await wrapper.get('.inline-search input').trigger('keyup.enter')
    await flushPromises()
    await wrapper.get('.target-candidates button').trigger('click')
    await flushPromises()

    await wrapper.get('.question-composer button').trigger('click')
    await flushPromises()

    expect(createTask).toHaveBeenCalledOnce()
    // Idempotency-Key 由调用方生成：键的语义是"一次用户意图"
    expect(vi.mocked(createTask).mock.calls[0][1]).toMatch(/[0-9a-f-]{36}/)
    expect(streamTaskEvents).toHaveBeenCalledWith(
      '/api/v1/ai/tasks/7001/stream',
      expect.any(Function),
      expect.any(Function),
    )

    // 生成中：状态与临时文本实时到达，不去轮询
    stream.emit({
      kind: 'snapshot',
      task: task('RUNNING'),
      lastSequence: 0,
      partialContent: '',
    })
    stream.emit({ kind: 'status', taskId: '7001', status: 'RUNNING', progressStage: '正在生成', sequence: 1 })
    stream.emit({ kind: 'chunk', taskId: '7001', section: 'coreConclusion', delta: '第一段。', sequence: 2 })
    await flushPromises()
    expect(wrapper.get('.streaming-text').text()).toContain('第一段。')
    expect(getTask).not.toHaveBeenCalled()

    // 重连补发的重复片段按 sequence 去重，不会拼两遍
    stream.emit({ kind: 'chunk', taskId: '7001', section: 'coreConclusion', delta: '第一段。', sequence: 2 })
    await flushPromises()
    expect(wrapper.get('.streaming-text').text().match(/第一段。/g)).toHaveLength(1)

    stream.emit({
      kind: 'report',
      taskId: '7001',
      reportId: '8001',
      qualityStatus: 'LIMITED',
      isLimited: true,
      sequence: 3,
    })
    stream.emit({ kind: 'done', taskId: '7001', finalStatus: 'COMPLETED', sequence: 4 })
    await flushPromises()

    expect(getReport).toHaveBeenCalledWith('8001')
    expect(wrapper.text()).toContain('核心结论')
    expect(wrapper.text()).toContain('行情与量价依据')
    expect(wrapper.text()).toContain('风险与不确定性')
    expect(wrapper.text()).toContain('受限分析')
    expect(wrapper.text()).toContain('分析区间内没有可用资讯')
    expect(wrapper.text()).toContain('不构成投资建议')
    // 配额只在提交后才有权威来源
    expect(wrapper.text()).toContain('今日剩余 19 / 20 次')
  })

  it('SSE 不可用时退回每 3 秒轮询，闭环不断', async () => {
    // 第一次轮询仍在运行：此时提示文案必须可见；任务终态后它随运行视图一起退场。
    vi.mocked(getTask).mockResolvedValueOnce(task('RUNNING'))
    const wrapper = mountPage()
    await flushPromises()
    await wrapper.get('.inline-search input').setValue('600519')
    await wrapper.get('.inline-search input').trigger('keyup.enter')
    await flushPromises()
    await wrapper.get('.target-candidates button').trigger('click')
    await flushPromises()
    await wrapper.get('.question-composer button').trigger('click')
    await flushPromises()

    stream.fail()
    await flushPromises()

    // 退回轮询：AI-04 被调用，并如实告知用户
    expect(getTask).toHaveBeenCalledWith('7001')
    expect(wrapper.text()).toContain('实时流不可用')

    wrapper.unmount()
  })

  it('核心行情缺失时禁用提交，不让用户白跑一次', async () => {
    vi.mocked(previewContext).mockResolvedValue({
      targets: [],
      canGenerate: false,
      dataCategories: [],
      newsCount: 0,
      limitations: ['核心行情缺失'],
    })

    const wrapper = mountPage()
    await flushPromises()
    await wrapper.get('.inline-search input').setValue('600519')
    await wrapper.get('.inline-search input').trigger('keyup.enter')
    await flushPromises()
    await wrapper.get('.target-candidates button').trigger('click')
    await flushPromises()
    await wrapper.get('.research-setup .secondary-button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('核心行情缺失，无法生成报告')
    expect(wrapper.get('.question-composer button').attributes('disabled')).toBeDefined()
  })

  it('多标的对比场景：选满 2 只才给提交，提交体带上全部已选', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.get('select').setValue('COMPARE')
    await flushPromises()

    // 未达 minTargets：按钮禁用
    expect(wrapper.get('.question-composer button').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('对比标的（0 / 3）')

    // 第一只
    await wrapper.get('.inline-search input').setValue('600519')
    await wrapper.get('.inline-search input').trigger('keyup.enter')
    await flushPromises()
    await wrapper.get('.target-candidates button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('对比标的（1 / 3）')
    expect(wrapper.get('.question-composer button').attributes('disabled')).toBeDefined()

    // 第二只
    await wrapper.get('.inline-search input').setValue('000001')
    await wrapper.get('.inline-search input').trigger('keyup.enter')
    await flushPromises()
    await wrapper.get('.target-candidates li:nth-child(2) button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('对比标的（2 / 3）')

    await wrapper.get('.question-composer button').trigger('click')
    await flushPromises()

    expect(createTask).toHaveBeenCalledOnce()
    const [request] = vi.mocked(createTask).mock.calls[0]
    expect(request.scene).toBe('COMPARE')
    expect(request.targets.map((item) => item.targetId)).toEqual(['sim-600519', 'sim-000001'])
  })

  it('板块场景：候选来自 SEC-02 榜单，本地按名称过滤', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.get('select').setValue('SECTOR')
    await flushPromises()
    await wrapper.get('.inline-search input').setValue('人工')
    await wrapper.get('.inline-search input').trigger('keyup.enter')
    await flushPromises()

    expect(getSectorRankings).toHaveBeenCalledWith({ page: 1, size: 100 })
    await wrapper.get('.target-candidates button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已选：人工智能（BKAI）')

    await wrapper.get('.question-composer button').trigger('click')
    await flushPromises()
    const [request] = vi.mocked(createTask).mock.calls[0]
    expect(request.targets).toEqual([
      {
        targetType: 'SECTOR',
        targetId: 'bk-ai',
        targetCode: 'BKAI',
        targetName: '人工智能',
        targetRole: 'PRIMARY',
      },
    ])
  })

  // ---------- 取消 / 重试 / 追问（AI-06 / AI-07 / AI-08）----------

  it('取消进行中的任务（AI-06）：带上幂等键，生效与否如实告知', async () => {
    const wrapper = mountPage()
    await flushPromises()
    await wrapper.get('.inline-search input').setValue('600519')
    await wrapper.get('.inline-search input').trigger('keyup.enter')
    await flushPromises()
    await wrapper.get('.target-candidates button').trigger('click')
    await flushPromises()
    await wrapper.get('.question-composer button').trigger('click')
    await flushPromises()
    stream.emit({
      kind: 'snapshot',
      task: task('RUNNING'),
      lastSequence: 0,
      partialContent: '',
    })
    await flushPromises()

    await wrapper.get('.run-actions button').trigger('click')
    await flushPromises()

    expect(cancelTask).toHaveBeenCalledWith('7001', expect.stringMatching(/[0-9a-f-]{36}/))
    expect(wrapper.text()).toContain('已请求取消。')
    wrapper.unmount()
  })

  it('取消未生效（任务已终态）时说"未生效"，不说"已取消"', async () => {
    vi.mocked(cancelTask).mockResolvedValue({
      taskId: '7001',
      status: 'COMPLETED',
      cancelRequested: false,
      effectiveImmediately: false,
    })
    const wrapper = mountPage()
    await flushPromises()
    await wrapper.get('.inline-search input').setValue('600519')
    await wrapper.get('.inline-search input').trigger('keyup.enter')
    await flushPromises()
    await wrapper.get('.target-candidates button').trigger('click')
    await flushPromises()
    await wrapper.get('.question-composer button').trigger('click')
    await flushPromises()
    stream.emit({
      kind: 'snapshot',
      task: task('RUNNING'),
      lastSequence: 0,
      partialContent: '',
    })
    await flushPromises()

    await wrapper.get('.run-actions button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('任务已经完成，取消未生效。')
    wrapper.unmount()
  })

  it('失败任务可重试（AI-07）：创建新任务并接上它的事件流', async () => {
    vi.mocked(getTask).mockResolvedValue(task('FAILED'))
    const wrapper = mountPage()
    await flushPromises()
    await wrapper.get('.inline-search input').setValue('600519')
    await wrapper.get('.inline-search input').trigger('keyup.enter')
    await flushPromises()
    await wrapper.get('.target-candidates button').trigger('click')
    await flushPromises()
    await wrapper.get('.question-composer button').trigger('click')
    await flushPromises()
    stream.emit({ kind: 'done', taskId: '7001', finalStatus: 'FAILED', sequence: 1 })
    await flushPromises()

    expect(wrapper.text()).toContain('本次分析未产出报告')

    await wrapper.get('.run-actions button').trigger('click')
    await flushPromises()

    expect(retryTask).toHaveBeenCalledWith('7001', expect.stringMatching(/[0-9a-f-]{36}/), undefined)
    // 新任务接上了自己的流（第二次订阅，streamUrl 指向新任务）
    expect(streamTaskEvents).toHaveBeenLastCalledWith(
      '/api/v1/ai/tasks/7002/stream',
      expect.any(Function),
      expect.any(Function),
    )
    wrapper.unmount()
  })

  it('报告就位后可追问（AI-08）：在同一会话里创建新任务', async () => {
    const wrapper = mountPage()
    await runAnalysis(wrapper)

    await wrapper.get('.follow-up-composer textarea').setValue('把风险部分展开成检查清单')
    await wrapper.get('.follow-up-composer button').trigger('click')
    await flushPromises()

    expect(createFollowUpTask).toHaveBeenCalledWith(
      '6001',
      { question: '把风险部分展开成检查清单' },
      expect.stringMatching(/[0-9a-f-]{36}/),
    )
    // 追问产生的新任务同样接上事件流
    expect(streamTaskEvents).toHaveBeenLastCalledWith(
      '/api/v1/ai/tasks/7003/stream',
      expect.any(Function),
      expect.any(Function),
    )
    wrapper.unmount()
  })

  it('提交失败时显示后端文案，不显示编造的报告', async () => {
    vi.mocked(createTask).mockRejectedValue(
      Object.assign(new Error('每日额度已用完'), { code: 'AI_QUOTA_EXCEEDED', status: 429 }),
    )

    const wrapper = mountPage()
    await flushPromises()
    await wrapper.get('.inline-search input').setValue('600519')
    await wrapper.get('.inline-search input').trigger('keyup.enter')
    await flushPromises()
    await wrapper.get('.target-candidates button').trigger('click')
    await flushPromises()
    await wrapper.get('.question-composer button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('每日额度已用完')
    expect(wrapper.find('.ai-report').exists()).toBe(false)
  })

  // ---------- 来源引用（HIS-07）----------

  it('报告就位后自动取引用，编号用 evidenceNo（与正文的 [n] 对齐）而不是数组下标', async () => {
    // 故意让 evidenceNo 不从 1 连续：按下标渲染会得到 [1] [2]，看上去"也对"
    vi.mocked(getReportEvidence).mockResolvedValue([
      evidence({ evidenceNo: 2, evidenceType: 'NEWS', sourceTitle: '某公司公告' }),
      evidence({ evidenceNo: 5, evidenceType: 'SECTOR', sourceTitle: '板块行情' }),
    ])

    const wrapper = mountPage()
    await runAnalysis(wrapper)

    expect(getReportEvidence).toHaveBeenCalledWith('8001')
    expect(wrapper.findAll('.evidence-no').map((node) => node.text())).toEqual(['[2]', '[5]'])
    // 类型码翻成中文，而不是把 QUOTE / NEWS 直接丢给用户
    expect(wrapper.text()).toContain('资讯')
    expect(wrapper.text()).toContain('板块')
  })

  it('没有 sourceUrl 就不给链接：授权受限与协议不合规都由服务端判过，前端不猜', async () => {
    vi.mocked(getReportEvidence).mockResolvedValue([
      evidence({ evidenceNo: 1, accessStatus: 'RESTRICTED', sourceUrl: null }),
    ])

    const wrapper = mountPage()
    await runAnalysis(wrapper)

    expect(wrapper.find('.evidence-link').exists()).toBe(false)
    expect(wrapper.text()).toContain('授权受限，仅摘要')
    // 摘要必须还在——受限不等于没有证据
    expect(wrapper.text()).toContain('最新价 13.96')
  })

  it('有原文地址时给外链，并带上 noopener noreferrer（契约 §24）', async () => {
    vi.mocked(getReportEvidence).mockResolvedValue([
      evidence({ evidenceNo: 1, sourceUrl: 'https://news.example.com/a' }),
    ])

    const wrapper = mountPage()
    await runAnalysis(wrapper)

    const link = wrapper.get('.evidence-link')
    expect(link.attributes('href')).toBe('https://news.example.com/a')
    expect(link.attributes('rel')).toBe('noopener noreferrer')
    expect(link.attributes('target')).toBe('_blank')
  })

  it('引用加载失败：显示错误并给重试，不把"读不到"表现成"没有引用"', async () => {
    vi.mocked(getReportEvidence).mockRejectedValueOnce(
      Object.assign(new Error('服务暂时不可用'), { traceId: 'trace-9' }),
    )

    const wrapper = mountPage()
    await runAnalysis(wrapper)

    expect(wrapper.text()).toContain('服务暂时不可用')
    expect(wrapper.text()).not.toContain('这份报告没有引用任何来源')

    vi.mocked(getReportEvidence).mockResolvedValue([evidence({ evidenceNo: 1 })])
    await wrapper.get('.evidence-drawer .link-button').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.evidence-no').map((node) => node.text())).toEqual(['[1]'])
  })

  it('报告确实没有引用：明确说明，而不是留一片空白', async () => {
    vi.mocked(getReportEvidence).mockResolvedValue([])

    const wrapper = mountPage()
    await runAnalysis(wrapper)

    expect(wrapper.text()).toContain('这份报告没有引用任何来源')
    expect(wrapper.find('.evidence-link').exists()).toBe(false)
  })

  it('还没提交时引用栏说明"报告生成后才会有"，不去请求接口', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(getReportEvidence).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('报告生成后，这里会列出它引用的来源')
  })
})
