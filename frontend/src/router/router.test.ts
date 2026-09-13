import { describe, expect, it, vi } from 'vitest'

import { guardRoute, routes } from './index'

describe('应用路由', () => {
  it('包含完整的前台研究路径和管理端入口', () => {
    const paths = routes.map((route) => route.path)

    expect(paths).toEqual(
      expect.arrayContaining([
        '/market',
        '/rankings',
        '/sectors',
        '/sectors/:id',
        '/stocks/:id',
        '/news',
        '/watchlist',
        '/ai',
        '/history',
        '/login',
        '/admin',
      ]),
    )
  })

  it('为个人功能和管理端声明访问级别', () => {
    expect(routes.find((route) => route.path === '/watchlist')?.meta?.access).toBe('USER')
    expect(routes.find((route) => route.path === '/admin')?.meta?.access).toBe('ADMIN')
    expect(routes.find((route) => route.path === '/market')?.meta?.access).toBe('PUBLIC')
  })

  it('游客访问 USER 页面时恢复会话并携带原地址跳转登录', async () => {
    const auth = {
      authenticated: false,
      isAdmin: false,
      restore: vi.fn().mockResolvedValue(undefined),
    }

    const result = await guardRoute(
      { meta: { access: 'USER' }, fullPath: '/watchlist?group=1', name: 'watchlist' },
      auth,
    )

    expect(auth.restore).toHaveBeenCalledOnce()
    expect(result).toEqual({ name: 'login', query: { redirect: '/watchlist?group=1' } })
  })

  it('非管理员不能进入 ADMIN 页面', async () => {
    const result = await guardRoute(
      { meta: { access: 'ADMIN' }, fullPath: '/admin', name: 'admin' },
      { authenticated: true, isAdmin: false, restore: vi.fn() },
    )

    expect(result).toEqual({ name: 'market' })
  })
})
