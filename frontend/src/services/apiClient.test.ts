import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiDownload, apiRequest, clearAccessToken, refreshAccessToken, setAccessToken } from './apiClient'

function response(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

/**
 * 一个最小的"像 xlsx 的响应"：内容无关紧要，只要不是 JSON。
 *
 * 用字符串而不是 `new Blob(...)` 作响应体：jsdom 环境的 `Blob` 与 undici 的 `Response`
 * 不是同一份实现，把前者的实例交给后者会直接抛异常（而下载会把任何异常都归成
 * "暂时无法连接服务"，于是用例失败的原因看起来跟文件毫不相干）。
 */
function fileResponse(headers: Record<string, string> = {}) {
  return new Response('PK\u0003\u0004', {
    status: 200,
    headers: {
      'content-type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      ...headers,
    },
  })
}

describe('REST Client', () => {
  afterEach(() => {
    clearAccessToken()
    vi.useRealTimers()
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

  it('刷新会话请求超时后返回统一错误，避免路由恢复无限等待', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn((_input: RequestInfo | URL, init?: RequestInit) =>
      new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => {
          reject(new DOMException('aborted', 'AbortError'))
        })
      }),
    ))

    const rejection = expect(refreshAccessToken()).rejects.toMatchObject({
      code: 'REQUEST_TIMEOUT',
      status: 0,
    })
    await vi.advanceTimersByTimeAsync(10_000)
    await rejection
  })
})

describe('二进制下载（EXP-03）', () => {
  afterEach(() => {
    clearAccessToken()
    vi.unstubAllGlobals()
  })

  it('取 filename* 解出的文件名，而不是那个 MIME 编码字', async () => {
    // 实测服务端（Spring 的 ContentDisposition）两个都给：不带星号的那个是 `=?UTF-8?Q?...?=`，
    // 取它的结果是用户下载到一个"问号套问号"的文件名。
    vi.stubGlobal('fetch', vi.fn(async () => fileResponse({
      'content-disposition':
        `attachment; filename="=?UTF-8?Q?=E6=A6=9C=E5=8D=95.xlsx?="; filename*=UTF-8''%E6%A6%9C%E5%8D%95-20260923.xlsx`,
      'x-data-cutoff-at': '2026-09-23T15:00+08:00',
    })))

    const file = await apiDownload('/export-jobs/1/download')

    expect(file.fileName).toBe('榜单-20260923.xlsx')
    expect(file.dataCutoffAt).toBe('2026-09-23T15:00+08:00')
  })

  it('只有 MIME 编码字时返回 null 交给调用方兜底，而不是返回半解析的垃圾串', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => fileResponse({
      'content-disposition': `attachment; filename="=?UTF-8?Q?=E6=A6=9C=E5=8D=95.xlsx?="`,
    })))

    const file = await apiDownload('/export-jobs/1/download')

    expect(file.fileName).toBeNull()
  })

  it('失败时解的是 JSON 错误壳，业务码与追踪编号照样拿得到', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response(409, {
      success: false,
      code: 'EXPORT_NOT_READY',
      message: '导出文件尚未生成完成',
      traceId: 'trace-409',
      timestamp: '2026-09-23T10:00:00+08:00',
    })))

    await expect(apiDownload('/export-jobs/1/download')).rejects.toMatchObject({
      code: 'EXPORT_NOT_READY',
      message: '导出文件尚未生成完成',
      status: 409,
      traceId: 'trace-409',
    })
  })

  it('令牌过期时先刷新再重下，而不是把"登录态过期"报成"导出失败"', async () => {
    setAccessToken('expired-token')
    let protectedCalls = 0
    const fetchMock = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
      if (String(input).endsWith('/auth/token/refresh')) {
        return response(200, {
          success: true,
          code: 'SUCCESS',
          message: '刷新成功',
          data: { accessToken: 'fresh-token', permissions: [] },
          traceId: 'trace-refresh',
          timestamp: '2026-09-23T10:00:00+08:00',
        })
      }
      protectedCalls += 1
      if (protectedCalls === 1) {
        return response(401, { success: false, code: 'UNAUTHORIZED', traceId: 'trace-401' })
      }
      return fileResponse({ 'content-disposition': `attachment; filename=ranking.xlsx` })
    })
    vi.stubGlobal('fetch', fetchMock)

    const file = await apiDownload('/export-jobs/1/download')

    expect(protectedCalls).toBe(2)
    expect(file.fileName).toBe('ranking.xlsx')
    const retry = fetchMock.mock.calls.find(
      ([, init]) => new Headers(init?.headers).get('Authorization') === 'Bearer fresh-token',
    )
    // 下载不能被内容协商卡住：问服务端要 JSON 的话，成功响应的 xlsx 会被拒收。
    expect(new Headers(retry?.[1]?.headers).get('Accept')).toBe('*/*')
  })
})
