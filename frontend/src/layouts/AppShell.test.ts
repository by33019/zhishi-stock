import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const client = vi.hoisted(() => ({
  apiRequest: vi.fn(),
  clearAccessToken: vi.fn(),
  onAuthenticationFailure: vi.fn(),
  refreshAccessToken: vi.fn(),
  setAccessToken: vi.fn(),
}))
const navigation = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('@/services/apiClient', () => client)
vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRouter: () => navigation,
}))

import { useAuthStore } from '@/stores/auth'
import AppShell from './AppShell.vue'

describe('AppShell', () => {
  beforeEach(() => vi.clearAllMocks())

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

    expect(wrapper.text()).toContain('开发测试用户')
    await wrapper.get('[data-testid="logout-button"]').trigger('click')
    await flushPromises()

    expect(client.apiRequest).toHaveBeenCalledWith('/auth/logout', { method: 'POST' })
    expect(navigation.push).toHaveBeenCalledWith('/market')
  })
})
