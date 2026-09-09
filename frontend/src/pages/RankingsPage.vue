<script setup lang="ts">
import { Download, Filter, Search, Sparkles, Star } from '@lucide/vue'
import { computed, ref } from 'vue'

import PageHeader from '@/components/PageHeader.vue'
import { rankingRows } from '@/services/mockApi'
import { formatChangeRate, formatMoney, formatVolume, trendClass } from '@/utils/format'

const activeList = ref('涨幅榜')
const keyword = ref('')
const lists = ['涨幅榜', '跌幅榜', '成交额榜', '换手率榜']
const filteredRows = computed(() => rankingRows.filter((row) => `${row.securityName}${row.securityCode}`.includes(keyword.value)))
</script>

<template>
  <div class="business-page page-enter">
    <PageHeader eyebrow="MARKET RANKING" title="行情榜单" description="用涨跌、成交与换手交叉识别活跃标的，所有数据均标注最新同步时间。" data-time="09-08 14:32">
      <button class="secondary-button" type="button"><Download :size="15" /> 导出 Excel</button>
    </PageHeader>

    <section class="ranking-summary">
      <div><span>领涨标的</span><strong>浦发银行</strong><b class="trend-up">+5.47%</b></div>
      <div><span>成交额最高</span><strong>宁德时代</strong><b>135.20亿</b></div>
      <div><span>市场中位数</span><strong>+0.62%</strong><b class="trend-up">偏强</b></div>
      <p><Sparkles :size="16" /><span><strong>AI 观察</strong>大金融放量居前，但高位标的分化扩大，追踪成交持续性优先于单日涨幅。</span></p>
    </section>

    <section class="data-workbench">
      <header class="workbench-toolbar">
        <div class="segmented-tabs">
          <button v-for="item in lists" :key="item" :class="{ active: activeList === item }" type="button" @click="activeList = item">{{ item }}</button>
        </div>
        <div class="toolbar-actions">
          <label class="inline-search"><Search :size="14" /><input v-model="keyword" aria-label="筛选榜单股票" placeholder="筛选股票" /></label>
          <button class="filter-button" type="button"><Filter :size="14" /> 沪深京</button>
        </div>
      </header>
      <div class="quote-table-wrap">
        <table class="quote-table ranking-table">
          <thead><tr><th>排名</th><th>股票</th><th>最新价</th><th>涨跌额</th><th>涨跌幅</th><th>成交量</th><th>成交额</th><th>换手率</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="(stock, index) in filteredRows" :key="stock.securityId">
              <td class="rank-cell">{{ String(index + 1).padStart(2, '0') }}</td>
              <td><RouterLink :to="`/stocks/${stock.securityId}`"><strong>{{ stock.securityName }}</strong><small>{{ stock.exchangeCode }}.{{ stock.securityCode }}</small></RouterLink></td>
              <td class="mono">{{ stock.latestPrice }}</td>
              <td :class="trendClass(stock.changeAmount)" class="mono">{{ stock.changeAmount }}</td>
              <td :class="trendClass(stock.changeRate)" class="mono strong">{{ formatChangeRate(stock.changeRate) }}</td>
              <td class="mono">{{ formatVolume(stock.tradeVolume) }}</td>
              <td class="mono">{{ formatMoney(stock.tradeAmount) }}</td>
              <td class="mono">{{ formatChangeRate(stock.turnoverRate) }}</td>
              <td><button class="table-action" type="button" aria-label="加入自选"><Star :size="14" /></button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="table-footer"><span>共 5,248 个交易标的 · 第 1 / 210 页</span><div><button type="button">上一页</button><button class="active" type="button">1</button><button type="button">2</button><button type="button">下一页</button></div></footer>
    </section>
  </div>
</template>
