import { describe, expect, it, vi } from 'vitest'

const client = vi.hoisted(() => ({ apiRequest: vi.fn() }))
vi.mock('./apiClient', () => client)

import { getMarketOverview } from './marketApi'

describe('市场接口', () => {
  it('使用统一 REST Client 查询 CN 市场总览', async () => {
    const overview = { marketCode: 'CN' }
    client.apiRequest.mockResolvedValue(overview)

    await expect(getMarketOverview()).resolves.toBe(overview)
    expect(client.apiRequest).toHaveBeenCalledWith('/markets/overview?market=CN')
  })
})
