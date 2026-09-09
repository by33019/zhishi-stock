<script setup lang="ts">
import { Download, MoreHorizontal, Search, Sparkles } from '@lucide/vue'
import { ref } from 'vue'

import PageHeader from '@/components/PageHeader.vue'

const activeType = ref('全部研究')
const reports = [
  { id: 'R-0908-01', title: '浦发银行今日放量上涨的证据拆解', type: '个股研究', target: 'SH.600000', time: '今天 14:33', quality: '证据完整', summary: '板块共振、成交放大与经营数据事件共同驱动，短期强于市场。' },
  { id: 'R-0908-02', title: '银行板块午后活跃原因分析', type: '板块研究', target: 'BK0475', time: '今天 13:48', quality: '证据完整', summary: '大金融方向成交同步放大，板块上涨覆盖面较广。' },
  { id: 'R-0907-03', title: '宁德时代与中芯国际强弱对比', type: '对比研究', target: '2 个标的', time: '昨天 15:21', quality: '部分证据', summary: '两者短期驱动因素不同，暂不适合仅按涨跌幅判断强弱。' },
  { id: 'R-0906-04', title: '本周 A 股市场结构复盘', type: '市场复盘', target: '中国 A 股', time: '09-06 16:12', quality: '证据完整', summary: '风险偏好边际改善，成交仍集中在少数高景气方向。' },
]
</script>

<template>
  <div class="business-page page-enter">
    <PageHeader eyebrow="RESEARCH ARCHIVE" title="分析历史" description="保存每次研究的问题、证据和结果，支持检索、继续追问与再次分析。">
      <button class="secondary-button" type="button"><Download :size="15" /> 批量导出</button>
    </PageHeader>
    <section class="history-layout">
      <aside class="history-filter"><span>研究类型</span><button v-for="item in ['全部研究', '个股研究', '板块研究', '市场复盘', '对比研究']" :key="item" :class="{ active: activeType === item }" type="button" @click="activeType = item">{{ item }}<b>{{ item === '全部研究' ? 24 : item === '个股研究' ? 12 : 3 }}</b></button><div /><span>时间范围</span><button class="active" type="button">近 30 天</button><button type="button">近 3 个月</button><button type="button">全部时间</button></aside>
      <main class="history-main"><header><label class="inline-search"><Search :size="15" /><input placeholder="搜索问题、标的或报告内容" /></label><select><option>按更新时间排序</option><option>按创建时间排序</option></select></header><div class="report-list"><article v-for="report in reports" :key="report.id"><div class="report-date"><strong>{{ report.time.split(' ')[0] }}</strong><span>{{ report.time.split(' ')[1] }}</span></div><div class="report-info"><div><span>{{ report.type }}</span><span>{{ report.target }}</span><b :class="{ limited: report.quality === '部分证据' }">{{ report.quality }}</b></div><h2>{{ report.title }}</h2><p>{{ report.summary }}</p></div><div class="report-actions"><button type="button"><Sparkles :size="14" /> 再次分析</button><button type="button"><MoreHorizontal :size="16" /></button></div></article></div></main>
    </section>
  </div>
</template>
