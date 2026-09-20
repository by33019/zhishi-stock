import { apiRequest } from './apiClient'
import type { MarketOverview, MarketStatus } from '@/types/domain'

export function getMarketOverview() {
  return apiRequest<MarketOverview>('/markets/overview?market=CN')
}

/**
 * MKT-02：市场交易状态。
 *
 * 非交易日返回 `isTradingDay: false` 且 `sessionStatus: 'CLOSED'`，
 * 契约明确这不视为数据延迟——因此调用方不要把它当成异常。
 */
export function getMarketStatus(marketCode = 'CN') {
  return apiRequest<MarketStatus>(`/markets/${marketCode}/status`)
}
