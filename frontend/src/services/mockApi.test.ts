import { describe, expect, it } from 'vitest'

import { getMarketOverview, getStockDetail } from './mockApi'

describe('Mock API 契约', () => {
  it('返回与统一 REST 响应一致的市场数据', async () => {
    const response = await getMarketOverview()

    expect(response.success).toBe(true)
    expect(response.code).toBe('SUCCESS')
    expect(response.data.indices.length).toBeGreaterThan(2)
    expect(response.data.dataStatus).toBe('REALTIME')
    expect(response.traceId).toMatch(/^mock-/)
  })

  it('保持 Snowflake ID 为字符串并提供 AI 可核验上下文', async () => {
    const response = await getStockDetail('19876543210001')

    expect(typeof response.data.securityId).toBe('string')
    expect(response.data.news[0]?.sourceName).toBeTruthy()
    expect(response.data.aiPrompts).toContain('异动解读')
  })
})
