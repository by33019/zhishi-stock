import { describe, expect, it } from 'vitest'

import { getMarketOverview, getNews } from './mockApi'

describe('Mock API 契约', () => {
  it('返回与统一 REST 响应一致的市场数据', async () => {
    const response = await getMarketOverview()

    expect(response.success).toBe(true)
    expect(response.code).toBe('SUCCESS')
    expect(response.data.indices.length).toBeGreaterThan(2)
    expect(response.data.dataStatus).toBe('REALTIME')
    expect(response.traceId).toMatch(/^mock-/)
  })

  it('保持 Snowflake ID 为字符串并提供资讯来源', async () => {
    const response = await getNews()

    expect(response.data.length).toBeGreaterThan(0)
    expect(typeof response.data[0]?.newsId).toBe('string')
    expect(response.data[0]?.sourceName).toBeTruthy()
  })
})
