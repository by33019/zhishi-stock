/**
 * 后台管理面（M3-11）的契约类型，对齐 `docs/RESTful-API.md` §16~§20。
 *
 * 后台字段命名与后端 JSON 一致；业务 ID 在后台面按 number 处理
 * （后台 ID 段在 JS 安全整数范围内），与前台业务面"全程 string"的约定不同源。
 */

// ---------- 用户 / 角色 / 日志（§16 / §20） ----------

export type AdminUserStatus = 'ACTIVE' | 'LOCKED' | 'DISABLED'

export interface AdminRoleRef {
  roleId: number
  roleName: string
}

export interface AdminUserSummary {
  userId: number
  username: string
  nickName: string
  status: AdminUserStatus
  isSuperAdmin: boolean
  roleCount: number
  lastLoginTime?: string
  createdAt: string
}

export interface AdminUserDetail extends AdminUserSummary {
  maskedEmail?: string
  maskedPhone?: string
  realName?: string
  tokenVersion: number
  version: number
  roles: AdminRoleRef[]
  updatedAt: string
}

export interface AdminUserPage extends PageDataShell<AdminUserSummary> {}

export interface AdminRoleSummary {
  roleId: number
  name: string
  description: string
  status: number
  userCount: number
  permissionCount: number
  version: number
}

export interface AdminOperationLogSummary {
  logId: number
  userId?: number
  username?: string
  operation: string
  durationMillis?: number
  requestUri: string
  httpMethod?: string
  resultStatus: string
  ip?: string
  traceId?: string
  createdAt: string
}

export interface AdminOperationLogDetail extends AdminOperationLogSummary {
  method?: string
  paramsSummary?: string
  legacyUserRef?: string
}

// ---------- 任务执行（§18.3） ----------

export type JobExecutionStatus =
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'PARTIALLY_SUCCEEDED'
  | 'CANCELED'

export type JobTriggerType = 'MANUAL' | 'SCHEDULED' | 'RETRY'

export interface JobDefinition {
  jobName: string
  displayName: string
  handlerName: string
  scheduleDescription: string
  supportsManualTrigger: boolean
  supportsShard: boolean
  enabled: boolean
  scopeKeyLabel?: string
  allowedScopeKeys?: string[]
  defaultScopeKey?: string
}

export interface JobExecutionCounts {
  inputCount?: number
  successCount?: number
  skippedCount?: number
  failureCount?: number
  outputCount?: number
  countsAvailable: boolean
}

export interface JobExecution {
  executionId: number
  jobName: string
  handlerName: string
  batchId: string
  shardIndex: number
  shardTotal: number
  attemptNo: number
  status: JobExecutionStatus
  triggerType: JobTriggerType
  scopeKey?: string
  counts?: JobExecutionCounts
  scheduledAt?: string
  startedAt?: string
  completedAt?: string
  errorCategory?: string
  errorCode?: string
  errorMessage?: string
  traceId?: string
}

// ---------- 资讯治理（§17） ----------

export type NewsSourceType = 'MEDIA' | 'EXCHANGE' | 'COMPANY' | 'REGULATOR'
export type NewsAuthorizationStatus = 'AUTHORIZED' | 'EXPIRED' | 'SUSPENDED' | 'UNKNOWN'
export type NewsSourceStatus = 'ACTIVE' | 'DEGRADED' | 'DISABLED'
export type NewsRelationStatus = 'CONFIRMED' | 'CANDIDATE' | 'REJECTED'
export type NewsRelationMethod = 'EXPLICIT' | 'RULE' | 'MODEL' | 'MANUAL'
export type NewsTargetType = 'SECURITY' | 'SECTOR' | 'MARKET'

export interface AdminNewsSource {
  sourceId: number
  sourceCode: string
  sourceName: string
  sourceType: NewsSourceType
  homepageUrl?: string
  authorizationStatus: NewsAuthorizationStatus
  rightsValidFrom?: string
  rightsValidTo?: string
  allowAiAnalysis: boolean
  status: NewsSourceStatus
  lastSuccessAt?: string
  lastFailureAt?: string
  version: number
}

export interface CreateNewsSourcePayload {
  providerId?: number
  sourceCode: string
  sourceName: string
  sourceType: NewsSourceType
  homepageUrl?: string
  rightsValidFrom?: string
  rightsValidTo?: string
  allowAiAnalysis?: boolean
  status?: NewsSourceStatus
}

export interface PatchNewsSourcePayload {
  sourceName?: string
  homepageUrl?: string
  rightsValidFrom?: string
  rightsValidTo?: string
  allowAiAnalysis?: boolean
  authorizationStatus?: NewsAuthorizationStatus
  status?: NewsSourceStatus
}

export interface AdminNewsRelation {
  relationId: number
  newsId: number
  newsTitle: string
  targetType: NewsTargetType
  targetId: string
  targetCode?: string
  targetName?: string
  relationMethod: NewsRelationMethod
  confidenceScore?: number
  relationStatus: NewsRelationStatus
  reasonSummary?: string
  reviewedBy?: number
  reviewedAt?: string
  createdAt: string
}

// ---------- AI 运营（§19） ----------

export type AiTaskStatus =
  | 'CREATED'
  | 'PREPARING'
  | 'QUEUED'
  | 'RUNNING'
  | 'VALIDATING'
  | 'COMPLETED'
  | 'CANCELED'
  | 'FAILED'
  | 'TIMED_OUT'

export interface AdminAiTaskTargetSummary {
  targetType: NewsTargetType
  targetCode: string
  targetName: string
  targetRole: string
}

export interface AdminAiTaskSummary {
  taskId: number
  sessionId: number
  userId: number
  scene: string
  status: AiTaskStatus
  providerCode?: string
  modelCode?: string
  createdAt: string
  startedAt?: string
  completedAt?: string
  errorCategory?: string
  traceId?: string
  targets: AdminAiTaskTargetSummary[]
}

export interface AdminAiUsageAttempt {
  attemptNo: number
  resultStatus: string
  providerCode?: string
  modelCode?: string
  promptTokens: number
  completionTokens: number
  cachedTokens: number
  totalTokens: number
  estimatedCost: number
  firstChunkLatencyMillis?: number
  totalLatencyMillis?: number
}

export interface AdminAiTaskDetail extends AdminAiTaskSummary {
  attemptNo: number
  maxAttempts: number
  retryOfTaskId?: number
  cancelRequested: boolean
  queuedAt?: string
  firstChunkAt?: string
  validatingAt?: string
  deadlineAt?: string
  errorCode?: string
  errorMessage?: string
  usageByAttempt: AdminAiUsageAttempt[]
  contextTypeCounts: Record<string, number>
}

export interface AdminAiProviderStat {
  providerCode?: string
  modelCode?: string
  calls: number
  successRate?: number
  avgFirstChunkLatencyMillis?: number
  avgTotalLatencyMillis?: number
}

export interface AdminAiOverview {
  taskCount: number
  succeededCount: number
  failedCount: number
  canceledCount: number
  timedOutCount: number
  successRate?: number
  restrictedReportCount: number
  queuedCount: number
  runningCount: number
  firstChunkLatencyP50Millis?: number
  firstChunkLatencyP95Millis?: number
  totalLatencyP50Millis?: number
  totalLatencyP95Millis?: number
  promptTokens: number
  completionTokens: number
  cachedTokens: number
  totalTokens: number
  estimatedCost: number
  providers: AdminAiProviderStat[]
}

export interface AdminAiUsageGroup {
  groupKey: string
  calls: number
  successCalls: number
  successRate?: number
  promptTokens: number
  completionTokens: number
  cachedTokens: number
  totalTokens: number
  estimatedCost: number
  avgFirstChunkLatencyMillis?: number
  avgTotalLatencyMillis?: number
}

export interface AdminAiFeedbackStats {
  total: number
  helpfulCount: number
  notHelpfulCount: number
  helpfulRate?: number
  reasonCounts: { reasonCode?: string; count: number }[]
  dailyTrend: { day: string; count: number }[]
}

// ---------- 分页壳 ----------

export interface PageDataShell<T> {
  items: T[]
  page: number
  size: number
  total: number
  totalPages: number
  hasNext: boolean
}
