import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

import AdminOverviewPage from './AdminOverviewPage.vue'

/**
 * 后台壳的行为：六个分区 Tab、默认落在总览、点击切换才加载对应分区。
 *
 * 分区内的数据请求在测试里用 fetch stub 承接（返回空分页壳）——
 * 这里只关心"切到哪个 Tab 发哪组请求"的路由行为，不关心数据渲染。
 */

function pageEnvelope() {
  return {
    success: true, code: 'SUCCESS', message: '', traceId: 't',
    data: { items: [], page: 1, size: 20, total: 0, totalPages: 0, hasNext: false },
  }
}

const fetchMock = vi.fn()

beforeEach(() => {
  setActivePinia(createPinia())
  fetchMock.mockImplementation(async (path: string) => {
    if (path.includes('/users/me')) {
      return new Response(JSON.stringify({
        success: true, code: 'SUCCESS', message: '', traceId: 't',
        data: { userId: 1, username: 'admin', roles: ['ADMIN'], permissions: ['admin:access'] },
      }), { status: 200 })
    }
    return new Response(JSON.stringify(pageEnvelope()), { status: 200 })
  })
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
  fetchMock.mockClear()
})

describe('AdminOverviewPage', () => {
  it('renders the six admin sections as tabs', () => {
    const wrapper = mount(AdminOverviewPage, { global: { plugins: [createPinia()] } })
    const tabs = wrapper.findAll('.admin-tab')
    expect(tabs).toHaveLength(6)
    expect(tabs[0].classes()).toContain('active')
  })

  it('mounts the users section only after clicking its tab', async () => {
    const wrapper = mount(AdminOverviewPage, { global: { plugins: [createPinia()] } })
    await flushPromises()
    const userCallsBefore = fetchMock.mock.calls.filter(([path]) =>
      String(path).includes('/admin/users')).length
    expect(userCallsBefore).toBe(0)

    await wrapper.findAll('.admin-tab')[1].trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('用户列表')
    const userCallsAfter = fetchMock.mock.calls.filter(([path]) =>
      String(path).includes('/admin/users')).length
    expect(userCallsAfter).toBeGreaterThan(0)
  })

  it('does not render fabricated demo numbers', () => {
    const wrapper = mount(AdminOverviewPage, { global: { plugins: [createPinia()] } })
    // 原型页的编造数值不得回归
    expect(wrapper.text()).not.toContain('12,680')
    expect(wrapper.text()).not.toContain('486')
  })
})
