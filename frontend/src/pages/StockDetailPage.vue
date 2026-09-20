<script setup lang="ts">
import { BellPlus, Star } from '@lucide/vue'
import type { EChartsOption } from 'echarts'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import BaseChart from '@/components/BaseChart.vue'
import { useRemoteData } from '@/composables/useRemoteData'
import { getSecurityKlines, getSecurityQuote } from '@/services/securityApi'
import type { KlinePeriod } from '@/types/domain'
import { formatChangeRate, formatDateTime, formatMoney, formatVolume, trendClass } from '@/utils/format'

const route = useRoute()
const securityId = computed(() => String(route.params.id))

/**
 * 周期只有契约 STK-07 白名单里的三种。
 *
 * 原型还有一档"分时"——那是 STK-06，后端未实现（见 `PROJECT_STATUS.md` 已知问题）。
 * 把它留在切换器里会得到一个永远画不出东西的按钮，比没有这个按钮更让人困惑。
 */
const PERIODS: { value: KlinePeriod; label: string }[] = [
  { value: 'DAY', label: '日 K' },
  { value: 'WEEK', label: '周 K' },
  { value: 'MONTH', label: '月 K' },
]

const period = ref<KlinePeriod>('DAY')

const {
  data: quote,
  loading: loadingQuote,
  error: quoteError,
  reload: reloadQuote,
} = useRemoteData(() => getSecurityQuote(securityId.value))

/**
 * K 线与快照是两次请求，**不合并**成一个"数据截止时间"。
 *
 * 契约 STK-05 明确"不保证不同证券源时间完全相同"：K 线的最后一个交易日与快照的
 * 盘中时刻本就是两个不同的时间点。合成一个会让用户以为它们同源同时。
 *
 * 周期是服务端参数（周 K / 月 K 由服务端按交易日历聚合），因此切换周期必须重新请求。
 * 在客户端把日 K 折叠成周 K 会在客户端交易日历与服务端不一致时错位。
 */
const {
  data: klines,
  loading: loadingKlines,
  error: klineError,
  reload: reloadKlines,
} = useRemoteData(() => getSecurityKlines(securityId.value, { period: period.value }))

watch(period, () => reloadKlines())

onMounted(() => {
  reloadQuote()
  reloadKlines()
})

const points = computed(() => klines.value?.points ?? [])

/** 含 DELAYED 的 K 线要在图下提示：延迟数据与实时数据混在一张图里是看不出来的。 */
const hasDelayedPoint = computed(() =>
  points.value.some((point) => point.qualityStatus !== 'VALID'))

const klineOption = computed<EChartsOption>(() => ({
  animation: false,
  grid: { left: 18, right: 14, top: 22, bottom: 28, outerBoundsMode: 'same', outerBoundsContain: 'axisLabel' },
  tooltip: { trigger: 'axis', axisPointer: { type: 'cross' }, borderWidth: 0, backgroundColor: '#102824', textStyle: { color: '#fff' } },
  xAxis: {
    type: 'category',
    data: points.value.map((point) => point.time.slice(5)),
    boundaryGap: true,
    axisLine: { lineStyle: { color: '#d7d0c2' } },
    axisTick: { show: false },
    axisLabel: { color: '#84908c', fontSize: 9, interval: 5 },
  },
  yAxis: { scale: true, splitLine: { lineStyle: { color: '#e8e1d5', type: 'dashed' } }, axisLabel: { color: '#84908c', fontSize: 9 } },
  series: [{
    type: 'candlestick',
    data: points.value.map((point) => [
      Number(point.openPrice),
      Number(point.closePrice),
      Number(point.lowPrice),
      Number(point.highPrice),
    ]),
    itemStyle: { color: '#c94735', color0: '#16816b', borderColor: '#c94735', borderColor0: '#16816b' },
  }],
}))
</script>

<template>
  <section v-if="quoteError" class="market-state-panel" role="alert">
    <h1>个股数据暂时无法加载</h1>
    <p>{{ quoteError.message }}</p>
    <small v-if="quoteError.traceId">追踪编号：{{ quoteError.traceId }}</small>
    <button data-testid="stock-detail-retry" type="button" @click="reloadQuote">重新加载</button>
  </section>

  <div v-else-if="quote" class="stock-page page-enter">
    <section class="stock-hero">
      <div class="stock-identity">
        <span class="exchange-badge">{{ quote.security.exchangeCode }}</span>
        <div>
          <span class="eyebrow">{{ quote.security.fullSymbol }}</span>
          <h1>{{ quote.security.securityName }}</h1>
          <p>
            {{ quote.security.isSuspended ? '停牌' : '正常交易' }} ·
            数据截止 {{ formatDateTime(quote.dataTime) }}
          </p>
        </div>
      </div>
      <div class="stock-price">
        <strong class="mono">{{ quote.latestPrice ?? '--' }}</strong>
        <span :class="trendClass(quote.changeRate)" class="mono">
          {{ quote.changeAmount ?? '--' }} · {{ formatChangeRate(quote.changeRate) }}
        </span>
      </div>
      <div class="stock-actions">
        <button class="secondary-button" type="button" disabled title="自选功能待接入（M3-01 / M3-02）">
          <Star :size="15" /> 加入自选
        </button>
        <button class="secondary-button" type="button" disabled title="价格预警待接入（M3-01）">
          <BellPlus :size="15" /> 设预警
        </button>
      </div>
    </section>

    <p v-if="quote.dataStatus !== 'REALTIME'" class="component-unavailable" data-testid="quote-data-status">
      当前展示最近有效快照（数据截止 {{ formatDateTime(quote.dataTime) }}）。
    </p>

    <section class="stock-stat-strip">
      <div><span>今开</span><strong class="mono">{{ quote.openPrice ?? '--' }}</strong></div>
      <div><span>最高</span><strong class="trend-up mono">{{ quote.highPrice ?? '--' }}</strong></div>
      <div><span>最低</span><strong class="trend-down mono">{{ quote.lowPrice ?? '--' }}</strong></div>
      <div><span>昨收</span><strong class="mono">{{ quote.previousClosePrice ?? '--' }}</strong></div>
      <div><span>成交量</span><strong>{{ formatVolume(quote.tradeVolume) }}</strong></div>
      <div><span>成交额</span><strong>{{ formatMoney(quote.tradeAmount) }}</strong></div>
      <div><span>换手率</span><strong>{{ formatChangeRate(quote.turnoverRate) }}</strong></div>
    </section>

    <section class="stock-research-grid">
      <article class="chart-card stock-chart">
        <header>
          <div><span class="eyebrow">PRICE EVIDENCE</span><h2>价格与成交结构</h2></div>
          <div class="segmented-tabs compact">
            <button
              v-for="item in PERIODS"
              :key="item.value"
              :class="{ active: period === item.value }"
              type="button"
              @click="period = item.value"
            >
              {{ item.label }}
            </button>
          </div>
        </header>

        <p v-if="klineError" class="component-unavailable" role="alert">
          K 线加载失败：{{ klineError.message }}
          <button data-testid="kline-retry" type="button" @click="reloadKlines">重新加载</button>
        </p>
        <BaseChart v-else-if="points.length" :option="klineOption" height="360px" />
        <p v-else-if="loadingKlines" class="component-unavailable">正在加载 K 线…</p>
        <p v-else class="component-unavailable" data-testid="kline-empty">该周期暂无 K 线数据。</p>

        <p v-if="hasDelayedPoint" class="component-unavailable" data-testid="kline-quality">
          K 线中包含非实时数据点（存在延迟或修正），请结合数据截止时间判断。
        </p>
        <p v-if="klines" class="page-header__time">
          数据截止 {{ formatDateTime(klines.dataCutoffAt) }}
        </p>
      </article>
    </section>

    <section class="stock-lower-grid">
      <article class="company-profile">
        <header class="section-heading">
          <div>
            <span class="section-index">01</span>
            <div><h2>经营与主题</h2><p>理解标的业务边界</p></div>
          </div>
        </header>
        <p class="component-unavailable" data-testid="profile-unavailable">
          公司资料（STK-08）与所属板块（STK-09）后端尚未实现，暂不展示。
          这里不再保留原型中的业务描述与主题标签——它们没有数据来源。
        </p>
      </article>

      <article class="related-events">
        <header class="section-heading">
          <div>
            <span class="section-index">02</span>
            <div><h2>关联事件</h2><p>公告与资讯时间线</p></div>
          </div>
          <RouterLink to="/news">全部</RouterLink>
        </header>
        <p class="component-unavailable" data-testid="news-unavailable">
          个股关联资讯（STK-10）后端尚未实现，暂不展示。
        </p>
      </article>
    </section>
  </div>

  <div v-else-if="loadingQuote" class="page-loading" aria-label="正在加载个股数据">
    <span /><span /><span />
  </div>
</template>
