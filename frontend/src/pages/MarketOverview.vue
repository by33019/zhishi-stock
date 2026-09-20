<script setup lang="ts">
import { ArrowUpRight, ChevronRight, Clock3, Sparkles } from '@lucide/vue'
import type { EChartsOption } from 'echarts'
import { computed, onMounted, ref } from 'vue'

import BaseChart from '@/components/BaseChart.vue'
import { getMarketOverview } from '@/services/marketApi'
import type { MarketOverview } from '@/types/domain'
import { formatChangeRate, formatDate, formatDateTime, formatMoney, formatTime, trendClass } from '@/utils/format'

const market = ref<MarketOverview>()
const loading = ref(true)
const error = ref<{ message: string; traceId?: string }>()

async function loadMarket() {
  loading.value = true
  error.value = undefined
  try {
    market.value = await getMarketOverview()
  } catch (cause) {
    const failure = cause as { message?: string; traceId?: string }
    market.value = undefined
    error.value = {
      message: failure.message ?? '市场行情暂不可用',
      traceId: failure.traceId,
    }
  } finally {
    loading.value = false
  }
}

onMounted(loadMarket)

const breadthRatio = computed(() => {
  if (!market.value) return 0
  const { riseCount, fallCount, flatCount } = market.value.breadth
  return Math.round((riseCount / (riseCount + fallCount + flatCount)) * 100)
})

/**
 * 这份快照对应的是**哪个交易日**的行情。
 *
 * 修复 #7 之后，非交易日（周末 / 节假日）与盘后拿到的是最近有效收盘数据，
 * 而页面上的"数据截止 09/19 15:00"看起来就像数据停更了。不解释清楚，
 * 用户会以为页面坏了——只改后端会把"显示假数据"换成"显示正确但令人困惑的数据"。
 */
const tradeDateLabel = computed(() => (market.value ? formatDate(market.value.tradeDate) : '--'))

/** 快照自身标称的市场状态（后端按交易日历推导），不是本地时钟推出来的。 */
const isLiveSession = computed(() => {
  const status = market.value?.marketStatus
  return status === 'PRE_OPEN' || status === 'CALL_AUCTION'
    || status === 'TRADING' || status === 'BREAK'
})

/**
 * 非交易时段标记。
 *
 * "休市"与"已收盘"必须分开：周日显示"已收盘"会让人以为今天开过市。
 * 判据是**快照的交易日是不是今天**（按北京时间比较）——
 * 不能用"`dataTime` 与 `tradeDate` 是否同日"：后端在非交易日会把 `dataTime`
 * 回退到上一交易日的收盘时刻，两者本来就同日，区分不出周末与盘后。
 */
const sessionBadge = computed(() => {
  if (!market.value || isLiveSession.value) return null
  return tradeDateLabel.value === formatDate(new Date().toISOString()) ? '已收盘' : '非交易日'
})

/** 首屏导语。写死一句"今日市场，温和放量"在周日是错的——那天没有"今日市场"。 */
const leadHeadline = computed(() => {
  if (sessionBadge.value === '非交易日') {
    return { title: '今日休市，', emphasis: `展示 ${tradeDateLabel.value} 收盘数据。` }
  }
  if (sessionBadge.value === '已收盘') {
    return { title: '今日已收盘，', emphasis: `数据截止 ${formatTime(market.value?.dataTime ?? null)}。` }
  }
  return { title: '今日市场，', emphasis: '盘中快照。' }
})

/**
 * 头部导语由真实广度数据拼出来，而不是写死一段行情判断。
 *
 * 原型那句"金融与科技方向形成共振，市场广度改善"在任何一天都显示同一个结论，
 * 且没有任何数据支撑它。与 M2-08 的做法一致：没有数据来源的表述不保留。
 */
const leadSummary = computed(() => {
  if (!market.value) return ''
  const { riseCount, fallCount, flatCount, limitUpCount, limitDownCount } = market.value.breadth
  const traded = riseCount + fallCount + flatCount
  const direction = riseCount > fallCount ? '涨多跌少' : riseCount < fallCount ? '跌多涨少' : '涨跌相当'
  return `${traded} 只交易标的中 ${riseCount} 只上涨、${fallCount} 只下跌、`
    + `${flatCount} 只平盘，${direction}；涨停 ${limitUpCount} 只、跌停 ${limitDownCount} 只。`
})

/**
 * 较昨日成交额变化。
 *
 * 原型写死 `+8.69%`，而同一份响应里的 `amount` / `previousAmount` 算出来是别的数——
 * 页面上的数字与它自己引用的数据互相矛盾，比空白更糟。改为直接算。
 */
const turnoverChange = computed(() => {
  const previous = Number(market.value?.turnover.previousAmount ?? 0)
  const current = Number(market.value?.turnover.amount ?? 0)
  if (!Number.isFinite(previous) || !Number.isFinite(current) || previous <= 0) return null
  const rate = (current / previous - 1) * 100
  return {
    text: `${rate >= 0 ? '+' : ''}${rate.toFixed(2)}%`,
    trend: trendClass(String(current / previous - 1)),
  }
})

const breadthOption = computed<EChartsOption>(() => ({
  tooltip: { trigger: 'item' },
  series: [
    {
      type: 'pie',
      radius: ['66%', '86%'],
      center: ['50%', '52%'],
      silent: true,
      label: { show: false },
      data: [
        { value: market.value?.breadth.riseCount ?? 0, itemStyle: { color: '#c94735' } },
        { value: market.value?.breadth.fallCount ?? 0, itemStyle: { color: '#16816b' } },
        { value: market.value?.breadth.flatCount ?? 0, itemStyle: { color: '#d7d0c2' } },
      ],
    },
  ],
}))

const turnoverOption = computed<EChartsOption>(() => ({
  grid: { left: 8, right: 8, top: 24, bottom: 8, outerBoundsMode: 'same', outerBoundsContain: 'axisLabel' },
  tooltip: { trigger: 'axis', borderWidth: 0, backgroundColor: '#102824', textStyle: { color: '#fff' } },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: ['09:30', '10:00', '10:30', '11:00', '11:30', '13:30', '14:00', '14:32'],
    axisLine: { lineStyle: { color: '#d7d0c2' } },
    axisTick: { show: false },
    axisLabel: { color: '#84908c', fontSize: 10 },
  },
  yAxis: {
    type: 'value',
    axisLabel: { formatter: '{value}B', color: '#84908c', fontSize: 10 },
    splitLine: { lineStyle: { color: '#e8e1d5', type: 'dashed' } },
  },
  series: [
    {
      type: 'line',
      smooth: 0.35,
      symbol: 'none',
      lineStyle: { color: '#bd8b38', width: 2.5 },
      areaStyle: { color: 'rgba(189,139,56,.12)' },
      data: market.value?.turnover.points ?? [],
    },
  ],
}))
</script>

<template>
  <section v-if="error" class="market-state-panel" role="alert">
    <h1>市场数据暂时无法加载</h1>
    <p>{{ error.message }}</p>
    <small v-if="error.traceId">追踪编号：{{ error.traceId }}</small>
    <button data-testid="market-retry" type="button" @click="loadMarket">重新加载</button>
  </section>
  <div v-else-if="market" class="market-page page-enter">
    <section class="market-lead">
      <div>
        <span class="eyebrow">MARKET PULSE · CN</span>
        <h1>
          {{ leadHeadline.title }}<em>{{ leadHeadline.emphasis }}</em>
        </h1>
        <p data-testid="market-lead-summary">{{ leadSummary }}</p>
      </div>
      <div class="market-lead__aside">
        <div class="data-time">
          <Clock3 :size="15" /> 数据截止 {{ formatDateTime(market.dataTime) }}
          <b v-if="sessionBadge" class="trade-date-badge" data-testid="trade-date-badge">
            交易日 {{ tradeDateLabel }} · {{ sessionBadge }}
          </b>
        </div>
        <div
          v-if="market.dataStatus !== 'REALTIME'"
          class="market-data-status"
          data-testid="market-data-status"
        >
          {{ market.dataStatus === 'DELAYED' ? '行情存在延迟' : '当前展示最近有效快照' }}
          · 最近同步 {{ formatDateTime(market.lastSuccessfulSyncAt) }}
        </div>
        <button type="button" disabled title="AI 市场解读待接入（M3-06）">
          <Sparkles :size="16" /> 生成市场解读
        </button>
      </div>
    </section>

    <section class="index-tape" aria-label="主要指数">
      <article v-for="index in market.indices" :key="index.indexId">
        <div><span>{{ index.indexName }}</span><small>{{ index.region === 'DOMESTIC' ? 'A股' : '海外' }}</small></div>
        <strong class="mono">{{ index.latestPoint }}</strong>
        <b :class="trendClass(index.changeRate)" class="mono">{{ formatChangeRate(index.changeRate) }}</b>
        <svg viewBox="0 0 120 34" preserveAspectRatio="none" aria-hidden="true">
          <polyline
            fill="none"
            :class="trendClass(index.changeRate)"
            stroke="currentColor"
            stroke-width="2"
            :points="index.sparkline.map((point, i) => `${i * 24},${31 - ((point - Math.min(...index.sparkline)) / (Math.max(...index.sparkline) - Math.min(...index.sparkline) || 1)) * 27}`).join(' ')"
          />
        </svg>
      </article>
    </section>

    <section class="market-grid">
      <article class="research-panel breadth-panel">
        <header class="section-heading">
          <div><span class="section-index">01</span><div><h2>市场广度</h2><p>上涨家数占全部交易标的比例</p></div></div>
          <span class="status-label">盘面温度</span>
        </header>
        <div class="breadth-content">
          <div class="donut-wrap">
            <BaseChart :option="breadthOption" height="190px" />
            <div><strong>{{ breadthRatio }}%</strong><span>上涨占比</span></div>
          </div>
          <dl>
            <div><dt><i class="up" />上涨</dt><dd class="trend-up mono">{{ market.breadth.riseCount }}</dd></div>
            <div><dt><i class="down" />下跌</dt><dd class="trend-down mono">{{ market.breadth.fallCount }}</dd></div>
            <div><dt><i class="flat" />平盘</dt><dd class="mono">{{ market.breadth.flatCount }}</dd></div>
            <div class="limit-row"><dt>涨停 / 跌停</dt><dd><b class="trend-up">{{ market.breadth.limitUpCount }}</b><span>/</span><b class="trend-down">{{ market.breadth.limitDownCount }}</b></dd></div>
          </dl>
        </div>
      </article>

      <article class="research-panel turnover-panel">
        <header class="section-heading">
          <div><span class="section-index">02</span><div><h2>成交趋势</h2><p>两市累计成交额</p></div></div>
          <div class="headline-number">
            <strong>{{ formatMoney(market.turnover.amount) }}</strong>
            <small
              v-if="turnoverChange"
              :class="turnoverChange.trend"
              data-testid="turnover-change"
            >较昨日 {{ turnoverChange.text }}</small>
            <small v-else data-testid="turnover-change">较昨日 --</small>
          </div>
        </header>
        <BaseChart :option="turnoverOption" height="230px" />
      </article>

      <article class="research-panel sectors-panel">
        <header class="section-heading">
          <div><span class="section-index">03</span><div><h2>热点板块</h2><p>资金与涨幅交叉观察</p></div></div>
          <RouterLink to="/sectors">全部板块 <ChevronRight :size="14" /></RouterLink>
        </header>
        <p v-if="market.componentStatus.sectors === 'UNAVAILABLE'" class="component-unavailable">
          热点板块暂不可用，其他行情仍可正常浏览。
        </p>
        <div v-else-if="market.sectors.length" class="sector-list">
          <RouterLink v-for="(sector, index) in market.sectors" :key="sector.sectorId" :to="`/sectors/${sector.sectorId}`">
            <span class="sector-rank">0{{ index + 1 }}</span>
            <span class="sector-name"><strong>{{ sector.sectorName }}</strong><small>领涨 {{ sector.leadingStock ?? '--' }}</small></span>
            <span class="sector-amount mono">{{ formatMoney(sector.tradeAmount) }}</span>
            <b :class="trendClass(sector.changeRate)" class="mono">{{ formatChangeRate(sector.changeRate) }}</b>
          </RouterLink>
        </div>
        <p v-else class="component-unavailable">暂无热点板块数据。</p>
      </article>
    </section>

    <section class="lower-grid">
      <article class="editorial-table">
        <header class="section-heading">
          <div><span class="section-index">04</span><div><h2>行情热榜</h2><p>活跃标的与即时趋势</p></div></div>
          <RouterLink to="/rankings">打开完整榜单 <ArrowUpRight :size="14" /></RouterLink>
        </header>
        <div class="quote-table-wrap">
          <table class="quote-table">
            <thead><tr><th>标的</th><th>最新价</th><th>涨跌幅</th><th>成交额</th><th>换手</th></tr></thead>
            <tbody>
              <tr v-for="stock in market.rankings" :key="stock.securityId">
                <td><RouterLink :to="`/stocks/${stock.securityId}`"><strong>{{ stock.securityName }}</strong><small>{{ stock.exchangeCode }}.{{ stock.securityCode }}</small></RouterLink></td>
                <td class="mono">{{ stock.latestPrice }}</td>
                <td :class="trendClass(stock.changeRate)" class="mono strong">{{ formatChangeRate(stock.changeRate) }}</td>
                <td class="mono">{{ formatMoney(stock.tradeAmount) }}</td>
                <td class="mono">{{ formatChangeRate(stock.turnoverRate) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </article>

      <article class="event-radar">
        <header class="section-heading">
          <div><span class="section-index">05</span><div><h2>事件雷达</h2><p>影响市场判断的最新线索</p></div></div>
          <RouterLink to="/news">更多 <ChevronRight :size="14" /></RouterLink>
        </header>
        <div class="event-list">
          <RouterLink v-for="item in market.news" :key="item.newsId" to="/news">
            <div><span>{{ item.newsType === 'ANNOUNCEMENT' ? '公告' : item.newsType === 'RESEARCH' ? '研报' : '快讯' }}</span><time>{{ formatDateTime(item.publishedAt) }}</time></div>
            <h3>{{ item.title }}</h3>
            <p>{{ item.summary }}</p>
            <small>{{ item.sourceName }}</small>
          </RouterLink>
        </div>
      </article>
    </section>
  </div>
  <div v-else-if="loading" class="page-loading" aria-label="正在加载市场数据">
    <span /><span /><span />
  </div>
</template>
