import { describe, expect, it } from 'vitest'

import { getNews } from './mockApi'

describe('Mock API 契约', () => {
  it('保持 Snowflake ID 为字符串并提供资讯来源', async () => {
    const response = await getNews()

    expect(response.success).toBe(true)
    expect(response.code).toBe('SUCCESS')
    expect(response.traceId).toMatch(/^mock-/)
    expect(response.data.length).toBeGreaterThan(0)
    expect(typeof response.data[0]?.newsId).toBe('string')
    expect(response.data[0]?.sourceName).toBeTruthy()
  })
})
