import { describe, expect, it } from 'vitest'

import { routes } from './index'

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
})
