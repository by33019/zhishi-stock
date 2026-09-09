<script setup lang="ts">
import { ArrowUpRight, Grid2X2, List, Sparkles } from '@lucide/vue'
import { onMounted, ref } from 'vue'

import PageHeader from '@/components/PageHeader.vue'
import { getMarketOverview } from '@/services/mockApi'
import type { SectorQuote } from '@/types/domain'
import { formatChangeRate, formatMoney, trendClass } from '@/utils/format'

const sectors = ref<SectorQuote[]>([])
const view = ref<'grid' | 'list'>('grid')

function setView(nextView: 'grid' | 'list') {
  view.value = nextView
}

onMounted(async () => {
  sectors.value = (await getMarketOverview()).data.sectors
})
</script>

<template>
  <div class="business-page page-enter">
    <PageHeader eyebrow="SECTOR INTELLIGENCE" title="板块分析" description="从涨幅、资金与领涨结构观察主题强度，避免只看单一排行。" data-time="09-08 14:32">
      <div class="view-switch"><button :class="{ active: view === 'grid' }" type="button" @click="setView('grid')"><Grid2X2 :size="15" /></button><button :class="{ active: view === 'list' }" type="button" @click="setView('list')"><List :size="15" /></button></div>
    </PageHeader>

    <section class="sector-brief">
      <div><span class="eyebrow">AI SECTOR NOTE</span><h2>金融领涨，科技成交活跃</h2><p>银行板块涨幅领先且成交额同步放大；半导体设备保持高活跃，但板块内部强弱差异需要进一步验证。</p></div>
      <button type="button"><Sparkles :size="16" /> 生成板块综述</button>
    </section>

    <section class="sector-board" :class="`is-${view}`">
      <RouterLink v-for="(sector, index) in sectors" :key="sector.sectorId" :to="`/sectors/${sector.sectorId}`" class="sector-card">
        <header><span>0{{ index + 1 }}</span><b :class="trendClass(sector.changeRate)">{{ formatChangeRate(sector.changeRate) }}</b></header>
        <h2>{{ sector.sectorName }}</h2>
        <p>{{ sector.companyCount }} 家成分股 · {{ sector.sectorCode }}</p>
        <div class="sector-meter"><i :style="{ width: `${82 - index * 12}%` }" /></div>
        <dl><div><dt>成交额</dt><dd>{{ formatMoney(sector.tradeAmount) }}</dd></div><div><dt>领涨股</dt><dd>{{ sector.leadingStock }}</dd></div></dl>
        <footer><span>查看板块证据</span><ArrowUpRight :size="15" /></footer>
      </RouterLink>
    </section>
  </div>
</template>
