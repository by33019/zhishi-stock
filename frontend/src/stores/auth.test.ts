import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const client = vi.hoisted(() => ({
  apiRequest: vi.fn(),
  clearAccessToken: vi.fn(),
  onAuthenticationFailure: vi.fn(),
  refreshAccessToken: vi.fn(),
  setAccessToken: vi.fn(),
}))

vi.mock('@/services/apiClient', () => client)

import { useAuthStore } from './auth'

describe('认证 Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('登录后只在内存保存会话信息', async () => {
    client.apiRequest.mockResolvedValue({
      accessToken: 'access-token',
      accessExpiresInSeconds: 900,
      refreshExpiresInSeconds: 604800,
      user: { userId: '9900000000003', username: 'demo', displayName: '开发测试用户' },
      permissions: ['user:self:read'],
    })
    const store = useAuthStore()

    await store.login('demo', 'Stock@123')

    expect(client.setAccessToken).toHaveBeenCalledWith('access-token')
    expect(store.authenticated).toBe(true)
    expect(store.user?.username).toBe('demo')
    expect(localStorage).toHaveLength(0)
  })

  it('页面刷新时通过 Refresh Cookie 恢复用户与权限', async () => {
    client.refreshAccessToken.mockResolvedValue({
      accessToken: 'restored-token',
      user: null,
      permissions: ['user:self:read'],
    })
    client.apiRequest.mockResolvedValueOnce({
      userId: '9900000000003',
      username: 'demo',
      displayName: '开发测试用户',
      status: 'ACTIVE',
    })
    const store = useAuthStore()

    await store.restore()

    expect(store.ready).toBe(true)
    expect(store.authenticated).toBe(true)
    expect(store.permissions).toContain('user:self:read')
  })
})
