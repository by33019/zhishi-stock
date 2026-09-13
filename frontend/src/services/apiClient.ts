import type { ApiResponse } from '@/types/domain'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'
const REQUEST_TIMEOUT_MS = 10_000

let accessToken: string | undefined
let refreshInFlight: Promise<TokenResponse> | undefined
let authenticationFailureHandler: (() => void) | undefined

export interface UserSummary {
  userId: number
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

export async function refreshAccessToken(): Promise<TokenResponse> {
  return refreshOnce()
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  return request<T>(path, init, true)
}

async function request<T>(path: string, init: RequestInit, mayRefresh: boolean): Promise<T> {
  const tokenUsed = accessToken
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  if (tokenUsed) headers.set('Authorization', `Bearer ${tokenUsed}`)

  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)
  let response: Response
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      ...init,
      headers,
      credentials: 'include',
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
  const response = await fetch(`${API_BASE_URL}${path}`, {
    method: 'POST',
    credentials: 'include',
    headers: { Accept: 'application/json' },
  })
  const envelope = await parseEnvelope<TokenResponse>(response)
  if (!envelope.success) throw toApiError(response.status, envelope)
  if (!response.ok) throw new ApiError(envelope.code, envelope.message, response.status, envelope.traceId)
  return envelope.data
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
