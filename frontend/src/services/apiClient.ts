import type { ApiResponse, ExportDownload } from '@/types/domain'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'
const REQUEST_TIMEOUT_MS = 10_000

/**
 * 二进制下载的超时。
 *
 * 不复用 10 秒：导出文件可能是几千行的 xlsx，服务端还要现从卷里读出来。
 * 10 秒的超时会把"正在下载一个正常的大文件"报成"请求超时"，
 * 而用户看到那句话只会去重试，重试又超时——一个自制的死循环。
 */
const DOWNLOAD_TIMEOUT_MS = 120_000

let accessToken: string | undefined
let refreshInFlight: Promise<TokenResponse> | undefined
let authenticationFailureHandler: (() => void) | undefined

export interface UserSummary {
  userId: string
  username: string
  displayName: string
}

export interface TokenResponse {
  accessToken: string
  accessExpiresInSeconds: number
  refreshExpiresInSeconds: number
  user: UserSummary | null
  permissions: string[]
}

interface ErrorEnvelope {
  code?: string
  message?: string
  data?: { fieldErrors?: Record<string, string> }
  traceId?: string
}

export class ApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status: number,
    public readonly traceId?: string,
    public readonly fieldErrors?: Record<string, string>,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export function setAccessToken(token: string) {
  accessToken = token
}

export function clearAccessToken() {
  accessToken = undefined
}

export function onAuthenticationFailure(handler: () => void) {
  authenticationFailureHandler = handler
}

/**
 * 把查询参数拼成 query string，**丢弃** `null` / `undefined` / 空串。
 *
 * 丢弃而不是传空串：后端把空串按"未传"处理（`QueryParameters.isPresent`），
 * 但传 `exchangeCodes=` 这种空值参数会让服务端日志与网关统计里出现无意义的条目。
 * `false` 与 `0` 是**有效取值**，必须保留——`excludeSt=false` 与不传是不同的语义。
 */
export function toQueryString(
  params: Record<string, string | number | boolean | null | undefined>,
): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === '') continue
    search.set(key, String(value))
  }
  return search.toString()
}

export async function refreshAccessToken(): Promise<TokenResponse> {
  return refreshOnce()
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  return request<T>(path, init, true)
}

/**
 * 下载一个二进制响应（EXP-03）。
 *
 * <h2>为什么不能走 {@link apiRequest}</h2>
 * {@code apiRequest} 无条件把响应体当 JSON 解，而这里的成功响应是一片 xlsx 字节。
 * 更关键的是**失败**响应仍然是那套 JSON 壳（404 / 409 / 429），所以两条分支要走不同解析，
 * 不能用同一个入口。
 *
 * <h2>为什么文件名与截止时间从响应头取</h2>
 * 服务端已经算好了文件名（含榜单口径与批次时间戳）与数据截止时间。
 * 前端自己拼一个（例如用页面上的 `dataTime`）会在跨批次时与服务端分叉，
 * 而这些分叉在界面上一模一样，看不出来。
 *
 * <h2>401 的重试与 JSON 请求同一套</h2>
 * 下载也会遇到 access token 过期。不刷新直接报错的话，用户会看到"导出失败"，
 * 而真实原因是"登录态过期了，刷新一下再下就行"——两件事的下一步完全不同。
 */
export async function apiDownload(path: string, init: RequestInit = {}): Promise<ExportDownload> {
  return download(path, init, true)
}

async function download(
  path: string,
  init: RequestInit,
  mayRefresh: boolean,
): Promise<ExportDownload> {
  const tokenUsed = accessToken
  const headers = new Headers(init.headers)
  // 刻意不设 `Accept: application/json`：成功时回来的是二进制。
  // 失败时的 JSON 错误体由服务端显式指定 Content-Type，不依赖内容协商。
  headers.set('Accept', '*/*')
  if (tokenUsed) headers.set('Authorization', `Bearer ${tokenUsed}`)

  const response = await fetchWithTimeout(
    path,
    { ...init, headers, credentials: 'include' },
    DOWNLOAD_TIMEOUT_MS,
  )

  if (response.status === 401 && mayRefresh) {
    try {
      if (!accessToken || accessToken === tokenUsed) await refreshOnce()
      return download(path, init, false)
    } catch (error) {
      clearAccessToken()
      authenticationFailureHandler?.()
      throw error
    }
  }

  if (!response.ok) {
    // 失败响应是 JSON 壳；这里不吞掉 INVALID_RESPONSE——一个既不是二进制、
    // 又不是错误壳的响应必须让调用方看见，而不是被解释成"下载成功但内容为空"。
    const envelope = await parseEnvelope<never>(response)
    throw toApiError(response.status, envelope)
  }

  return {
    blob: await response.blob(),
    fileName: fileNameFrom(response.headers.get('Content-Disposition')),
    dataCutoffAt: response.headers.get('X-Data-Cutoff-At'),
  }
}

/**
 * 从 `Content-Disposition` 解析文件名。
 *
 * <p>RFC 5987 的 `filename*` **优先**：它带字符集声明，是中文文件名的唯一正确形式。
 * 服务端（Spring 的 `ContentDisposition`）在带字符集时会把两个都写上，
 * 而那个不带星号的 `filename` 是 MIME 编码字（实测形如 `=?UTF-8?Q?...?=`）——
 * 直接拿它当文件名，用户下载到的是一个问号套问号的文件。
 *
 * <p>因此：解不出来就返回 `null`，由调用方给一个兜底名。
 * **不返回半解析的垃圾串**——一个明显不对的文件名至少能被用户看出来，
 * 而 `=?UTF-8?Q?...` 这种会让人以为系统坏了。
 */
function fileNameFrom(disposition: string | null): string | null {
  if (!disposition) return null

  const extended = /filename\*\s*=\s*[^']*'[^']*'([^;]+)/i.exec(disposition)
  if (extended) {
    try {
      return decodeURIComponent(extended[1]!.trim())
    } catch {
      // 百分号编码坏了就往下走，别把异常抛到用户面前。
    }
  }

  const plain = /filename\s*=\s*"?([^";]+)"?/i.exec(disposition)?.[1]?.trim()
  if (!plain || plain.startsWith('=?')) return null
  return plain
}

async function request<T>(path: string, init: RequestInit, mayRefresh: boolean): Promise<T> {
  const tokenUsed = accessToken
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  if (tokenUsed) headers.set('Authorization', `Bearer ${tokenUsed}`)

  const response = await fetchWithTimeout(path, {
    ...init,
    headers,
    credentials: 'include',
  })

  if (response.status === 401 && mayRefresh && !isAuthenticationEntry(path)) {
    try {
      if (!accessToken || accessToken === tokenUsed) await refreshOnce()
      return request<T>(path, init, false)
    } catch (error) {
      clearAccessToken()
      authenticationFailureHandler?.()
      throw error
    }
  }

  const envelope = await parseEnvelope<T>(response)
  if (!envelope.success) throw toApiError(response.status, envelope)
  if (!response.ok) throw new ApiError(envelope.code, envelope.message, response.status, envelope.traceId)
  return envelope.data
}

async function refreshOnce(): Promise<TokenResponse> {
  if (!refreshInFlight) {
    refreshInFlight = rawTokenRequest('/auth/token/refresh')
      .then((tokens) => {
        setAccessToken(tokens.accessToken)
        return tokens
      })
      .finally(() => {
        refreshInFlight = undefined
      })
  }
  return refreshInFlight
}

async function rawTokenRequest(path: string): Promise<TokenResponse> {
  const response = await fetchWithTimeout(path, {
    method: 'POST',
    credentials: 'include',
    headers: { Accept: 'application/json' },
  })
  const envelope = await parseEnvelope<TokenResponse>(response)
  if (!envelope.success) throw toApiError(response.status, envelope)
  if (!response.ok) throw new ApiError(envelope.code, envelope.message, response.status, envelope.traceId)
  return envelope.data
}

async function fetchWithTimeout(
  path: string,
  init: RequestInit,
  timeoutMs: number = REQUEST_TIMEOUT_MS,
): Promise<Response> {
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), timeoutMs)
  try {
    return await fetch(`${API_BASE_URL}${path}`, {
      ...init,
      signal: controller.signal,
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new ApiError('REQUEST_TIMEOUT', '请求超时，请稍后重试', 0)
    }
    throw new ApiError('NETWORK_ERROR', '暂时无法连接服务', 0)
  } finally {
    window.clearTimeout(timeout)
  }
}

async function parseEnvelope<T>(response: Response): Promise<ApiResponse<T> | (ErrorEnvelope & { success: false })> {
  try {
    return await response.json()
  } catch {
    throw new ApiError('INVALID_RESPONSE', '服务返回了无法识别的数据', response.status)
  }
}

function toApiError(status: number, envelope: ErrorEnvelope) {
  return new ApiError(
    envelope.code ?? `HTTP_${status}`,
    envelope.message ?? '请求失败',
    status,
    envelope.traceId,
    envelope.data?.fieldErrors,
  )
}

function isAuthenticationEntry(path: string) {
  return path === '/auth/login' || path === '/auth/token/refresh'
}
