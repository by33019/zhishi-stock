import { describe, expect, it } from 'vitest'

import { formatChangeRate, formatMoney, formatVolume, trendClass } from './format'

describe('行情格式化', () => {
  it('按 A 股习惯格式化涨跌幅并保留方向', () => {
    expect(formatChangeRate('0.0215')).toBe('+2.15%')
    expect(formatChangeRate('-0.008')).toBe('-0.80%')
    expect(formatChangeRate(null)).toBe('--')
  })

  it('以中文数量级展示成交额和成交量', () => {
    expect(formatMoney('102300000000')).toBe('1,023.00亿')
    expect(formatVolume('328000000')).toBe('3.28亿股')
  })

  it('同时提供文字类名而不是只依赖颜色', () => {
    expect(trendClass('0.01')).toBe('trend-up')
    expect(trendClass('-0.01')).toBe('trend-down')
    expect(trendClass('0')).toBe('trend-flat')
  })
})
