import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import LoginPage from './LoginPage.vue'

describe('登录页', () => {
  it('清晰说明登录价值并提供安全登录表单', () => {
    const wrapper = mount(LoginPage, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })

    expect(wrapper.text()).toContain('让每个判断')
    expect(wrapper.text()).toContain('登录研究工作台')
    expect(wrapper.find('input[type="text"]').exists()).toBe(true)
    expect(wrapper.find('input[type="password"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('AI 仅提供研究辅助')
  })
})
