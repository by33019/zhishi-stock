<script setup lang="ts">
import { BellPlus, ChevronRight, Sparkles, Star } from '@lucide/vue'
import type { EChartsOption } from 'echarts'
import { computed, onMounted, ref } from 'vue'

import BaseChart from '@/components/BaseChart.vue'
import { getStockDetail } from '@/services/mockApi'
import type { StockDetail } from '@/types/domain'
import { formatChangeRate, formatDateTime, formatMoney, formatVolume, trendClass } from '@/utils/format'

const stock = ref<StockDetail>()
const activeFrame = ref('日 K')

onMounted(async () => {
  stock.value = (await getStockDetail('19876543210001')).data
})

const klineOption = computed<EChartsOption>(() => ({
  animation: false,
  grid: { left: 18, right: 14, top: 22, bottom: 28, outerBoundsMode: 'same', outerBoundsContain: 'axisLabel' },
  tooltip: { trigger: 'axis', axisPointer: { type: 'cross' }, borderWidth: 0, backgroundColor: '#102824', textStyle: { color: '#fff' } },
  xAxis: { type: 'category', data: stock.value?.kline.map((item) => item.time.slice(5)) ?? [], boundaryGap: true, axisLine: { lineStyle: { color: '#d7d0c2' } }, axisTick: { show: false }, axisLabel: { color: '#84908c', fontSize: 9, interval: 5 } },
  yAxis: { scale: true, splitLine: { lineStyle: { color: '#e8e1d5', type: 'dashed' } }, axisLabel: { color: '#84908c', fontSize: 9 } },
  series: [{ type: 'candlestick', data: stock.value?.kline.map((item) => [item.open, item.close, item.low, item.high]) ?? [], itemStyle: { color: '#c94735', color0: '#16816b', borderColor: '#c94735', borderColor0: '#16816b' } }],
}))
</script>

<template>
  <div v-if="stock" class="stock-page page-enter">
    <section class="stock-hero">
      <div class="stock-identity"><span class="exchange-badge">{{ stock.exchangeCode }}</span><div><span class="eyebrow">{{ stock.fullSymbol }}</span><h1>{{ stock.securityName }}</h1><p>{{ stock.sectors.join(' · ') }} · 实时行情</p></div></div>
      <div class="stock-price"><strong class="mono">{{ stock.latestPrice }}</strong><span :class="trendClass(stock.changeRate)" class="mono">{{ stock.changeAmount }} · {{ formatChangeRate(stock.changeRate) }}</span></div>
      <div class="stock-actions"><button class="secondary-button" type="button"><Star :size="15" /> 加入自选</button><button class="secondary-button" type="button"><BellPlus :size="15" /> 设预警</button><button class="primary-button" type="button"><Sparkles :size="15" /> AI 异动解读</button></div>
    </section>

    <section class="stock-stat-strip">
      <div><span>今开</span><strong class="mono">{{ stock.openPrice }}</strong></div><div><span>最高</span><strong class="trend-up mono">{{ stock.highPrice }}</strong></div><div><span>最低</span><strong class="trend-down mono">{{ stock.lowPrice }}</strong></div><div><span>昨收</span><strong class="mono">{{ stock.previousClosePrice }}</strong></div><div><span>成交量</span><strong>{{ formatVolume(stock.tradeVolume) }}</strong></div><div><span>成交额</span><strong>{{ formatMoney(stock.tradeAmount) }}</strong></div><div><span>换手率</span><strong>{{ formatChangeRate(stock.turnoverRate) }}</strong></div><div><span>市盈率</span><strong>{{ stock.peRatio }}</strong></div>
    </section>

    <section class="stock-research-grid">
      <article class="chart-card stock-chart">
        <header><div><span class="eyebrow">PRICE EVIDENCE</span><h2>价格与成交结构</h2></div><div class="segmented-tabs compact"><button v-for="frame in ['分时', '日 K', '周 K', '月 K']" :key="frame" :class="{ active: activeFrame === frame }" type="button" @click="activeFrame = frame">{{ frame }}</button></div></header>
        <BaseChart :option="klineOption" height="360px" />
      </article>
      <aside class="ai-snapshot">
        <span class="eyebrow">AI QUICK VIEW</span><h2>异动速览</h2><strong>放量上行，板块共振较强</strong><p>当前涨幅与银行板块同步扩大，成交额较近 5 日同期提升。事件侧有经营数据支撑，但仍需关注尾盘量能回落。</p>
        <ul><li><span>量价可信度</span><b>高</b></li><li><span>事件相关性</span><b>中高</b></li><li><span>数据新鲜度</span><b>26 秒</b></li></ul>
        <button type="button"><Sparkles :size="15" /> 查看完整证据链</button><small>内容由 AI 生成，仅供研究参考</small>
      </aside>
    </section>

    <section class="stock-lower-grid">
      <article class="company-profile"><header class="section-heading"><div><span class="section-index">01</span><div><h2>经营与主题</h2><p>理解标的业务边界</p></div></div></header><p>{{ stock.businessDescription }}</p><div class="tag-row"><span v-for="sector in stock.sectors" :key="sector">{{ sector }}</span></div><dl><div><dt>总市值</dt><dd>{{ formatMoney(stock.marketCap) }}</dd></div><div><dt>所属交易所</dt><dd>上海证券交易所</dd></div></dl></article>
      <article class="related-events"><header class="section-heading"><div><span class="section-index">02</span><div><h2>关联事件</h2><p>公告与资讯时间线</p></div></div><RouterLink to="/news">全部 <ChevronRight :size="14" /></RouterLink></header><div class="event-list"><RouterLink v-for="item in stock.news" :key="item.newsId" to="/news"><div><span>{{ item.newsType === 'ANNOUNCEMENT' ? '公告' : '资讯' }}</span><time>{{ formatDateTime(item.publishedAt) }}</time></div><h3>{{ item.title }}</h3><p>{{ item.summary }}</p><small>{{ item.sourceName }}</small></RouterLink></div></article>
    </section>
  </div>
  <div v-else class="page-loading"><span /><span /><span /></div>
</template>
