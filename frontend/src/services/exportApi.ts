import { apiDownload, apiRequest } from './apiClient'
import type {
  ExportDownload,
  ExportJobAccepted,
  ExportJobRequest,
  ExportJobView,
} from '@/types/domain'

/**
 * EXP-01：创建导出任务（HTTP 202）。
 *
 * **`Idempotency-Key` 由调用方传入**，不在这里生成——与 `watchlistApi` / `aiApi`
 * 的同一条理由：键的语义是"一次用户意图"。在这个函数里 `randomUUID()` 的话，
 * `apiClient` 因 401 刷新而重发的那一次请求会带上**新的**键，
 * 于是服务端把它当成第二次导出——用户点一次按钮，得到两个作业，消耗两次限流。
 *
 * 返回的 202 只说明"已受理"：文件此刻还不存在，必须走 {@link getExportJob} 轮询。
 */
export function createExportJob(request: ExportJobRequest, idempotencyKey: string) {
  return apiRequest<ExportJobAccepted>('/export-jobs', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(request),
  })
}

/** EXP-02：查询本人导出任务状态。轮询期间被反复调用。 */
export function getExportJob(exportId: string) {
  return apiRequest<ExportJobView>(`/export-jobs/${encodeURIComponent(exportId)}`)
}

/**
 * EXP-03：下载已完成文件。
 *
 * 文件名与数据截止时间来自响应头（见 `apiDownload`），调用方**不要**自己拼一个：
 * 前端另算一份就会与服务端文件说明区里的数字分叉，而那种分叉在界面上看不出来。
 */
export function downloadExportFile(exportId: string): Promise<ExportDownload> {
  return apiDownload(`/export-jobs/${encodeURIComponent(exportId)}/download`)
}
