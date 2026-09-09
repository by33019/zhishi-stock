import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { describe, expect, it } from 'vitest'

import AppShell from './AppShell.vue'

describe('AppShell', () => {
  it('展示核心导航、搜索和交易状态', () => {
    const wrapper = mount(AppShell, {
      global: {
        plugins: [createPinia()],
        stubs: {
          RouterLink: { template: '<a><slot /></a>' },
          RouterView: { template: '<main>页面内容</main>' },
        },
      },
    })

    expect(wrapper.text()).toContain('市场总览')
    expect(wrapper.text()).toContain('行情榜单')
    expect(wrapper.text()).toContain('AI 研究')
    expect(wrapper.text()).toContain('交易中')
    expect(wrapper.find('input[aria-label="全局证券搜索"]').exists()).toBe(true)
  })

  it('可以打开和关闭 AI 研究侧栏', async () => {
    const wrapper = mount(AppShell, {
      global: {
        plugins: [createPinia()],
        stubs: {
          RouterLink: { template: '<a><slot /></a>' },
          RouterView: { template: '<main>页面内容</main>' },
        },
      },
    })

    await wrapper.get('[data-testid="ai-panel-toggle"]').trigger('click')
    expect(wrapper.get('[data-testid="ai-side-panel"]').text()).toContain('研究助手')

    await wrapper.get('[aria-label="关闭 AI 研究侧栏"]').trigger('click')
    expect(wrapper.find('[data-testid="ai-side-panel"]').exists()).toBe(false)
  })
})
