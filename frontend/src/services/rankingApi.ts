import { apiRequest, toQueryString } from './apiClient'
import type { RankingQuery, StockRanking } from '@/types/domain'

/** QTE-01：涨幅榜 / 跌幅榜 / 成交额榜。 */
export function getStockRankings(query: RankingQuery) {
  const search = toQueryString({
    rankingType: query.rankingType,
    exchangeCodes: query.exchangeCodes,
    boardCodes: query.boardCodes,
    sectorId: query.sectorId,
    excludeSt: query.excludeSt,
    excludeSuspended: query.excludeSuspended,
    page: query.page,
    size: query.size,
  })
  return apiRequest<StockRanking>(`/stock-rankings?${search}`)
}
