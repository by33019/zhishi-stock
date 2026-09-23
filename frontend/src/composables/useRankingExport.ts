import { computed, ref } from 'vue'

import { createExportJob, downloadExportFile, getExportJob } from '@/services/exportApi'
import type { ExportJobView, RankingExportFilters } from '@/types/domain'
import { saveBlob } from '@/utils/download'

/**
 * 导出按钮的生命周期。
 *
 * `QUEUED` 与 `RUNNING` 分开，是因为它们对用户是两句话：前者"排队中"（服务端还没开始，
 * 慢是正常的），后者"生成中 45%"（有进度可看）。合成一个"处理中"会让进度条永远没有依据。
 */
export type ExportPhase = 'IDLE' | 'QUEUED' | 'RUNNING' | 'DOWNLOADING'

export interface ExportFailure {
  message: string
  traceId?: string
}

export interface UseRankingExportOptions {
  /** 轮询间隔。服务端生成 2,000 行左右的榜单实测 2 秒内完成，1 秒足够且不至于打满服务端。 */
  pollIntervalMs?: number
  /** 轮询总时长上限。超过就放弃，但**不**取消服务端作业——它可能马上就完成了。 */
  pollTimeoutMs?: number
  /**
   * 轮询的等待函数，可注入。
   *
   * 留这个口子是给测试的：否则每个用例都要配假定时器，而假定时器与 `flushPromises`
   * 混用时，失败的用例报出来的是"超时"，看不出真正断言的是什么。
   */
  wait?: (ms: number) => Promise<void>
  /** 触发浏览器保存，可注入以便在测试里断言文件名与内容。 */
  save?: (blob: Blob, fileName: string) => void
}

/** 服务端未给 `error` 时的兜底文案。两种终态要用户做的事不同，所以不能共用一句话。 */
const TERMINAL_MESSAGES: Record<'FAILED' | 'EXPIRED', string> = {
  FAILED: '导出生成失败，请稍后重试',
  EXPIRED: '导出文件已超过保留期被清理，请重新导出',
}

function defaultWait(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}

/**
 * 行情榜单的 Excel 导出（契约 §9.2 EXP-01~EXP-03）。
 *
 * <h2>为什么是"创建 + 轮询"而不是一次请求直接拿文件</h2>
 * EXP-01 返回的是 **202 已受理**，文件此刻不存在。前端必须走 EXP-02 轮询到
 * `COMPLETED` 再走 EXP-03 下载。任何"点一下就当拿到文件"的实现都会在
 * 服务端真正开始异步生成之后失效——而失效的表现是下载到一个 404 或一个半成品。
 *
 * <h2>幂等键在一次 `run` 内保持不变</h2>
 * `apiClient` 遇到 401 会自动刷新令牌并**重发**同一个请求。若每次调用都用新的键，
 * 那一次自动重发在服务端就是**第二次导出**：用户点一次按钮，得到两个作业、被扣两次限流。
 * 所以键在这里生成一次，随本次 `run` 的请求一起走完。
 */
export function useRankingExport(options: UseRankingExportOptions = {}) {
  const pollIntervalMs = options.pollIntervalMs ?? 1000
  const pollTimeoutMs = options.pollTimeoutMs ?? 120_000
  const wait = options.wait ?? defaultWait
  const save = options.save ?? saveBlob

  /** 轮询次数上限。用次数而不是墙上时钟：注入的 `wait` 可能瞬间返回，时钟不会前进。 */
  const maxPolls = Math.max(1, Math.ceil(pollTimeoutMs / pollIntervalMs))

  const phase = ref<ExportPhase>('IDLE')
  const progress = ref(0)
  const failure = ref<ExportFailure>()

  const busy = computed(() => phase.value !== 'IDLE')

  let idempotencyKey = ''

  async function run(filters: RankingExportFilters): Promise<void> {
    // 连点两次不该产生第二个作业——按钮虽然会禁用，但双击在禁用生效前就能发出两次事件。
    if (busy.value) return
    failure.value = undefined
    progress.value = 0
    phase.value = 'QUEUED'
    idempotencyKey = crypto.randomUUID()

    try {
      // 不传 `columns`：由服务端套用默认列集（13 列）。前端抄一份列清单就意味着
      // 服务端加一列时导出文件里不会有它，而两边各自看都正常。
      const accepted = await createExportJob(
        { exportType: 'STOCK_RANKING', filters },
        idempotencyKey,
      )
      const job = await poll(accepted.exportId)

      phase.value = 'DOWNLOADING'
      const file = await downloadExportFile(accepted.exportId)
      // 文件名优先用响应头给的那个：它是服务端算好的（含口径与批次时间戳），
      // 与文件说明区里写的口径天然一致。`job.fileName` 只是次选。
      save(file.blob, file.fileName ?? job.fileName ?? 'stock-ranking.xlsx')
    } catch (cause) {
      const error = cause as { message?: string; traceId?: string }
      failure.value = {
        message: error?.message ?? '导出失败，请稍后重试',
        traceId: error?.traceId,
      }
    } finally {
      phase.value = 'IDLE'
      progress.value = 0
    }
  }

  async function poll(exportId: string): Promise<ExportJobView> {
    for (let attempt = 0; attempt < maxPolls; attempt += 1) {
      const job = await getExportJob(exportId)
      progress.value = job.progress

      if (job.status === 'COMPLETED') return job
      if (job.status === 'FAILED' || job.status === 'EXPIRED') {
        throw new Error(job.error ?? TERMINAL_MESSAGES[job.status])
      }

      phase.value = 'RUNNING'
      await wait(pollIntervalMs)
    }
    throw new Error('导出等待超时，请稍后重试')
  }

  return { phase, progress, failure, busy, run }
}
