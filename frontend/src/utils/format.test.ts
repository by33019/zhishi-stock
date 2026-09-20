import { describe, expect, it } from 'vitest'

import {
  formatChangeRate,
  formatDate,
  formatDateTime,
  formatMoney,
  formatTime,
  formatVolume,
  trendClass,
} from './format'

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

  it('契约里可为空的数据截止时间渲染为占位符而不是 Invalid Date', () => {
    // dataTime 在契约中可空（板块全停牌、快照缺失）；直接 new Date(null) 会渲染成
    // "Invalid Date"，那串字会出现在页面上。
    expect(formatDateTime(null)).toBe('--')
    expect(formatDateTime('')).toBe('--')
    expect(formatDateTime('不是时间')).toBe('--')
  })
})

describe('纯日期与纯时刻格式化', () => {
  it('纯日期字段不渲染出 00:00', () => {
    // tradeDate 是 date 类型，用 formatDateTime 会变成 "09/19 00:00"，
    // 让人以为存在一个 00:00 的数据时刻
    expect(formatDate('2026-09-19')).toBe('09/19')
  })

  it('纯日期同样钉住北京时间，不按 UTC 归日', () => {
    // `new Date('2026-09-19')` 按 UTC 零点解析；跟随运行环境时区会显示成 09/18
    expect(formatDate('2026-09-19')).toBe('09/19')
    expect(formatDate('2026-09-19T00:00:00+08:00')).toBe('09/19')
  })

  it('时刻只保留 HH:mm，且同样钉住北京时间', () => {
    expect(formatTime('2026-09-13T14:32:00+08:00')).toBe('14:32')
    expect(formatTime('2026-09-13T06:32:00Z')).toBe('14:32')
  })

  it('空值与非时间字符串一律渲染为占位符', () => {
    expect(formatDate(null)).toBe('--')
    expect(formatDate('')).toBe('--')
    expect(formatDate('不是时间')).toBe('--')
    expect(formatTime(null)).toBe('--')
    expect(formatTime('不是时间')).toBe('--')
  })
})
