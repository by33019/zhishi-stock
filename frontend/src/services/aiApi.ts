import { apiRequest } from './apiClient'
import type {
  AiContextPreview,
  AiContextTarget,
  AiReportDetail,
  AiReportEvidence,
  AiScene,
  AiSceneDefinition,
  AiTaskAccepted,
  AiTaskSummary,
} from '@/types/domain'

/** AI-01：可用场景目录。场景的取值与约束全部来自服务端，前端不硬编码枚举。 */
export function getScenes() {
  return apiRequest<AiSceneDefinition[]>('/ai/scenes')
}

/**
 * AI-02：上下文预览——**不消耗配额**，用于在正式提交前展示将用到哪些数据。
 *
 * 它回答两件事：`canGenerate`（核心行情齐备吗）与 `limitations`（有哪些数据缺口）。
 * 界面必须把 `limitations` 显示出来：报告确实会因为缺资讯而变成受限分析，
 * 提前说清楚比事后在报告里打一个 LIMITED 标记要友好得多。
 */
export function previewContext(request: {
  scene: AiScene
  targets: AiContextTarget[]
  analysisStartAt?: string
  analysisEndAt?: string
}) {
  return apiRequest<AiContextPreview>('/ai/context-previews', {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

/**
 * AI-03：创建分析任务（202）。
 *
 * `Idempotency-Key` **由调用方生成并持有**：它的语义是"一次用户意图"——
 * 重试要复用同一个键（否则会重复消耗额度），而改了目标或问题就是新的意图、
 * 要换一个新键。这个判断只有页面做得了，所以键不从服务层生成。
 */
export function createTask(
  request: {
    scene: AiScene
    targets: AiContextTarget[]
    question?: string
    sessionId?: string
    analysisStartAt?: string
    analysisEndAt?: string
  },
  idempotencyKey: string,
) {
  return apiRequest<AiTaskAccepted>('/ai/tasks', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(request),
  })
}

/** AI-04：查询任务状态。终态时 `reportId` 才有值。 */
export function getTask(taskId: string) {
  return apiRequest<AiTaskSummary>(`/ai/tasks/${encodeURIComponent(taskId)}`)
}

/**
 * HIS-06：取报告正文。
 *
 * 报告与任务**分开取**：任务摘要在跑完后就不再变化，而报告是独立资源。
 * 把它们合成一个请求会让"任务已完成但报告还没读到"变成一个无法表达的状态。
 */
export function getReport(reportId: string) {
  return apiRequest<AiReportDetail>(`/ai/reports/${encodeURIComponent(reportId)}`)
}

/**
 * HIS-07：取报告引用的来源。
 *
 * 与 HIS-06 分开取的理由与上面相反：来源是引用栏才需要的东西，
 * 而引用栏**不是一打开报告就要看的**（用户先读结论，需要核对时才展开）。
 * 合进报告详情会让每次都多付一次查询，且报告详情的响应体随引用数增长。
 *
 * 与报告正文里的 `[n]` 配合使用：`evidenceNo` 就是正文里的那个编号。
 */
export function getReportEvidence(reportId: string, evidenceType?: string) {
  const search = evidenceType ? `?evidenceType=${encodeURIComponent(evidenceType)}` : ''
  return apiRequest<AiReportEvidence[]>(
    `/ai/reports/${encodeURIComponent(reportId)}/evidence${search}`,
  )
}
