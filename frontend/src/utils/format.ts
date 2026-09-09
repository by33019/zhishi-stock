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

export function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value))
}
