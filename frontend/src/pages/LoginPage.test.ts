import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import LoginPage from './LoginPage.vue'

function mountLoginPage() {
  return mount(LoginPage, {
    global: {
      stubs: {
        RouterLink: {
          props: ['to'],
          template: '<a :href="to"><slot /></a>',
        },
      },
    },
  })
}

describe('登录页', () => {
  it('在统一研究画布中组织品牌叙事与登录任务', () => {
    const wrapper = mountLoginPage()

    expect(wrapper.find('.login-canvas').exists()).toBe(true)
    expect(wrapper.find('.login-form-wrap').exists()).toBe(false)
    expect(wrapper.get('.login-atmosphere').attributes('aria-hidden')).toBe('true')
    expect(wrapper.findAll('h1')).toHaveLength(1)
    expect(wrapper.text()).toContain('让每个判断')
    expect(wrapper.text()).toContain('登录研究工作台')
    expect(wrapper.text()).toContain('AI 仅提供研究辅助')
    expect(wrapper.get('.login-back').attributes('href')).toBe('/market')
    expect(wrapper.find('input[autocomplete="username"]').exists()).toBe(true)
    expect(wrapper.find('input[autocomplete="current-password"]').exists()).toBe(true)
  })

  it('切换密码可见状态并同步可访问名称', async () => {
    const wrapper = mountLoginPage()
    const passwordInput = wrapper.get('input[autocomplete="current-password"]')
    const visibilityButton = wrapper.get('[data-testid="password-visibility"]')

    expect(passwordInput.attributes('type')).toBe('password')
    expect(visibilityButton.attributes('aria-label')).toBe('显示密码')

    await visibilityButton.trigger('click')

    expect(passwordInput.attributes('type')).toBe('text')
    expect(visibilityButton.attributes('aria-label')).toBe('隐藏密码')
  })
})
