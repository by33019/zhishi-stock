import { apiRequest, toQueryString } from './apiClient'
import type { NewsOptions, NewsPage, NewsQuery } from '@/types/domain'

/**
 * NEWS-01：资讯中心列表。
 *
 * 所有筛选条件都走服务端：类型（`newsTypes`）、关键字（`keyword`）、分页（`page` / `size`）。
 * 「不限类型」= **不传** `newsTypes`（`toQueryString` 会丢弃空串），而不是传空串让服务端去猜。
 */
export function getNews(query: NewsQuery = {}) {
  const search = toQueryString({
    newsTypes: query.newsTypes,
    keyword: query.keyword,
    page: query.page,
    size: query.size,
  })
  return apiRequest<NewsPage>(`/news?${search}`)
}

/**
 * NEWS-04：受控筛选项。
 *
 * 类型标签的取值来自这里，而不是前端硬编码枚举——服务端新增一种资讯类型时，
 * 前端会自动多出一个标签。中文名映射由 `newsTypeLabel` 负责，未知值回退为原值。
 */
export function getNewsOptions() {
  return apiRequest<NewsOptions>('/news/options')
}
