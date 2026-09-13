import { apiRequest } from './apiClient'
import type { MarketOverview } from '@/types/domain'

export function getMarketOverview() {
  return apiRequest<MarketOverview>('/markets/overview?market=CN')
}
