import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

import { useRemoteData } from '@/composables/useRemoteData'
import { getMarketStatus } from '@/services/marketApi'
import { formatDateTime } from '@/utils/format'
import type { MarketSessionStatus } from '@/types/domain'

/**
 * 刷新节拍。
 *
 * 市场状态会在 09:15 / 09:25 / 09:30 / 11:30 / 13:00 / 14:57 / 15:00 变化，
 * 不刷新就会一直停在旧状态。60 秒足够跟上，又不会让每个打开的标签页
 * 每分钟发多次请求。
 *
 * 刻意**不**按 `nextSessionAt` 定时唤醒：那样在日历本身过期时会静默停更，
 * 固定节拍不会失效。
 */
export const MARKET_STATUS_REFRESH_MS = 60_000

/** 各时段的中文文案。 */
const SESSION_LABELS: Record<MarketSessionStatus, string> = {
  PRE_OPEN: '盘前',
  CALL_AUCTION: '集合竞价',
  TRADING: '交易中',
  BREAK: '午间休市',
  CLOSED: '已收盘',
}

/**
 * 顶栏 / 侧栏用的市场状态。
 *
 * 在 {@link useRemoteData} 之上只叠加两件事：定时刷新，以及页面不可见时暂停。
 * 轮询**不放进** `useRemoteData`——它的定位是"一次请求的三态"，
 * 加了轮询就会让四个业务页也一起轮询。
 */
export function useMarketStatus(marketCode = 'CN') {
  const { data, loading, error, reload } = useRemoteData(() => getMarketStatus(marketCode))

  /** 当前时刻，与刷新同节拍更新；顶栏用它显示"交易中 14:32"。 */
  const now = ref(new Date().toISOString())

  let timer: number | undefined

  /** 首次加载：此时还没有任何数据，才值得显示占位。 */
  const isFirstLoad = computed(() => loading.value && !data.value)

  /**
   * 状态文案。
   *
   * 非交易日与"已收盘"必须分开：周日显示"已收盘"会让人以为今天开过市。
   */
  const label = computed(() => {
    if (!data.value) return null
    if (!data.value.isTradingDay) return '休市'
    return SESSION_LABELS[data.value.sessionStatus]
  })

  /**
   * 是否处于时段进行中。
   *
   * 只有进行中才显示当前时间：收盘后显示时刻会被读成"数据截止该时刻"，
   * 而那时没有数据。
   */
  const isLive = computed(() => {
    const status = data.value?.sessionStatus
    return status === 'PRE_OPEN' || status === 'CALL_AUCTION'
      || status === 'TRADING' || status === 'BREAK'
  })

  const nextSessionLabel = computed(() =>
    data.value?.nextSessionAt ? formatDateTime(data.value.nextSessionAt) : null,
  )

  function tick() {
    now.value = new Date().toISOString()
    void reload()
  }

  function start() {
    if (timer !== undefined) return
    timer = window.setInterval(tick, MARKET_STATUS_REFRESH_MS)
  }

  function stop() {
    if (timer === undefined) return
    window.clearInterval(timer)
    timer = undefined
  }

  /** 后台标签页持续请求没有意义；恢复可见时立刻补一次，免得切回来看到过期状态。 */
  function onVisibilityChange() {
    if (document.hidden) {
      stop()
      return
    }
    tick()
    start()
  }

  onMounted(() => {
    void reload()
    start()
    document.addEventListener('visibilitychange', onVisibilityChange)
  })

  onBeforeUnmount(() => {
    stop()
    document.removeEventListener('visibilitychange', onVisibilityChange)
  })

  return {
    status: data,
    label,
    isLive,
    now,
    nextSessionLabel,
    isFirstLoad,
    error,
    reload,
  }
}
