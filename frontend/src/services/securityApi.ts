import { apiRequest, toQueryString } from './apiClient'
import type { KlineQuery, KlineSeries, QuoteSnapshot, SecuritySearchResult } from '@/types/domain'

/**
 * STK-01：搜索建议。
 *
 * `q` 由调用方保证已裁剪且非空——契约要求 1–50 字符，空串会被后端判成 400。
 * `limit` 显式传值而不是依赖后端默认的 10：PRD QTE-01 要求"最多 10 条"，
 * 依赖默认值会让这条需求在别人改默认值时静默失效。
 */
export function searchSecurities(q: string, limit = 10) {
  const search = toQueryString({ q, limit })
  return apiRequest<SecuritySearchResult>(`/securities/search?${search}`)
}

/** STK-04：个股最新快照。 */
export function getSecurityQuote(securityId: string) {
  return apiRequest<QuoteSnapshot>(`/securities/${encodeURIComponent(securityId)}/quote`)
}

/**
 * STK-07：个股日 / 周 / 月 K 线。
 *
 * 周期是**服务端参数**（周 K / 月 K 由服务端按交易日历聚合），
 * 因此切换周期必须重新请求，不能在客户端把日 K 折叠成周 K——
 * 客户端的交易日历与服务端不一致时，聚合出的周会错位。
 */
export function getSecurityKlines(securityId: string, query: KlineQuery) {
  const search = toQueryString({
    period: query.period,
    startDate: query.startDate,
    endDate: query.endDate,
    adjustment: query.adjustment,
  })
  return apiRequest<KlineSeries>(`/securities/${encodeURIComponent(securityId)}/klines?${search}`)
}
