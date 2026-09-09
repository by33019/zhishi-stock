<script setup lang="ts">
import { ArrowLeft, Sparkles, Star } from '@lucide/vue'
import type { EChartsOption } from 'echarts'

import BaseChart from '@/components/BaseChart.vue'
import { rankingRows } from '@/services/mockApi'
import { formatChangeRate, formatMoney, trendClass } from '@/utils/format'

const sectorTrend: EChartsOption = {
  grid: { left: 12, right: 10, top: 20, bottom: 10, outerBoundsMode: 'same', outerBoundsContain: 'axisLabel' },
  tooltip: { trigger: 'axis' },
  xAxis: { type: 'category', boundaryGap: false, data: ['09:30', '10:00', '10:30', '11:00', '11:30', '13:30', '14:00', '14:32'], axisLine: { lineStyle: { color: '#d7d0c2' } }, axisTick: { show: false }, axisLabel: { color: '#84908c', fontSize: 10 } },
  yAxis: { type: 'value', axisLabel: { formatter: '{value}%', color: '#84908c' }, splitLine: { lineStyle: { color: '#e8e1d5', type: 'dashed' } } },
  series: [{ type: 'line', smooth: true, symbol: 'none', lineStyle: { color: '#c94735', width: 2 }, areaStyle: { color: 'rgba(201,71,53,.1)' }, data: [0.1, 0.45, 0.8, 0.62, 1.18, 1.5, 2.1, 2.74] }],
}
</script>

<template>
  <div class="business-page page-enter">
    <RouterLink class="back-link" to="/sectors"><ArrowLeft :size="14" /> 返回板块</RouterLink>
    <section class="detail-hero sector-detail-hero">
      <div><span class="eyebrow">SECTOR · BK0475</span><h1>银行</h1><p>42 家成分股 · 金融服务 · 数据截止 14:32</p></div>
      <div class="detail-price"><strong class="trend-up">+2.74%</strong><span>今日涨跌幅</span></div>
      <button class="primary-button" type="button"><Sparkles :size="16" /> AI 板块解读</button>
    </section>

    <section class="detail-layout">
      <article class="chart-card">
        <header><div><span class="eyebrow">INTRADAY</span><h2>板块分时走势</h2></div><div class="segmented-tabs compact"><button class="active" type="button">今日</button><button type="button">5日</button><button type="button">20日</button></div></header>
        <BaseChart :option="sectorTrend" height="310px" />
      </article>
      <aside class="evidence-note">
        <span class="eyebrow">STRUCTURE</span><h2>强度拆解</h2>
        <dl><div><dt>上涨 / 下跌</dt><dd><b class="trend-up">38</b> / <b class="trend-down">4</b></dd></div><div><dt>板块成交额</dt><dd>821.00亿</dd></div><div><dt>领涨集中度</dt><dd>中等</dd></div><div><dt>相对大盘</dt><dd class="trend-up">+2.15%</dd></div></dl>
        <p>板块上涨覆盖面较广，暂非单一权重驱动。午后成交增速略有放缓，需观察尾盘承接。</p>
      </aside>
    </section>

    <section class="data-workbench component-table">
      <header class="section-heading"><div><span class="section-index">01</span><div><h2>成分股表现</h2><p>按涨跌幅排序</p></div></div><span class="page-header__time">共 42 只</span></header>
      <div class="quote-table-wrap"><table class="quote-table ranking-table"><thead><tr><th>股票</th><th>最新价</th><th>涨跌幅</th><th>成交额</th><th>换手率</th><th>操作</th></tr></thead><tbody><tr v-for="stock in rankingRows.slice(0, 4)" :key="stock.securityId"><td><RouterLink :to="`/stocks/${stock.securityId}`"><strong>{{ stock.securityName }}</strong><small>{{ stock.exchangeCode }}.{{ stock.securityCode }}</small></RouterLink></td><td class="mono">{{ stock.latestPrice }}</td><td :class="trendClass(stock.changeRate)" class="mono strong">{{ formatChangeRate(stock.changeRate) }}</td><td>{{ formatMoney(stock.tradeAmount) }}</td><td>{{ formatChangeRate(stock.turnoverRate) }}</td><td><button class="table-action" type="button"><Star :size="14" /></button></td></tr></tbody></table></div>
    </section>
  </div>
</template>
