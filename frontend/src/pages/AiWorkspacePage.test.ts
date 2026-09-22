import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import AiWorkspacePage from './AiWorkspacePage.vue'
import { createTask, getReport, getScenes, getTask, previewContext } from '@/services/aiApi'
import { searchSecurities } from '@/services/securityApi'
import type {
  AiReportDetail,
  AiSceneDefinition,
  AiTaskAccepted,
  AiTaskSummary,
} from '@/types/domain'

vi.mock('@/services/aiApi', () => ({
  getScenes: vi.fn(),
  previewContext: vi.fn(),
  createTask: vi.fn(),
  getTask: vi.fn(),
  getReport: vi.fn(),
}))
vi.mock('@/services/securityApi', () => ({ searchSecurities: vi.fn() }))

const NOW = '2026-09-22T15:00:00+08:00'

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

function mountPage() {
  return mount(AiWorkspacePage, {
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
}

beforeEach(() => {
  vi.mocked(getScenes).mockResolvedValue([
    scene(),
    scene({ scene: 'COMPARE', name: '多标的对比', allowedTargetTypes: ['SECURITY'], minTargets: 2, maxTargets: 3 }),
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
    ],
  })
  vi.mocked(previewContext).mockResolvedValue({
    targets: [],
    canGenerate: true,
    dataCategories: [{ category: 'QUOTE', dataCutoffAt: NOW }],
    newsCount: 0,
    limitations: ['模拟证券600519 在分析区间内没有可用资讯，报告将为受限分析'],
  })
  const accepted: AiTaskAccepted = {
    task: task('QUEUED'),
    statusUrl: '/api/v1/ai/tasks/7001',
    streamUrl: '/api/v1/ai/tasks/7001/stream',
    quota: {
      dailyLimit: 20,
      usedCount: 1,
      remainingCount: 19,
      runningCount: 1,
      concurrentLimit: 2,
      resetsAt: '2026-09-23T00:00:00+08:00',
    },
  }
  vi.mocked(createTask).mockResolvedValue(accepted)
  vi.mocked(getTask).mockResolvedValue(task('COMPLETED', '8001'))
  vi.mocked(getReport).mockResolvedValue(report())
})

describe('AI 研究工作台', () => {
  it('场景清单来自 AI-01，而不是前端硬编码', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(getScenes).toHaveBeenCalledOnce()
    const options = wrapper.findAll('select option').map((option) => option.text())
    expect(options).toContain('个股研究')
    expect(options).toContain('多标的对比')
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

  it('提交后轮询到完成，渲染六章节、受限原因与免责声明', async () => {
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
    expect(getTask).toHaveBeenCalledWith('7001')
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

  it('多标的对比场景不给提交入口，并写明原因（而不是留一个点了没反应的按钮）', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.get('select').setValue('COMPARE')
    await flushPromises()

    expect(wrapper.text()).toContain('尚未接入标的检索')
    expect(wrapper.get('.question-composer button').attributes('disabled')).toBeDefined()
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

  it('来源引用区如实说明 HIS-07 未交付，不渲染一份写死的引用列表', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('HIS-07')
    // 原型里那些写死的 evidenceNo / sourceTitle 必须消失
    expect(wrapper.text()).not.toContain('浦发银行实时行情')
    expect(wrapper.text()).not.toContain('银行板块行情')
  })
})
