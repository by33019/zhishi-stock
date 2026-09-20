import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'

import type { MarketStatus } from '@/types/domain'

const marketApi = vi.hoisted(() => ({
  getMarketOverview: vi.fn(),
  getMarketStatus: vi.fn(),
}))
vi.mock('@/services/marketApi', () => marketApi)

import { MARKET_STATUS_REFRESH_MS, useMarketStatus } from './useMarketStatus'

const TRADING_DAY: MarketStatus = {
  marketCode: 'CN',
  tradeDate: '2026-09-18',
  isTradingDay: true,
  sessionStatus: 'TRADING',
  currentSession: 'MORNING_CONTINUOUS',
  nextSessionAt: '2026-09-18T11:30:00+08:00',
  calendarSourceTime: '2026-09-18T10:00:00+08:00',
}

/** 宿主组件：`onMounted` / `onBeforeUnmount` 必须在真实组件里才会执行。 */
let api: ReturnType<typeof useMarketStatus>

const Host = defineComponent({
  setup() {
    api = useMarketStatus()
    return () => h('div')
  },
})

function mountHost() {
  return mount(Host)
}

describe('useMarketStatus', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // `shouldAdvanceTime`：假的 `setTimeout` 也必须能自己走完，
    // 否则 `flushPromises()`（内部就是 `setTimeout(resolve, 0)`）永远不 resolve。
    vi.useFakeTimers({ shouldAdvanceTime: true })
    marketApi.getMarketStatus.mockResolvedValue(structuredClone(TRADING_DAY))
  })

  afterEach(() => {
    vi.useRealTimers()
    // 用例 2 会把 document.hidden 改成不可变属性，恢复它避免影响后续文件
    Reflect.deleteProperty(document, 'hidden')
  })

  it('挂载即请求一次，并按固定节拍刷新', async () => {
    const wrapper = mountHost()
    await flushPromises()

    expect(marketApi.getMarketStatus).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(MARKET_STATUS_REFRESH_MS)
    expect(marketApi.getMarketStatus).toHaveBeenCalledTimes(2)

    await vi.advanceTimersByTimeAsync(MARKET_STATUS_REFRESH_MS * 2)
    expect(marketApi.getMarketStatus).toHaveBeenCalledTimes(4)

    wrapper.unmount()
  })

  it('页面不可见时暂停刷新，恢复可见时立刻补一次', async () => {
    const wrapper = mountHost()
    await flushPromises()
    expect(marketApi.getMarketStatus).toHaveBeenCalledTimes(1)

    Object.defineProperty(document, 'hidden', { value: true, configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(MARKET_STATUS_REFRESH_MS * 3)

    // 后台标签页持续请求没有意义
    expect(marketApi.getMarketStatus).toHaveBeenCalledTimes(1)

    Object.defineProperty(document, 'hidden', { value: false, configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()

    // 切回来立刻补一次，否则会看到过期状态
    expect(marketApi.getMarketStatus).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  it('卸载后停止刷新', async () => {
    const wrapper = mountHost()
    await flushPromises()

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(MARKET_STATUS_REFRESH_MS * 3)

    expect(marketApi.getMarketStatus).toHaveBeenCalledTimes(1)
  })

  it('把 sessionStatus 映射成中文文案', async () => {
    for (const [session, label] of [
      ['PRE_OPEN', '盘前'],
      ['CALL_AUCTION', '集合竞价'],
      ['TRADING', '交易中'],
      ['BREAK', '午间休市'],
      ['CLOSED', '已收盘'],
    ] as const) {
      marketApi.getMarketStatus.mockResolvedValue({
        ...structuredClone(TRADING_DAY),
        sessionStatus: session,
      })
      const wrapper = mountHost()
      await flushPromises()

      expect(api.label.value).toBe(label)
      wrapper.unmount()
    }
  })

  it('非交易日显示"休市"而不是"已收盘"', async () => {
    marketApi.getMarketStatus.mockResolvedValue({
      ...structuredClone(TRADING_DAY),
      tradeDate: '2026-09-18',
      isTradingDay: false,
      sessionStatus: 'CLOSED',
      currentSession: 'CLOSED',
      nextSessionAt: null,
    })
    const wrapper = mountHost()
    await flushPromises()

    // 周日显示"已收盘"会让人以为今天开过市
    expect(api.label.value).toBe('休市')
    expect(api.isLive.value).toBe(false)

    wrapper.unmount()
  })

  it('收盘后不再算"进行中"，顶栏不该显示当前时刻', async () => {
    marketApi.getMarketStatus.mockResolvedValue({
      ...structuredClone(TRADING_DAY),
      sessionStatus: 'CLOSED',
      currentSession: 'CLOSED',
    })
    const wrapper = mountHost()
    await flushPromises()

    expect(api.isLive.value).toBe(false)
    wrapper.unmount()
  })

  it('失败时清掉旧状态并暴露后端文案与 traceId', async () => {
    marketApi.getMarketStatus.mockRejectedValue({
      code: 'MARKET_DATA_UNAVAILABLE',
      message: '市场行情暂不可用',
      traceId: 'trace-503',
    })
    const wrapper = mountHost()
    await flushPromises()

    expect(api.label.value).toBeNull()
    expect(api.error.value?.message).toBe('市场行情暂不可用')
    expect(api.error.value?.traceId).toBe('trace-503')

    wrapper.unmount()
  })

  it('重试成功后错误态被清掉', async () => {
    marketApi.getMarketStatus.mockRejectedValueOnce({ message: '网络错误' })
    const wrapper = mountHost()
    await flushPromises()
    expect(api.error.value).toBeTruthy()

    await api.reload()
    expect(api.error.value).toBeUndefined()
    expect(api.label.value).toBe('交易中')

    wrapper.unmount()
  })

  it('now 随刷新节拍更新，供顶栏显示北京时间', async () => {
    const wrapper = mountHost()
    await flushPromises()
    const before = api.now.value

    await vi.advanceTimersByTimeAsync(MARKET_STATUS_REFRESH_MS)

    expect(api.now.value).not.toBe(before)
    wrapper.unmount()
  })
})
