<script setup lang="ts">
import { ArrowUpRight, ChevronRight, Clock3, Sparkles } from '@lucide/vue'
import type { EChartsOption } from 'echarts'
import { computed, onMounted, ref } from 'vue'

import BaseChart from '@/components/BaseChart.vue'
import { getMarketOverview } from '@/services/mockApi'
import type { MarketOverview } from '@/types/domain'
import { formatChangeRate, formatDateTime, formatMoney, trendClass } from '@/utils/format'

const market = ref<MarketOverview>()

onMounted(async () => {
  market.value = (await getMarketOverview()).data
})

const breadthRatio = computed(() => {
  if (!market.value) return 0
  const { riseCount, fallCount, flatCount } = market.value.breadth
  return Math.round((riseCount / (riseCount + fallCount + flatCount)) * 100)
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
  <div v-if="market" class="market-page page-enter">
    <section class="market-lead">
      <div>
        <span class="eyebrow">MARKET PULSE · CN</span>
        <h1>今日市场，<em>温和放量。</em></h1>
        <p>金融与科技方向形成共振，市场广度改善。重点观察午后成交持续性，以及高位板块的分化风险。</p>
      </div>
      <div class="market-lead__aside">
        <div class="data-time"><Clock3 :size="15" /> 数据截止 {{ formatDateTime(market.dataTime) }}</div>
        <button type="button"><Sparkles :size="16" /> 生成市场解读</button>
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
          <div class="headline-number"><strong>{{ formatMoney(market.turnover.amount) }}</strong><small class="trend-up">较昨日 +8.69%</small></div>
        </header>
        <BaseChart :option="turnoverOption" height="230px" />
      </article>

      <article class="research-panel sectors-panel">
        <header class="section-heading">
          <div><span class="section-index">03</span><div><h2>热点板块</h2><p>资金与涨幅交叉观察</p></div></div>
          <RouterLink to="/sectors">全部板块 <ChevronRight :size="14" /></RouterLink>
        </header>
        <div class="sector-list">
          <RouterLink v-for="(sector, index) in market.sectors" :key="sector.sectorId" :to="`/sectors/${sector.sectorId}`">
            <span class="sector-rank">0{{ index + 1 }}</span>
            <span class="sector-name"><strong>{{ sector.sectorName }}</strong><small>领涨 {{ sector.leadingStock }}</small></span>
            <span class="sector-amount mono">{{ formatMoney(sector.tradeAmount) }}</span>
            <b :class="trendClass(sector.changeRate)" class="mono">{{ formatChangeRate(sector.changeRate) }}</b>
          </RouterLink>
        </div>
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
  <div v-else class="page-loading" aria-label="正在加载市场数据">
    <span /><span /><span />
  </div>
</template>
