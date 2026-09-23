import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  createExportJob: vi.fn(),
  getExportJob: vi.fn(),
  downloadExportFile: vi.fn(),
}))
vi.mock('@/services/exportApi', () => api)

const download = vi.hoisted(() => ({ saveBlob: vi.fn() }))
vi.mock('@/utils/download', () => download)

import { useRankingExport, type ExportPhase } from './useRankingExport'
import type { ExportJobView } from '@/types/domain'

function job(overrides: Partial<ExportJobView> = {}): ExportJobView {
  return {
    exportId: '7332',
    exportType: 'STOCK_RANKING',
    status: 'COMPLETED',
    progress: 100,
    fileName: 'stock-ranking-gainers-20260923.xlsx',
    rowCount: 2079,
    createdAt: '2026-09-23T09:52:37+08:00',
    expiresAt: '2026-09-24T09:52:37+08:00',
    error: null,
    ...overrides,
  }
}

/** 还没跑完的作业：`fileName` / `rowCount` 都是 `null`，服务端不编造。 */
function pending(status: 'QUEUED' | 'RUNNING', progress: number): ExportJobView {
  return job({ status, progress, fileName: null, rowCount: null })
}

function setup(pollTimeoutMs = 60_000) {
  return useRankingExport({
    // 轮询等待注入为立即返回：用例不从真实时间里推结论，失败时报出来的也是真断言。
    wait: () => Promise.resolve(),
    save: download.saveBlob,
    pollIntervalMs: 1,
    pollTimeoutMs,
  })
}

describe('榜单导出', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('创建后轮询到 COMPLETED 再下载，文件名用响应头给的那个', async () => {
    const blob = new Blob(['xlsx'])
    api.createExportJob.mockResolvedValue({ exportId: '7332' })
    api.getExportJob.mockResolvedValue(job())
    api.downloadExportFile.mockResolvedValue({
      blob,
      fileName: '榜单-20260923.xlsx',
      dataCutoffAt: '2026-09-23T15:00+08:00',
    })

    const exporter = setup()
    await exporter.run({ rankingType: 'GAINERS' })

    expect(api.createExportJob).toHaveBeenCalledWith(
      { exportType: 'STOCK_RANKING', filters: { rankingType: 'GAINERS' } },
      expect.any(String),
    )
    expect(api.downloadExportFile).toHaveBeenCalledWith('7332')
    expect(download.saveBlob).toHaveBeenCalledWith(blob, '榜单-20260923.xlsx')
    expect(exporter.failure.value).toBeUndefined()
    expect(exporter.busy.value).toBe(false)
  })

  it('未完成时继续轮询，中间的服务端进度确实被推到了界面上', async () => {
    api.createExportJob.mockResolvedValue({ exportId: '7332' })
    api.getExportJob
      .mockResolvedValueOnce(pending('QUEUED', 0))
      .mockResolvedValueOnce(pending('RUNNING', 40))
      .mockResolvedValue(job())

    const observed: { phase: ExportPhase; progress: number }[] = []
    const exporter = useRankingExport({
      pollIntervalMs: 1,
      save: download.saveBlob,
      wait: () => {
        observed.push({ phase: exporter.phase.value, progress: exporter.progress.value })
        return Promise.resolve()
      },
    })
    api.downloadExportFile.mockResolvedValue({ blob: new Blob(), fileName: null, dataCutoffAt: null })

    await exporter.run({ rankingType: 'GAINERS' })

    expect(api.getExportJob).toHaveBeenCalledTimes(3)
    expect(observed).toEqual([
      { phase: 'RUNNING', progress: 0 },
      { phase: 'RUNNING', progress: 40 },
    ])
  })

  it('FAILED 时用服务端给的 error 文案，且不去下载一个不存在的文件', async () => {
    api.createExportJob.mockResolvedValue({ exportId: '7332' })
    api.getExportJob.mockResolvedValue(job({
      status: 'FAILED',
      progress: 30,
      fileName: null,
      rowCount: null,
      error: '榜单数据源不可用',
    }))

    const exporter = setup()
    await exporter.run({ rankingType: 'GAINERS' })

    expect(exporter.failure.value?.message).toBe('榜单数据源不可用')
    expect(api.downloadExportFile).not.toHaveBeenCalled()
    expect(download.saveBlob).not.toHaveBeenCalled()
  })

  it('EXPIRED 且服务端没给 error 时，提示的是"重新导出"而不是"重试"', async () => {
    api.createExportJob.mockResolvedValue({ exportId: '7332' })
    api.getExportJob.mockResolvedValue(job({
      status: 'EXPIRED',
      fileName: null,
      rowCount: null,
      error: null,
    }))

    const exporter = setup()
    await exporter.run({ rankingType: 'GAINERS' })

    expect(exporter.failure.value?.message).toContain('重新导出')
    expect(api.downloadExportFile).not.toHaveBeenCalled()
  })

  it('轮询到上限就放弃，并把状态与进度收干净', async () => {
    api.createExportJob.mockResolvedValue({ exportId: '7332' })
    api.getExportJob.mockResolvedValue(pending('RUNNING', 60))

    const exporter = setup(3)
    await exporter.run({ rankingType: 'GAINERS' })

    expect(api.getExportJob).toHaveBeenCalledTimes(3)
    expect(exporter.failure.value?.message).toContain('超时')
    expect(exporter.progress.value).toBe(0)
    expect(exporter.busy.value).toBe(false)
  })

  it('两次导出用两个不同的幂等键——第二次是新的用户意图，不能被服务端当成重放', async () => {
    api.createExportJob.mockResolvedValue({ exportId: '7332' })
    api.getExportJob.mockResolvedValue(job())
    api.downloadExportFile.mockResolvedValue({ blob: new Blob(), fileName: null, dataCutoffAt: null })

    const exporter = setup()
    await exporter.run({ rankingType: 'GAINERS' })
    await exporter.run({ rankingType: 'GAINERS' })

    const keys = api.createExportJob.mock.calls.map((call) => call[1])
    expect(keys[0]).toBeTruthy()
    expect(keys[0]).not.toBe(keys[1])
  })

  it('正在导出时再次点击不会产生第二个作业', async () => {
    let release: (value: ExportJobView) => void = () => {}
    api.createExportJob.mockResolvedValue({ exportId: '7332' })
    api.downloadExportFile.mockResolvedValue({ blob: new Blob(), fileName: null, dataCutoffAt: null })
    api.getExportJob.mockImplementation(
      () => new Promise<ExportJobView>((resolve) => {
        release = resolve
      }),
    )

    const exporter = setup()
    const inFlight = exporter.run({ rankingType: 'GAINERS' })
    await Promise.resolve()
    await exporter.run({ rankingType: 'GAINERS' })

    expect(api.createExportJob).toHaveBeenCalledTimes(1)

    release(job())
    await inFlight
  })

  it('下载失败也要把失败原因与追踪编号交出来', async () => {
    api.createExportJob.mockResolvedValue({ exportId: '7332' })
    api.getExportJob.mockResolvedValue(job())
    api.downloadExportFile.mockRejectedValue({
      code: 'EXPORT_EXPIRED',
      message: '导出文件已过期',
      traceId: 'trace-409',
    })

    const exporter = setup()
    await exporter.run({ rankingType: 'GAINERS' })

    expect(exporter.failure.value).toEqual({ message: '导出文件已过期', traceId: 'trace-409' })
  })
})
