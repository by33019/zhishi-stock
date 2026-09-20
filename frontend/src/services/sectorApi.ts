import { apiRequest, toQueryString } from './apiClient'
import type {
  ConstituentQuery,
  PageData,
  SectorConstituent,
  SectorDetail,
  SectorRanking,
  SectorRankingQuery,
} from '@/types/domain'

/** SEC-02：板块排行。一次请求即返回同一快照下的全部板块行情。 */
export function getSectorRankings(query: SectorRankingQuery) {
  const search = toQueryString({
    sectorType: query.sectorType,
    rankingType: query.rankingType,
    page: query.page,
    size: query.size,
  })
  return apiRequest<SectorRanking>(`/sector-rankings?${search}`)
}

/** SEC-03：板块详情。停用板块仍返回 200（契约：「可返回历史状态」）。 */
export function getSectorDetail(sectorId: string) {
  return apiRequest<SectorDetail>(`/sectors/${encodeURIComponent(sectorId)}`)
}

/** SEC-06：板块成分股。 */
export function getSectorConstituents(sectorId: string, query: ConstituentQuery = {}) {
  const search = toQueryString({
    effectiveDate: query.effectiveDate,
    rankingType: query.rankingType,
    page: query.page,
    size: query.size,
  })
  return apiRequest<PageData<SectorConstituent>>(
    `/sectors/${encodeURIComponent(sectorId)}/constituents?${search}`,
  )
}
