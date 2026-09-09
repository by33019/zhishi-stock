<script setup lang="ts">
import { BookOpenText, Check, Copy, Download, Plus, Send, Sparkles, Square, ThumbsDown, ThumbsUp } from '@lucide/vue'
import { ref } from 'vue'

import type { AiReport } from '@/types/domain'

const question = ref('')
const generating = ref(false)
const report = ref<AiReport>()
const selectedScene = ref('个股研究')
const templates = ['为什么浦发银行今日放量上涨？', '梳理银行板块的主要机会与风险', '对比宁德时代与中芯国际的近期强弱']

function chooseTemplate(prompt: string) {
  question.value = prompt
}

function generateReport() {
  if (!question.value) question.value = templates[0]!
  generating.value = true
  setTimeout(() => {
    report.value = {
      reportId: '90012026090801',
      qualityStatus: 'VALID',
      coreConclusion: '浦发银行今日上涨更可能由银行板块共振、成交放大与经营数据事件共同驱动，当前证据支持“短期强于市场”，但不足以推导持续上涨。',
      quoteEvidence: '截至 14:32，股价 12.35 元，涨幅 5.47%，成交额 39.82 亿元；银行板块同期上涨 2.74%，个股相对板块超额约 2.73 个百分点。',
      eventClues: '公司上半年经营数据披露后，市场对净息差企稳与资产质量稳定的关注升温；同时午后大金融整体成交放大。',
      riskAndUncertainty: '板块尾盘成交增速已放缓，单日放量不能确认趋势延续；利率环境和资产质量变化仍可能反向影响估值。',
      generatedAt: '2026-09-08T14:33:12+08:00',
      evidence: [
        { evidenceNo: 1, evidenceType: 'QUOTE', sourceTitle: '浦发银行实时行情', evidenceSummary: '涨幅 5.47%，成交额 39.82 亿元', dataTime: '2026-09-08T14:32:18+08:00' },
        { evidenceNo: 2, evidenceType: 'SECTOR', sourceTitle: '银行板块行情', evidenceSummary: '板块上涨 2.74%，38 只成分股上涨', dataTime: '2026-09-08T14:32:12+08:00' },
        { evidenceNo: 3, evidenceType: 'ANNOUNCEMENT', sourceTitle: '上半年经营数据公告', evidenceSummary: '净息差环比企稳，资产质量保持稳定', dataTime: '2026-09-08T13:56:00+08:00' },
      ],
    }
    generating.value = false
  }, 180)
}
</script>

<template>
  <div class="ai-workspace-page page-enter">
    <header class="ai-workspace-head"><div><span class="eyebrow">AI RESEARCH STUDIO</span><h1>把问题变成可复核的研究</h1><p>选择分析场景，组合标的与时间范围，AI 会把结论、行情依据、事件线索和风险边界放在同一份报告中。</p></div><div><span class="live-dot" /> AI 服务正常 · 平均首段 2.8 秒</div></header>
    <section class="ai-studio-grid">
      <aside class="research-setup">
        <header><span>研究设置</span><button type="button"><Plus :size="14" /> 新建</button></header>
        <label>分析场景<select v-model="selectedScene"><option>个股研究</option><option>板块研究</option><option>市场复盘</option><option>多标的对比</option></select></label>
        <label>目标标的<div class="stock-selector"><span>浦发银行 <small>SH.600000</small></span><button type="button">×</button></div></label>
        <label>时间范围<select><option>近 5 个交易日</option><option>近 20 个交易日</option><option>自定义</option></select></label>
        <div class="setup-divider" />
        <span class="setup-label">问题模板</span>
        <button v-for="(template, index) in templates" :key="template" :data-testid="index === 0 ? 'prompt-template' : undefined" class="template-button" type="button" @click="chooseTemplate(template)"><span>0{{ index + 1 }}</span>{{ template }}</button>
        <p class="quota-note">今日剩余 18 次分析 · 单次最多 3 个对比标的</p>
      </aside>

      <main class="report-canvas">
        <div class="question-composer"><textarea v-model="question" rows="3" placeholder="描述你希望验证的问题，例如：为什么浦发银行今日放量上涨？" /><div><span><Check :size="13" /> 已纳入实时行情与授权资讯</span><button data-testid="generate-report" type="button" :disabled="generating" @click="generateReport"><Square v-if="generating" :size="14" /><Send v-else :size="14" />{{ generating ? '生成中' : '开始分析' }}</button></div></div>

        <div v-if="generating" class="report-generating"><span class="ai-orbit"><Sparkles :size="22" /></span><h2>正在交叉验证证据</h2><p>行情快照 → 板块对比 → 事件关联 → 风险检查</p><div><i /><i /><i /></div></div>

        <article v-else-if="report" class="ai-report">
          <header><div><span class="quality-badge">证据完整</span><span>生成于 14:33</span></div><div><button type="button"><Copy :size="14" /> 复制</button><button type="button"><Download :size="14" /> 导出</button></div></header>
          <section class="report-lead"><span class="report-index">01</span><div><h2>核心结论</h2><p>{{ report.coreConclusion }}</p></div></section>
          <section><span class="report-index">02</span><div><h2>量价依据</h2><p>{{ report.quoteEvidence }}</p></div></section>
          <section><span class="report-index">03</span><div><h2>资讯与事件线索</h2><p>{{ report.eventClues }}</p></div></section>
          <section class="risk-section"><span class="report-index">04</span><div><h2>风险与不确定性</h2><p>{{ report.riskAndUncertainty }}</p></div></section>
          <footer><p>本报告由 AI 基于公开行情和授权信息生成，仅供研究参考，不构成投资建议。</p><div><span>这份分析有帮助吗？</span><button type="button"><ThumbsUp :size="14" /></button><button type="button"><ThumbsDown :size="14" /></button></div></footer>
        </article>

        <div v-else class="report-empty"><span><BookOpenText :size="28" /></span><h2>研究画布等待你的问题</h2><p>选择左侧模板或直接输入问题，我们会先检查数据新鲜度，再生成带来源引用的分析。</p></div>
      </main>

      <aside class="evidence-drawer">
        <header><span>来源引用</span><b>{{ report?.evidence.length ?? 0 }}</b></header>
        <div v-if="report" class="evidence-cards"><article v-for="item in report.evidence" :key="item.evidenceNo"><div><span>0{{ item.evidenceNo }}</span><b>{{ item.evidenceType }}</b></div><h3>{{ item.sourceTitle }}</h3><p>{{ item.evidenceSummary }}</p><small>数据时间 {{ item.dataTime.slice(11, 16) }}</small></article></div>
        <div v-else class="evidence-empty"><p>报告生成后，引用将按出现顺序列在这里。</p></div>
      </aside>
    </section>
  </div>
</template>
