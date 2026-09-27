import { apiRequest, toQueryString } from './apiClient'
import type {
  AdminAiFeedbackStats,
  AdminAiOverview,
  AdminAiTaskDetail,
  AdminAiTaskSummary,
  AdminAiUsageGroup,
  AdminNewsRelation,
  AdminNewsSource,
  AdminOperationLogDetail,
  AdminOperationLogSummary,
  AdminRoleSummary,
  AdminUserDetail,
  AdminUserPage,
  CreateNewsSourcePayload,
  JobDefinition,
  JobExecution,
  PageDataShell,
  PatchNewsSourcePayload,
} from '@/types/admin'

/**
 * 后台管理面（M3-11）的 API 层，对齐契约 §16~§20。
 *
 * 与前台业务面同一个约定：
 * - **写操作的 `Idempotency-Key` 由页面传入**（键的语义是"一次用户意图"，
 *   重试复用、换意图换新键——service 里 randomUUID 会让重试变成新意图）；
 * - 响应壳由 `apiRequest` 统一解开，业务失败抛 `ApiError`（含业务码与 traceId）；
 * - 分页参数直接透传，`toQueryString` 丢弃空值。
 */

const admin = (path: string) => `/admin${path}`

// ---------- 用户管理（ADM-USR-01~09） ----------

/** ADM-USR-01：用户分页。 */
export function getAdminUsers(query: {
  keyword?: string
  status?: string
  page?: number
  size?: number
} = {}) {
  const search = toQueryString(query)
  return apiRequest<AdminUserPage>(`${admin('/users')}?${search}`)
}

/** ADM-USR-02：用户详情。 */
export function getAdminUser(userId: number) {
  return apiRequest<AdminUserDetail>(admin(`/users/${userId}`))
}

/** ADM-USR-03：创建用户（幂等）。 */
export function createAdminUser(
  payload: {
    username: string
    nickName?: string
    email?: string
    phone?: string
    roleIds?: number[]
  },
  idempotencyKey: string,
) {
  return apiRequest<AdminUserDetail>(admin('/users'), {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(payload),
  })
}

/** ADM-USR-04：修改资料（If-Match 乐观锁）。 */
export function patchAdminUser(
  userId: number,
  expectedVersion: number,
  payload: { nickName?: string; email?: string; phone?: string },
) {
  return apiRequest<AdminUserDetail>(admin(`/users/${userId}`), {
    method: 'PATCH',
    headers: { 'If-Match': String(expectedVersion) },
    body: JSON.stringify(payload),
  })
}

/** ADM-USR-05：启用 / 锁定（If-Match 乐观锁）。 */
export function changeAdminUserStatus(
  userId: number,
  expectedVersion: number,
  status: 'ACTIVE' | 'LOCKED',
  reason?: string,
) {
  return apiRequest<AdminUserDetail>(admin(`/users/${userId}/status`), {
    method: 'PATCH',
    headers: { 'If-Match': String(expectedVersion) },
    body: JSON.stringify({ status, reason }),
  })
}

/** ADM-USR-06：整体替换用户角色。 */
export function replaceAdminUserRoles(userId: number, roleIds: number[]) {
  return apiRequest<AdminUserDetail>(admin(`/users/${userId}/roles`), {
    method: 'PUT',
    body: JSON.stringify({ roleIds }),
  })
}

/** ADM-USR-07：签发一次性密码重置凭证（幂等）。 */
export function resetAdminUserPassword(userId: number, idempotencyKey: string) {
  return apiRequest<{ userId: number; delivery: string; maskedTarget?: string; expiresAt: string }>(
    admin(`/users/${userId}/password-reset`),
    {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ delivery: 'EMAIL' }),
    },
  )
}

/** ADM-USR-08：强制下线（幂等）。 */
export function revokeAdminUserSessions(userId: number, idempotencyKey: string, reason?: string) {
  return apiRequest<{ userId: number; revokedSessions: number }>(
    admin(`/users/${userId}/sessions/revoke`),
    {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ reason }),
    },
  )
}

/** ADM-USR-09：删除用户（If-Match 乐观锁；不能删自己、不能删最后一个超管）。 */
export function deleteAdminUser(userId: number, expectedVersion: number, reason?: string) {
  return apiRequest<void>(admin(`/users/${userId}`), {
    method: 'DELETE',
    headers: { 'If-Match': String(expectedVersion) },
    body: JSON.stringify({ reason }),
  })
}

// ---------- 角色 / 操作日志（ADM-ROL-01 / LOG-01~02） ----------

/** ADM-ROL-01：角色列表（只读）。 */
export function getAdminRoles(query: { page?: number; size?: number } = {}) {
  return apiRequest<PageDataShell<AdminRoleSummary>>(`${admin('/roles')}?${toQueryString(query)}`)
}

/** LOG-01：操作日志分页（缺省最近 7 天，上限 90 天）。 */
export function getAdminOperationLogs(query: {
  userId?: number
  username?: string
  operation?: string
  resultStatus?: string
  page?: number
  size?: number
} = {}) {
  return apiRequest<PageDataShell<AdminOperationLogSummary>>(
    `${admin('/operation-logs')}?${toQueryString(query)}`,
  )
}

/** LOG-02：操作日志详情。 */
export function getAdminOperationLog(logId: number) {
  return apiRequest<AdminOperationLogDetail>(admin(`/operation-logs/${logId}`))
}

// ---------- 定时任务（ADM-JOB-01~05） ----------

/** ADM-JOB-01：白名单任务定义。 */
export function getAdminJobDefinitions() {
  return apiRequest<JobDefinition[]>(admin('/job-definitions'))
}

/** ADM-JOB-02：人工触发（202，返回 RUNNING 记录摘要）。 */
export function triggerAdminJob(
  jobName: string,
  payload: { scopeKey?: string; reason?: string },
  idempotencyKey: string,
) {
  return apiRequest<JobExecution>(admin(`/job-definitions/${jobName}/executions`), {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(payload),
  })
}

/** ADM-JOB-03：执行历史分页（按开始时间倒序）。 */
export function getAdminJobExecutions(query: {
  jobName?: string
  status?: string
  triggerType?: string
  page?: number
  size?: number
} = {}) {
  return apiRequest<PageDataShell<JobExecution>>(
    `${admin('/job-executions')}?${toQueryString(query)}`,
  )
}

/** ADM-JOB-04：执行详情。 */
export function getAdminJobExecution(executionId: number) {
  return apiRequest<JobExecution>(admin(`/job-executions/${executionId}`))
}

/** ADM-JOB-05：重试失败或部分失败的执行（202）。 */
export function retryAdminJobExecution(
  executionId: number,
  payload: { reason?: string },
  idempotencyKey: string,
) {
  return apiRequest<JobExecution>(admin(`/job-executions/${executionId}/retries`), {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(payload),
  })
}

// ---------- 资讯治理（ADM-NEWS-01~08） ----------

/** ADM-NEWS-01：来源分页。 */
export function getAdminNewsSources(query: {
  sourceType?: string
  authorizationStatus?: string
  status?: string
  page?: number
  size?: number
} = {}) {
  return apiRequest<PageDataShell<AdminNewsSource>>(
    `${admin('/news-sources')}?${toQueryString(query)}`,
  )
}

/** ADM-NEWS-02：来源详情。 */
export function getAdminNewsSource(sourceId: number) {
  return apiRequest<AdminNewsSource>(admin(`/news-sources/${sourceId}`))
}

/** ADM-NEWS-03：创建来源（幂等；授权状态由服务端从授权区间推导）。 */
export function createAdminNewsSource(payload: CreateNewsSourcePayload, idempotencyKey: string) {
  return apiRequest<AdminNewsSource>(admin('/news-sources'), {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(payload),
  })
}

/** ADM-NEWS-04：修改来源（If-Match 乐观锁）。 */
export function patchAdminNewsSource(
  sourceId: number,
  expectedVersion: number,
  payload: PatchNewsSourcePayload,
) {
  return apiRequest<AdminNewsSource>(admin(`/news-sources/${sourceId}`), {
    method: 'PATCH',
    headers: { 'If-Match': String(expectedVersion) },
    body: JSON.stringify(payload),
  })
}

/** ADM-NEWS-05：关联分页（缺省看 CANDIDATE）。 */
export function getAdminNewsRelations(query: {
  relationStatus?: string
  targetType?: string
  newsId?: number
  minConfidence?: number
  page?: number
  size?: number
} = {}) {
  return apiRequest<PageDataShell<AdminNewsRelation>>(
    `${admin('/news-relations')}?${toQueryString(query)}`,
  )
}

/** ADM-NEWS-06：人工复核候选关联。 */
export function reviewAdminNewsRelation(
  relationId: number,
  relationStatus: 'CONFIRMED' | 'REJECTED',
  reasonSummary?: string,
) {
  return apiRequest<AdminNewsRelation>(admin(`/news-relations/${relationId}`), {
    method: 'PATCH',
    body: JSON.stringify({ relationStatus, reasonSummary }),
  })
}

/** ADM-NEWS-07：手工建立关联（幂等）。 */
export function createAdminNewsRelation(
  newsId: number,
  payload: { targetType: string; targetId: string; reasonSummary?: string },
  idempotencyKey: string,
) {
  return apiRequest<AdminNewsRelation>(admin(`/news/${newsId}/relations`), {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(payload),
  })
}

/** ADM-NEWS-08：删除关联 = 置 REJECTED（保留审计）。 */
export function deleteAdminNewsRelation(relationId: number, reasonSummary?: string) {
  return apiRequest<AdminNewsRelation>(admin(`/news-relations/${relationId}`), {
    method: 'DELETE',
    body: JSON.stringify({ reasonSummary }),
  })
}

// ---------- AI 运营（ADM-AI-01~06） ----------

/** ADM-AI-01：运营总览。窗口缺省 = 全部时间。 */
export function getAdminAiOverview(query: { startAt?: string; endAt?: string } = {}) {
  return apiRequest<AdminAiOverview>(`${admin('/ai/overview')}?${toQueryString(query)}`)
}

/** ADM-AI-02：任务元数据分页。 */
export function getAdminAiTasks(query: {
  userId?: number
  scene?: string
  status?: string
  providerCode?: string
  errorCategory?: string
  page?: number
  size?: number
} = {}) {
  return apiRequest<PageDataShell<AdminAiTaskSummary>>(
    `${admin('/ai/tasks')}?${toQueryString(query)}`,
  )
}

/** ADM-AI-03：任务详情（只有元数据，没有正文）。 */
export function getAdminAiTask(taskId: number) {
  return apiRequest<AdminAiTaskDetail>(admin(`/ai/tasks/${taskId}`))
}

/** ADM-AI-04：取消任务（幂等；终态任务返回 409）。 */
export function cancelAdminAiTask(taskId: number, idempotencyKey: string, reason?: string) {
  return apiRequest<AdminAiTaskDetail>(admin(`/ai/tasks/${taskId}/cancel`), {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ reason }),
  })
}

/** ADM-AI-05：分组用量。 */
export function getAdminAiUsage(query: {
  groupBy?: 'DAY' | 'PROVIDER' | 'MODEL' | 'SCENE'
  startAt?: string
  endAt?: string
  providerCode?: string
  modelCode?: string
} = {}) {
  return apiRequest<AdminAiUsageGroup[]>(`${admin('/ai/usage')}?${toQueryString(query)}`)
}

/** ADM-AI-06：反馈统计。 */
export function getAdminAiFeedbackStatistics(query: { startAt?: string; endAt?: string } = {}) {
  return apiRequest<AdminAiFeedbackStats>(
    `${admin('/ai/feedback-statistics')}?${toQueryString(query)}`,
  )
}
