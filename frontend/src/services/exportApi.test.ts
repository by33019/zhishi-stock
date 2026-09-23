import { describe, expect, it, vi } from 'vitest'

const client = vi.hoisted(() => ({ apiRequest: vi.fn(), apiDownload: vi.fn() }))
vi.mock('./apiClient', () => client)

import { createExportJob, downloadExportFile, getExportJob } from './exportApi'

describe('导出接口', () => {
  it('EXP-01 用 POST、把幂等键放进请求头，并原样序列化请求体', async () => {
    client.apiRequest.mockResolvedValue({})

    await createExportJob(
      { exportType: 'STOCK_RANKING', filters: { rankingType: 'GAINERS', exchangeCodes: 'SH' } },
      'key-1',
    )

    expect(client.apiRequest).toHaveBeenCalledWith('/export-jobs', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'key-1' },
      body: JSON.stringify({
        exportType: 'STOCK_RANKING',
        filters: { rankingType: 'GAINERS', exchangeCodes: 'SH' },
      }),
    })
  })

  it('EXP-01 不传 columns 时不把空数组塞进请求体，由服务端套用默认列集', async () => {
    client.apiRequest.mockResolvedValue({})

    await createExportJob({ exportType: 'STOCK_RANKING', filters: { rankingType: 'LOSERS' } }, 'key-2')

    const body = JSON.parse(client.apiRequest.mock.calls[0]![1].body as string)
    expect(body).not.toHaveProperty('columns')
  })

  it('EXP-02 用 GET 查询本人作业状态', async () => {
    client.apiRequest.mockResolvedValue({})

    await getExportJob('7332365375356929')

    expect(client.apiRequest).toHaveBeenCalledWith('/export-jobs/7332365375356929')
  })

  it('EXP-03 走二进制下载通道，而不是 JSON 通道', async () => {
    client.apiDownload.mockResolvedValue({ blob: new Blob(), fileName: null, dataCutoffAt: null })

    await downloadExportFile('7332365375356929')

    expect(client.apiDownload).toHaveBeenCalledWith('/export-jobs/7332365375356929/download')
    // 走错通道的表现是拿 xlsx 字节去 JSON.parse，报一句与真实原因无关的解析错误。
    expect(client.apiRequest).not.toHaveBeenCalled()
  })
})
