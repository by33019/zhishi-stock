export function formatChangeRate(value: string | null): string {
  if (value === null || value === '') return '--'

  const number = Number(value) * 100
  const prefix = number > 0 ? '+' : ''
  return `${prefix}${number.toFixed(2)}%`
}

export function trendClass(value: string | null): 'trend-up' | 'trend-down' | 'trend-flat' {
  const number = Number(value ?? 0)
  if (number > 0) return 'trend-up'
  if (number < 0) return 'trend-down'
  return 'trend-flat'
}

export function formatMoney(value: string | null): string {
  if (value === null || value === '') return '--'
  const number = Number(value)
  if (number >= 100_000_000) return `${(number / 100_000_000).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}亿`
  if (number >= 10_000) return `${(number / 10_000).toFixed(2)}万`
  return number.toLocaleString('zh-CN')
}

export function formatVolume(value: string | null): string {
  if (value === null || value === '') return '--'
  const number = Number(value)
  if (number >= 100_000_000) return `${(number / 100_000_000).toFixed(2)}亿股`
  if (number >= 10_000) return `${(number / 10_000).toFixed(2)}万股`
  return `${number.toLocaleString('zh-CN')}股`
}

/**
 * 行情时间一律按北京时间（Asia/Shanghai）渲染。
 *
 * 必须显式指定 timeZone：不指定时 Intl 会使用运行环境本地时区，
 * 导致部署在 UTC 机器上的实例把 A 股行情时间显示成 UTC 时间（差 8 小时，
 * 跨日时连日期都会错）。A 股交易时间只存在于北京时间这一种语义下，
 * 因此这里固定时区而不是跟随运行环境。
 *
 * 接受 `null` 是因为契约里 `dataTime` 可空（板块全停牌、快照缺失等）。
 * 不做成可选参数：`formatDateTime(undefined)` 与"没传"无法区分，
 * 显式要求调用方传 `null` 能逼出遗漏的分支。
 */
export function formatDateTime(value: string | null): string {
  if (value === null || value === '') return '--'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return '--'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(parsed)
}
