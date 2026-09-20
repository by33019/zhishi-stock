import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { MarketStatus } from '@/types/domain'

const client = vi.hoisted(() => ({
  apiRequest: vi.fn(),
  clearAccessToken: vi.fn(),
  onAuthenticationFailure: vi.fn(),
  refreshAccessToken: vi.fn(),
  setAccessToken: vi.fn(),
}))
const marketApi = vi.hoisted(() => ({
  getMarketOverview: vi.fn(),
  getMarketStatus: vi.fn(),
}))
const navigation = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('@/services/apiClient', () => client)
vi.mock('@/services/marketApi', () => marketApi)
vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRouter: () => navigation,
}))

import { useAuthStore } from '@/stores/auth'
import AppShell from './AppShell.vue'

const TRADING: MarketStatus = {
  marketCode: 'CN',
  tradeDate: '2026-09-18',
  isTradingDay: true,
  sessionStatus: 'TRADING',
  currentSession: 'MORNING_CONTINUOUS',
  nextSessionAt: '2026-09-18T11:30:00+08:00',
  calendarSourceTime: '2026-09-18T10:00:00+08:00',
}

function mountShell() {
  return mount(AppShell, {
    global: {
      plugins: [createPinia()],
      stubs: {
        RouterLink: { template: '<a><slot /></a>' },
        RouterView: { template: '<main>页面内容</main>' },
      },
    },
  })
}

describe('AppShell', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    marketApi.getMarketStatus.mockResolvedValue(structuredClone(TRADING))
  })

  it('展示核心导航、搜索和交易状态', async () => {
    const wrapper = mountShell()
    await flushPromises()

    expect(wrapper.text()).toContain('市场总览')
    expect(wrapper.text()).toContain('行情榜单')
    expect(wrapper.text()).toContain('AI 研究')
    expect(wrapper.text()).toContain('交易中')
    expect(wrapper.find('input[aria-label="全局证券搜索"]').exists()).toBe(true)
  })

  it('顶栏状态来自接口，而不是写死的"交易中 14:32"', async () => {
    const wrapper = mountShell()
    await flushPromises()

    const state = wrapper.get('[data-testid="market-state"]')
    expect(state.text()).toContain('交易中')
    // 原型里那个 14:32 是常量，与任何数据都无关
    expect(state.text()).not.toContain('14:32')
    // 交易日 + 下一时段放进 title，不挤压顶栏布局
    expect(state.attributes('title')).toContain('交易日')
    expect(state.attributes('title')).toContain('下一时段')
  })

  it('非交易日显示"休市"且不显示当前时刻', async () => {
    marketApi.getMarketStatus.mockResolvedValue({
      ...structuredClone(TRADING),
      isTradingDay: false,
      sessionStatus: 'CLOSED',
      currentSession: 'CLOSED',
      nextSessionAt: null,
    })
    const wrapper = mountShell()
    await flushPromises()

    const state = wrapper.get('[data-testid="market-state"]')
    expect(state.text()).toContain('休市')
    expect(state.text()).not.toContain('交易中')
    // 休市时那个圆点必须停止脉动——跳动的点本身就在暗示"数据在实时更新"
    expect(state.classes()).toContain('is-idle')
  })

  it('接口失败时显示"状态未知"并可重试，不沿用编造值', async () => {
    marketApi.getMarketStatus.mockRejectedValue({
      code: 'MARKET_DATA_UNAVAILABLE',
      message: '市场行情暂不可用',
      traceId: 'trace-503',
    })
    const wrapper = mountShell()
    await flushPromises()

    const state = wrapper.get('[data-testid="market-state"]')
    expect(state.text()).toContain('状态未知')
    expect(state.attributes('title')).toContain('trace-503')
    // 全站可见的顶栏里不该出现任何编造值
    expect(wrapper.text()).not.toContain('延迟 26 秒')
    expect(wrapper.text()).not.toContain('数据链路正常')
  })

  it('侧栏显示交易日历数据源时间，替代写死的"延迟 26 秒"', async () => {
    const wrapper = mountShell()
    await flushPromises()

    const source = wrapper.get('[data-testid="calendar-source"]')
    expect(source.text()).toContain('交易日历')
    expect(source.text()).toContain('更新于')
    expect(source.text()).not.toContain('延迟 26 秒')
  })

  it('可以打开和关闭 AI 研究侧栏', async () => {
    const wrapper = mountShell()
    await flushPromises()

    await wrapper.get('[data-testid="ai-panel-toggle"]').trigger('click')
    expect(wrapper.get('[data-testid="ai-side-panel"]').text()).toContain('研究助手')

    await wrapper.get('[aria-label="关闭 AI 研究侧栏"]').trigger('click')
    expect(wrapper.find('[data-testid="ai-side-panel"]').exists()).toBe(false)
  })

  it('登录后展示用户并可退出当前会话', async () => {
    client.apiRequest.mockResolvedValue({ loggedOut: true })
    const pinia = createPinia()
    const auth = useAuthStore(pinia)
    auth.user = { userId: '9900000000003', username: 'demo', displayName: '开发测试用户' }
    const wrapper = mount(AppShell, {
      global: {
        plugins: [pinia],
        stubs: {
          RouterLink: { template: '<a><slot /></a>' },
          RouterView: { template: '<main>页面内容</main>' },
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('开发测试用户')
    await wrapper.get('[data-testid="logout-button"]').trigger('click')
    await flushPromises()

    expect(client.apiRequest).toHaveBeenCalledWith('/auth/logout', { method: 'POST' })
    expect(navigation.push).toHaveBeenCalledWith('/market')
  })
})
