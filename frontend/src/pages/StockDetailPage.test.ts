import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import StockDetailPage from './StockDetailPage.vue'

describe('个股详情页', () => {
  afterEach(() => vi.useRealTimers())

  it('同时呈现行情、图表、事件证据与场景化 AI 入口', async () => {
    vi.useFakeTimers()
    const wrapper = mount(StockDetailPage, {
      global: {
        mocks: { $route: { params: { id: '19876543210001' } } },
        stubs: {
          RouterLink: { template: '<a><slot /></a>' },
          BaseChart: { template: '<div data-testid="chart-stub" />' },
        },
      },
    })

    await vi.runAllTimersAsync()
    await flushPromises()

    expect(wrapper.text()).toContain('浦发银行')
    expect(wrapper.text()).toContain('12.35')
    expect(wrapper.text()).toContain('日 K')
    expect(wrapper.text()).toContain('经营与主题')
    expect(wrapper.text()).toContain('关联事件')
    expect(wrapper.text()).toContain('AI 异动解读')
    expect(wrapper.find('[data-testid="chart-stub"]').exists()).toBe(true)
  })
})
