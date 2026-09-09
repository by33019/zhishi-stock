import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import MarketOverview from './MarketOverview.vue'

describe('市场总览页', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('将市场证据、机会线索和数据时间组织在同一首屏', async () => {
    vi.useFakeTimers()
    const wrapper = mount(MarketOverview, {
      global: {
        stubs: {
          RouterLink: { template: '<a><slot /></a>' },
          BaseChart: { template: '<div data-testid="chart-stub" />' },
        },
      },
    })

    await vi.runAllTimersAsync()
    await flushPromises()

    expect(wrapper.text()).toContain('盘面温度')
    expect(wrapper.text()).toContain('上证指数')
    expect(wrapper.text()).toContain('市场广度')
    expect(wrapper.text()).toContain('热点板块')
    expect(wrapper.text()).toContain('行情热榜')
    expect(wrapper.text()).toContain('事件雷达')
    expect(wrapper.text()).toContain('14:32')
    expect(wrapper.findAll('[data-testid="chart-stub"]').length).toBeGreaterThanOrEqual(2)
  })
})
