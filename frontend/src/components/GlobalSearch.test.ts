import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { SecuritySearchResult, SecuritySummary } from '@/types/domain'

const securityApi = vi.hoisted(() => ({ searchSecurities: vi.fn() }))
const navigation = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('@/services/securityApi', () => securityApi)
vi.mock('vue-router', () => ({ useRouter: () => navigation }))

import GlobalSearch from './GlobalSearch.vue'

const DEBOUNCE_MS = 300

function summary(overrides: Partial<SecuritySummary> = {}): SecuritySummary {
  return {
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
    ...overrides,
  }
}

function result(items: SecuritySearchResult['items']): SecuritySearchResult {
  return { items }
}

const threeMatches: SecuritySearchResult['items'] = [
  { security: summary(), matchedField: 'CODE', highlight: '600000' },
  {
    security: summary({ securityId: 'sim-600001', securityCode: '600001', securityName: '邯郸钢铁' }),
    matchedField: 'CODE',
    highlight: '600001',
  },
  {
    security: summary({ securityId: 'sim-600002', securityCode: '600002', securityName: '齐鲁石化', isSuspended: true }),
    matchedField: 'CODE',
    highlight: '600002',
  },
]

function mountSearch() {
  return mount(GlobalSearch, { attachTo: document.body })
}

async function type(wrapper: ReturnType<typeof mountSearch>, value: string) {
  const input = wrapper.get('input[aria-label="全局证券搜索"]')
  // 真实用户必须先聚焦才能打字，面板也正是在聚焦时展开的
  await input.trigger('focus')
  await input.setValue(value)
  await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
  await flushPromises()
  return input
}

describe('全局证券搜索', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
    securityApi.searchSecurities.mockResolvedValue(result(threeMatches))
  })

  afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  it('输入后等满防抖窗口才发请求，且 q 用裁剪后的值', async () => {
    const wrapper = mountSearch()
    const input = wrapper.get('input[aria-label="全局证券搜索"]')

    await input.setValue('  600000  ')
    expect(securityApi.searchSecurities).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    await flushPromises()

    // 契约要求 q 裁剪后 1–50 字符；带空格的原文会被后端判成 400
    expect(securityApi.searchSecurities).toHaveBeenCalledWith('600000', 10)
  })

  it('连续输入只在最后一次之后发一次请求', async () => {
    const wrapper = mountSearch()
    const input = wrapper.get('input[aria-label="全局证券搜索"]')

    await input.setValue('6')
    await vi.advanceTimersByTimeAsync(100)
    await input.setValue('60')
    await vi.advanceTimersByTimeAsync(100)
    await input.setValue('600')
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    await flushPromises()

    expect(securityApi.searchSecurities).toHaveBeenCalledTimes(1)
    expect(securityApi.searchSecurities).toHaveBeenCalledWith('600', 10)
  })

  it('空输入与纯空白都不发请求', async () => {
    const wrapper = mountSearch()
    const input = wrapper.get('input[aria-label="全局证券搜索"]')

    await input.setValue('')
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    await input.setValue('   ')
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    await flushPromises()

    // 发出去必然换回一条 400，并在服务端日志里留下噪音
    expect(securityApi.searchSecurities).not.toHaveBeenCalled()
  })

  it('渲染名称、代码、交易所，并标记停牌状态', async () => {
    const wrapper = mountSearch()
    await type(wrapper, '600')

    const options = wrapper.findAll('[role="option"]')
    expect(options).toHaveLength(3)
    expect(options[0]!.text()).toContain('浦发银行')
    expect(options[0]!.text()).toContain('SH · 600000')
    expect(options[2]!.text()).toContain('停牌')
    // 正常交易的证券不该被标成异常
    expect(options[0]!.text()).not.toContain('停牌')
  })

  it('把 highlight 原文子串标成 mark，而不是重新拼一个字符串', async () => {
    securityApi.searchSecurities.mockResolvedValue(result([
      { security: summary({ securityName: '浦发银行' }), matchedField: 'NAME', highlight: '浦发' },
    ]))
    const wrapper = mountSearch()
    await type(wrapper, '浦发')

    const option = wrapper.get('[role="option"]')
    expect(option.get('mark').text()).toBe('浦发')
    // 高亮只包住命中片段，其余原文保持原样
    expect(option.text()).toContain('浦发银行')
  })

  it('点击建议跳转到响应里的 securityId，而不是写死的 ID', async () => {
    const wrapper = mountSearch()
    await type(wrapper, '600')

    await wrapper.findAll('[role="option"]')[1]!.trigger('click')

    // 原型写死 /stocks/19876543210001，该 ID 在主数据里不存在，点开就是 404
    expect(navigation.push).toHaveBeenCalledWith('/stocks/sim-600001')
  })

  it('Enter 直接选中第一条', async () => {
    const wrapper = mountSearch()
    const input = await type(wrapper, '600')

    await input.trigger('keydown', { key: 'Enter' })

    expect(navigation.push).toHaveBeenCalledWith('/stocks/sim-600000')
  })

  it('↓ 两次后 Enter 选中第二条（第一次 ↓ 落在第一条）', async () => {
    const wrapper = mountSearch()
    const input = await type(wrapper, '600')

    await input.trigger('keydown', { key: 'ArrowDown' })
    expect(wrapper.findAll('[role="option"]')[0]!.classes()).toContain('is-active')

    await input.trigger('keydown', { key: 'ArrowDown' })
    expect(wrapper.findAll('[role="option"]')[1]!.classes()).toContain('is-active')
    // 焦点始终在输入框上，因此要靠 aria-activedescendant 告诉读屏软件当前高亮哪一项
    expect(input.attributes('aria-activedescendant')).toBe('global-search-option-1')

    await input.trigger('keydown', { key: 'Enter' })

    expect(navigation.push).toHaveBeenCalledWith('/stocks/sim-600001')
  })

  it('Esc 收起面板但保留关键词', async () => {
    const wrapper = mountSearch()
    const input = await type(wrapper, '600')

    await input.trigger('keydown', { key: 'Escape' })

    expect(wrapper.find('[role="listbox"]').exists()).toBe(false)
    // 用户按 Esc 通常是想收起面板再看一眼输入，清空会让他重新打字
    expect((input.element as HTMLInputElement).value).toBe('600')
  })

  it('无结果时给出空状态而不是空白面板', async () => {
    securityApi.searchSecurities.mockResolvedValue(result([]))
    const wrapper = mountSearch()
    await type(wrapper, 'zzz')

    expect(wrapper.get('[role="listbox"]').text()).toContain('没有匹配「zzz」的证券')
  })

  it('失败时保留关键词、展示追踪编号，并可重试', async () => {
    securityApi.searchSecurities.mockRejectedValue({
      code: 'INVALID_REQUEST',
      message: 'q 长度必须为 1 至 50 个字符',
      traceId: 'trace-400',
    })
    const wrapper = mountSearch()
    const input = await type(wrapper, '600')

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('q 长度必须为 1 至 50 个字符')
    expect(alert.text()).toContain('trace-400')
    // 关键词必须还在，否则用户得重新输入才能重试
    expect((input.element as HTMLInputElement).value).toBe('600')

    securityApi.searchSecurities.mockResolvedValue(result(threeMatches))
    await wrapper.get('[data-testid="search-retry"]').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('[role="option"]')).toHaveLength(3)
  })

  it('⌘K / Ctrl+K 聚焦输入框', async () => {
    const wrapper = mountSearch()
    const input = wrapper.get('input[aria-label="全局证券搜索"]')
    expect(document.activeElement).not.toBe(input.element)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, bubbles: true }))

    expect(document.activeElement).toBe(input.element)
  })

  it('点击组件外部关闭面板', async () => {
    const wrapper = mountSearch()
    await type(wrapper, '600')
    expect(wrapper.find('[role="listbox"]').exists()).toBe(true)

    document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    // 直接派发 DOM 事件不会像 VTU 的 trigger 那样等待 nextTick，必须自己等一次渲染
    await flushPromises()

    expect(wrapper.find('[role="listbox"]').exists()).toBe(false)
  })
})
