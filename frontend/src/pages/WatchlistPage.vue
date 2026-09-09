<script setup lang="ts">
import { MoreHorizontal, Plus, Sparkles, Star, Trash2 } from '@lucide/vue'
import { computed, ref } from 'vue'

import PageHeader from '@/components/PageHeader.vue'
import { rankingRows } from '@/services/mockApi'
import { formatChangeRate, formatMoney, trendClass } from '@/utils/format'

const activeGroup = ref('重点观察')
const groups = [{ name: '重点观察', count: 5 }, { name: '金融', count: 2 }, { name: '科技成长', count: 2 }, { name: '高股息', count: 1 }]
const watchlist = computed(() => activeGroup.value === '重点观察' ? rankingRows : rankingRows.slice(0, 2))
</script>

<template>
  <div class="business-page page-enter">
    <PageHeader eyebrow="PERSONAL RADAR" title="我的自选" description="把持续跟踪的标的、变化与研究入口组织在同一个观察面板。" data-time="09-08 14:32">
      <button class="primary-button" type="button"><Plus :size="15" /> 添加股票</button>
    </PageHeader>
    <section class="watchlist-layout">
      <aside class="watch-groups"><header><span>自选分组</span><button type="button"><Plus :size="14" /></button></header><button v-for="group in groups" :key="group.name" :class="{ active: activeGroup === group.name }" type="button" @click="activeGroup = group.name"><span><Star :size="13" />{{ group.name }}</span><b>{{ group.count }}</b></button><footer>拖动可调整分组顺序</footer></aside>
      <div class="watch-content">
        <section class="watch-insight"><div><span class="eyebrow">DAILY WATCH</span><h2>{{ activeGroup }} · 今日摘要</h2><p>5 只标的中 3 只上涨，浦发银行涨幅与成交额同步居首；消费标的表现偏弱。</p></div><button type="button"><Sparkles :size="15" /> 分析本组</button></section>
        <div class="watch-cards">
          <article v-for="stock in watchlist" :key="stock.securityId">
            <header><RouterLink :to="`/stocks/${stock.securityId}`"><strong>{{ stock.securityName }}</strong><span>{{ stock.exchangeCode }}.{{ stock.securityCode }}</span></RouterLink><button type="button"><MoreHorizontal :size="16" /></button></header>
            <div class="watch-price"><strong>{{ stock.latestPrice }}</strong><span :class="trendClass(stock.changeRate)">{{ formatChangeRate(stock.changeRate) }}</span></div>
            <svg viewBox="0 0 200 48" preserveAspectRatio="none"><polyline fill="none" :class="trendClass(stock.changeRate)" stroke="currentColor" stroke-width="2" :points="stock.sparkline.map((point, i) => `${i * 33.3},${44 - ((point - Math.min(...stock.sparkline)) / (Math.max(...stock.sparkline) - Math.min(...stock.sparkline) || 1)) * 38}`).join(' ')" /></svg>
            <dl><div><dt>成交额</dt><dd>{{ formatMoney(stock.tradeAmount) }}</dd></div><div><dt>换手率</dt><dd>{{ formatChangeRate(stock.turnoverRate) }}</dd></div></dl>
            <footer><RouterLink :to="`/stocks/${stock.securityId}`">查看详情</RouterLink><button type="button"><Sparkles :size="13" /> AI 解读</button><button type="button" aria-label="移出自选"><Trash2 :size="13" /></button></footer>
          </article>
        </div>
      </div>
    </section>
  </div>
</template>
