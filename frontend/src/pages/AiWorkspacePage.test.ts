import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import AiWorkspacePage from './AiWorkspacePage.vue'

describe('AI 研究工作台', () => {
  afterEach(() => vi.useRealTimers())

  it('由研究问题生成包含证据与风险边界的报告', async () => {
    vi.useFakeTimers()
    const wrapper = mount(AiWorkspacePage, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })

    await wrapper.get('[data-testid="prompt-template"]').trigger('click')
    await wrapper.get('[data-testid="generate-report"]').trigger('click')
    await vi.runAllTimersAsync()
    await flushPromises()

    expect(wrapper.text()).toContain('核心结论')
    expect(wrapper.text()).toContain('量价依据')
    expect(wrapper.text()).toContain('风险与不确定性')
    expect(wrapper.text()).toContain('来源引用')
    expect(wrapper.text()).toContain('仅供研究参考')
  })
})
