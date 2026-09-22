import { apiRequest, toQueryString } from './apiClient'
import type {
  AiMessage,
  AiSessionDeletion,
  AiSessionDetail,
  AiSessionQuery,
  AiSessionSummary,
  AiSessionUpdated,
  PageData,
} from '@/types/domain'

/**
 * HIS-01：本人会话历史列表。
 *
 * 筛选与分页**全部走服务端**。`scene` 传空串表示"不限类型"——`toQueryString`
 * 会丢弃空串，于是请求里根本不带这个参数，而不是让服务端去猜空串的意思。
 * `favorite` 传 `false` 是**有意义的筛选**（只看未收藏），不能被当成"没传"丢弃，
 * `toQueryString` 保留 `false` 正是为此。
 */
export function getSessions(query: AiSessionQuery = {}) {
  const search = toQueryString({
    scene: query.scene,
    keyword: query.keyword,
    favorite: query.favorite,
    startAt: query.startAt,
    endAt: query.endAt,
    page: query.page,
    size: query.size,
  })
  return apiRequest<PageData<AiSessionSummary>>(`/ai/sessions?${search}`)
}

/** HIS-02：会话详情（目标摘要 + 最近任务与报告摘要）。 */
export function getSession(sessionId: string) {
  return apiRequest<AiSessionDetail>(`/ai/sessions/${encodeURIComponent(sessionId)}`)
}

/**
 * HIS-05：会话消息。
 *
 * 服务端已排除 `SYSTEM` 内部 Prompt，前端不做二次过滤——两处都过滤会让"到底谁负责"
 * 变得含糊，而真正的边界在服务端（客户端过滤挡不住任何人直接用接口）。
 */
export function getSessionMessages(
  sessionId: string,
  query: { page?: number; size?: number } = {},
) {
  const search = toQueryString({ page: query.page, size: query.size })
  return apiRequest<PageData<AiMessage>>(
    `/ai/sessions/${encodeURIComponent(sessionId)}/messages?${search}`,
  )
}

/**
 * HIS-03：重命名 / 收藏。
 *
 * `expectedVersion` **由调用方传入**而不是从这里读——乐观锁的前提是"我用的是哪一版"，
 * 而那只有页面知道（它渲染的就是那一版）。在这里自己取版本会变成"用最新的版本写"，
 * 乐观锁就完全失效了。
 *
 * 两个字段都可选；**不传与传 `false` 语义不同**（后者是"取消收藏"），
 * 所以调用方要用 `undefined` 表示"不改这项"，而不是 `null`。
 */
export function updateSession(
  sessionId: string,
  expectedVersion: number,
  patch: { title?: string; isFavorite?: boolean },
) {
  return apiRequest<AiSessionUpdated>(`/ai/sessions/${encodeURIComponent(sessionId)}`, {
    method: 'PATCH',
    headers: { 'If-Match': String(expectedVersion) },
    body: JSON.stringify(patch),
  })
}

/**
 * HIS-04：软删除。
 *
 * 服务端返回 `purgeAfter`（默认删除后 30 天物理清理），界面要把它显示出来——
 * 只告诉用户"已删除"而数据其实还在，会让人以为删了个假的。
 */
export function deleteSession(sessionId: string, expectedVersion: number) {
  return apiRequest<AiSessionDeletion>(`/ai/sessions/${encodeURIComponent(sessionId)}`, {
    method: 'DELETE',
    headers: { 'If-Match': String(expectedVersion) },
  })
}
