import { apiRequest, toQueryString } from './apiClient'
import type { KlineQuery, KlineSeries, QuoteSnapshot } from '@/types/domain'

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
