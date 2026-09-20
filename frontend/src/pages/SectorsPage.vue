<script setup lang="ts">
import { ArrowUpRight, Grid2X2, List } from '@lucide/vue'
import { computed, onMounted, ref } from 'vue'

import PageHeader from '@/components/PageHeader.vue'
import { useRemoteData } from '@/composables/useRemoteData'
import { getSectorRankings } from '@/services/sectorApi'
import { formatChangeRate, formatDateTime, formatMoney, trendClass } from '@/utils/format'

const view = ref<'grid' | 'list'>('grid')

/**
 * 用板块排行而不是"板块列表 + 逐个取行情"。
 *
 * `GET /sectors` 只有 6 个主数据字段，卡片要的涨跌幅 / 成交额 / 领涨股都在 `SectorQuote` 里。
 * 逐个 `/sectors/{id}/quote` 取的话，39 个板块就是 39 次请求，而且**每次取到的快照批次可能不同**——
 * 页面上的板块涨跌幅会来自不同时刻。排行接口一次返回同一快照下的全部板块。
 *
 * `size=100` 是契约上限，覆盖全部 39 个板块，因此不需要分页。
 */
const { data: ranking, loading, error, reload } = useRemoteData(() =>
  getSectorRankings({ rankingType: 'GAINERS', size: 100 }))

/** "成交额最高"同样让服务端排序，不在客户端比较定点字符串。 */
const { data: turnoverTop, reload: loadTurnoverTop } = useRemoteData(() =>
  getSectorRankings({ rankingType: 'TURNOVER', size: 1 }))

onMounted(() => {
  reload()
  loadTurnoverTop()
})

const sectors = computed(() => ranking.value?.items ?? [])
const leader = computed(() => sectors.value[0])
const turnoverLeader = computed(() => turnoverTop.value?.items[0])

const risingCount = computed(() =>
  sectors.value.filter((sector) => Number(sector.changeRate ?? 0) > 0).length)

/**
 * 客观摘要：只陈述接口返回的事实，不写结论。
 *
 * 原型这里是一句写死的判断（"金融领涨，科技成交活跃"），配一个不能点的生成按钮。
 * 一句看起来像结论的话比留空更危险——用户会当成系统判断。AI 解读归 M3-06 / M3-07。
 */
const summary = computed(() => {
  if (!leader.value) return '板块行情暂不可用。'
  return `涨幅居前：${leader.value.sectorName} ${formatChangeRate(leader.value.changeRate)}；`
    + `成交额最高：${turnoverLeader.value?.sectorName ?? '--'}；`
    + `${sectors.value.length} 个板块中 ${risingCount.value} 个上涨。`
})

/**
 * 强度条宽度：把涨跌幅映射到 0~100 的**视觉**宽度。
 *
 * 与原型不同，这里不再用 `82 - index * 12` 这种按名次递减的假数据——
 * 那条长度与涨跌幅无关，看起来像指标却不是。改成按涨跌幅归一化：
 * 涨幅 10% 及以上占满，跌 10% 及以上为 0，平盘居中。
 */
function meterWidth(changeRate: string | null): number {
  const rate = Number(changeRate ?? 0)
  if (Number.isNaN(rate)) return 50
  const clamped = Math.max(-0.1, Math.min(0.1, rate))
  return Math.round(50 + (clamped / 0.1) * 50)
}
</script>

<template>
  <section v-if="error" class="market-state-panel" role="alert">
    <h1>板块数据暂时无法加载</h1>
    <p>{{ error.message }}</p>
    <small v-if="error.traceId">追踪编号：{{ error.traceId }}</small>
    <button data-testid="sectors-retry" type="button" @click="reload">重新加载</button>
  </section>

  <div v-else-if="ranking" class="business-page page-enter">
    <PageHeader
      eyebrow="SECTOR INTELLIGENCE"
      title="板块分析"
      description="从涨幅、资金与领涨结构观察主题强度，避免只看单一排行。"
      :data-time="ranking.dataTime ? formatDateTime(ranking.dataTime) : ''"
    >
      <div class="view-switch">
        <button :class="{ active: view === 'grid' }" type="button" @click="view = 'grid'">
          <Grid2X2 :size="15" />
        </button>
        <button :class="{ active: view === 'list' }" type="button" @click="view = 'list'">
          <List :size="15" />
        </button>
      </div>
    </PageHeader>

    <section class="sector-brief">
      <div>
        <span class="eyebrow">SECTOR SNAPSHOT</span>
        <h2>{{ leader?.sectorName ?? '暂无板块数据' }}</h2>
        <p>{{ summary }}</p>
      </div>
    </section>

    <section class="sector-board" :class="`is-${view}`">
      <RouterLink
        v-for="(sector, index) in sectors"
        :key="sector.sectorId"
        :to="`/sectors/${sector.sectorId}`"
        class="sector-card"
      >
        <header>
          <span>{{ String(index + 1).padStart(2, '0') }}</span>
          <b :class="trendClass(sector.changeRate)">{{ formatChangeRate(sector.changeRate) }}</b>
        </header>
        <h2>{{ sector.sectorName }}</h2>
        <p>{{ sector.companyCount }} 家成分股 · {{ sector.sectorCode }}</p>
        <div class="sector-meter"><i :style="{ width: `${meterWidth(sector.changeRate)}%` }" /></div>
        <dl>
          <div><dt>成交额</dt><dd>{{ formatMoney(sector.tradeAmount) }}</dd></div>
          <div>
            <dt>领涨股</dt>
            <dd>{{ sector.leadingStock?.security.securityName ?? '--' }}</dd>
          </div>
        </dl>
        <footer><span>查看板块证据</span><ArrowUpRight :size="15" /></footer>
      </RouterLink>
    </section>
  </div>

  <div v-else-if="loading" class="page-loading" aria-label="正在加载板块数据">
    <span /><span /><span />
  </div>
</template>
