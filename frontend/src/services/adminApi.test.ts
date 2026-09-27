import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createAdminNewsSource,
  getAdminAiOverview,
  getAdminJobExecutions,
  getAdminNewsRelations,
  getAdminUsers,
  reviewAdminNewsRelation,
} from './adminApi'

/**
 * 后台 API 层的请求形状契约：路径、查询串、幂等键头。
 *
 * 后台的每一条路径都带 `/admin` 前缀（apiClient 的 base 是 `/api/v1`），
 * 写操作都带 `Idempotency-Key`——这两件事写错任何一处，浏览器里表现为 404
 * 或"重复点击创建了两条"，而单测是唯一能在构建期钉住它们的地方。
 */

const fetchMock = vi.fn()

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
  fetchMock.mockResolvedValue(new Response(JSON.stringify({
    success: true, code: 'SUCCESS', message: '', traceId: 't-1',
    data: { items: [], page: 1, size: 20, total: 0, totalPages: 0, hasNext: false },
  }), { status: 200 }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  fetchMock.mockClear()
})

describe('adminApi', () => {
  it('getAdminUsers hits /admin/users with query params', async () => {
    await getAdminUsers({ keyword: 'demo', page: 2 })
    const [path] = fetchMock.mock.calls[0]
    // size 未传：toQueryString 丢弃 undefined，不让 "size=undefined" 到达服务端
    expect(path).toBe('/api/v1/admin/users?keyword=demo&page=2')
  })

  it('getAdminNewsRelations defaults to the CANDIDATE queue', async () => {
    await getAdminNewsRelations()
    const [path] = fetchMock.mock.calls[0]
    expect(path).toBe('/api/v1/admin/news-relations?')
  })

  it('createAdminNewsSource sends the idempotency key header', async () => {
    await createAdminNewsSource(
      { sourceCode: 'sim-media-a', sourceName: 'A', sourceType: 'MEDIA' },
      'key-1',
    )
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(init.method).toBe('POST')
    // apiClient 会把 init.headers 归一成 Headers 实例再交给 fetch
    expect((init.headers as Headers).get('Idempotency-Key')).toBe('key-1')
  })

  it('reviewAdminNewsRelation PATCHes the decision', async () => {
    await reviewAdminNewsRelation(21, 'CONFIRMED')
    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v1/admin/news-relations/21')
    expect(init.method).toBe('PATCH')
  })

  it('getAdminAiOverview hits the overview endpoint', async () => {
    await getAdminAiOverview()
    const [path] = fetchMock.mock.calls[0]
    expect(path).toBe('/api/v1/admin/ai/overview?')
  })

  it('getAdminJobExecutions forwards the status filter', async () => {
    await getAdminJobExecutions({ status: 'FAILED' })
    const [path] = fetchMock.mock.calls[0]
    expect(path).toBe('/api/v1/admin/job-executions?status=FAILED')
  })
})
