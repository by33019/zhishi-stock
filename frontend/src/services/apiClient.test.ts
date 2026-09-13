import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiRequest, clearAccessToken, setAccessToken } from './apiClient'

function response(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

describe('REST Client', () => {
  afterEach(() => {
    clearAccessToken()
    vi.unstubAllGlobals()
  })

  it('并发 401 只执行一次刷新，并用新令牌重试所有请求', async () => {
    setAccessToken('expired-token')
    let refreshCalls = 0
    let protectedCalls = 0
    const fetchMock = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/auth/token/refresh')) {
        refreshCalls += 1
        return response(200, {
          success: true,
          code: 'SUCCESS',
          message: '刷新成功',
          data: { accessToken: 'fresh-token', permissions: [] },
          traceId: 'trace-refresh',
          timestamp: '2026-09-13T10:00:00+08:00',
        })
      }
      protectedCalls += 1
      if (protectedCalls <= 2) {
        return response(401, { success: false, code: 'UNAUTHORIZED', traceId: 'trace-401' })
      }
      return response(200, {
        success: true,
        code: 'SUCCESS',
        message: '成功',
        data: { ok: true },
        traceId: 'trace-ok',
        timestamp: '2026-09-13T10:00:00+08:00',
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    await Promise.all([apiRequest('/users/me'), apiRequest('/users/me/permissions')])

    expect(refreshCalls).toBe(1)
    const retryCalls = fetchMock.mock.calls.filter(([, init]) =>
      new Headers(init?.headers).get('Authorization') === 'Bearer fresh-token',
    )
    expect(retryCalls).toHaveLength(2)
  })

  it('将后端错误码、traceId 和字段错误保留给页面', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response(423, {
      success: false,
      code: 'ACCOUNT_LOCKED',
      message: '账户已临时锁定',
      data: { fieldErrors: { account: '请稍后重试' } },
      traceId: 'trace-locked',
      timestamp: '2026-09-13T10:00:00+08:00',
    })))

    await expect(apiRequest('/auth/login', { method: 'POST' })).rejects.toMatchObject({
      code: 'ACCOUNT_LOCKED',
      traceId: 'trace-locked',
      fieldErrors: { account: '请稍后重试' },
    })
  })
})
