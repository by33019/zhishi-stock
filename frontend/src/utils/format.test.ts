import { describe, expect, it } from 'vitest'

import { formatChangeRate, formatDateTime, formatMoney, formatVolume, trendClass } from './format'

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

describe('行情时间格式化', () => {
  it('固定按北京时间渲染，不随运行环境时区漂移', () => {
    // 同一时刻的两种等价写法必须渲染成同一个结果
    expect(formatDateTime('2026-09-13T14:32:00+08:00')).toBe('09/13 14:32')
    expect(formatDateTime('2026-09-13T06:32:00Z')).toBe('09/13 14:32')
  })

  it('跨日时刻按北京时间归日，而不是按 UTC 归日', () => {
    // UTC 的 9/12 20:00 已经是北京时间 9/13 04:00，必须归到 13 日
    expect(formatDateTime('2026-09-12T20:00:00Z')).toBe('09/13 04:00')
    // 反向边界：北京时间 9/13 00:00 恰好是 UTC 9/12 16:00
    expect(formatDateTime('2026-09-12T16:00:00Z')).toBe('09/13 00:00')
  })
})
