<script setup lang="ts">
import { onMounted } from 'vue'

import { useRemoteData } from '@/composables/useRemoteData'
import { getAdminAiOverview, getAdminJobDefinitions, getAdminJobExecutions } from '@/services/adminApi'
import type { JobExecution } from '@/types/admin'

/**
 * 总览分区：只呈现有后端来源的事实——AI 运营总览（ADM-AI-01）、
 * 白名单任务定义（ADM-JOB-01）与最近执行记录（ADM-JOB-03）。
 * 原型里的"行情延迟 26s / 在线会话 486"没有数据源，不渲染。
 */
const overview = useRemoteData(() => getAdminAiOverview())
const definitions = useRemoteData(() => getAdminJobDefinitions())
const executions = useRemoteData(() =>
  getAdminJobExecutions({ page: 1, size: 10 }),
)

onMounted(() => {
  overview.reload()
  definitions.reload()
  executions.reload()
})

function executionSummary(execution: JobExecution): string {
  const counts = execution.counts
  if (!counts || !counts.countsAvailable) return '—'
  return `输入 ${counts.inputCount ?? 0} · 成功 ${counts.successCount ?? 0} · 失败 ${counts.failureCount ?? 0}`
}

function formatPercent(value?: number): string {
  return value == null ? '—' : `${(value * 100).toFixed(1)}%`
}

function formatNumber(value?: number): string {
  return value == null ? '—' : value.toLocaleString('zh-CN')
}
</script>

<template>
  <div>
    <section class="research-panel">
      <div class="section-heading">
        <div><span class="eyebrow">AI OPERATIONS</span><h2>AI 运营总览</h2></div>
        <button class="secondary-button" type="button" :disabled="overview.loading.value" @click="overview.reload()">
          {{ overview.loading.value ? '刷新中…' : '刷新' }}
        </button>
      </div>
      <p v-if="overview.error.value" class="admin-error">{{ overview.error.value.message }}</p>
      <div v-else-if="overview.data.value" class="ops-metrics">
        <article><span><strong>{{ formatNumber(overview.data.value.taskCount) }}</strong><small>任务总量</small></span></article>
        <article><span><strong>{{ formatPercent(overview.data.value.successRate) }}</strong><small>成功率</small></span></article>
        <article><span><strong>{{ formatNumber(overview.data.value.queuedCount) }}</strong><small>队列长度</small></span></article>
        <article><span><strong>{{ formatNumber(overview.data.value.runningCount) }}</strong><small>运行中</small></span></article>
        <article><span><strong>{{ formatNumber(overview.data.value.restrictedReportCount) }}</strong><small>受限报告</small></span></article>
        <article><span><strong>{{ formatNumber(overview.data.value.totalTokens) }}</strong><small>Token 用量</small></span></article>
      </div>
      <div v-else class="page-loading"><span /><span /><span /></div>
      <dl v-if="overview.data.value" class="admin-facts">
        <div><dt>失败 / 取消 / 超时</dt><dd>{{ overview.data.value.failedCount }} / {{ overview.data.value.canceledCount }} / {{ overview.data.value.timedOutCount }}</dd></div>
        <div><dt>首段耗时 p50 / p95</dt><dd>{{ overview.data.value.firstChunkLatencyP50Millis ?? '—' }} / {{ overview.data.value.firstChunkLatencyP95Millis ?? '—' }} ms</dd></div>
        <div><dt>总耗时 p50 / p95</dt><dd>{{ overview.data.value.totalLatencyP50Millis ?? '—' }} / {{ overview.data.value.totalLatencyP95Millis ?? '—' }} ms</dd></div>
        <div v-for="provider in overview.data.value.providers" :key="`${provider.providerCode}-${provider.modelCode}`">
          <dt>Provider {{ provider.providerCode }} · {{ provider.modelCode }}</dt>
          <dd>{{ provider.calls }} 次调用 · 成功率 {{ formatPercent(provider.successRate) }}</dd>
        </div>
      </dl>
    </section>

    <section class="research-panel">
      <div class="section-heading"><div><span class="eyebrow">SCHEDULED JOBS</span><h2>任务定义</h2></div></div>
      <p v-if="definitions.error.value" class="admin-error">{{ definitions.error.value.message }}</p>
      <table v-else-if="definitions.data.value" class="quote-table">
        <thead><tr><th>任务</th><th>调度周期</th><th>人工触发</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="definition in definitions.data.value" :key="definition.jobName">
            <td><strong>{{ definition.displayName }}</strong><small>{{ definition.jobName }}</small></td>
            <td>{{ definition.scheduleDescription }}</td>
            <td>{{ definition.supportsManualTrigger ? '支持' : '不支持' }}</td>
            <td><span class="status-label" :class="definition.enabled ? 'up' : 'flat'">{{ definition.enabled ? '启用' : '停用' }}</span></td>
          </tr>
        </tbody>
      </table>
      <div v-else class="page-loading"><span /><span /><span /></div>
    </section>

    <section class="research-panel">
      <div class="section-heading"><div><span class="eyebrow">RECENT EXECUTIONS</span><h2>最近执行记录</h2></div></div>
      <p v-if="executions.error.value" class="admin-error">{{ executions.error.value.message }}</p>
      <table v-else-if="executions.data.value && executions.data.value.items.length" class="quote-table">
        <thead><tr><th>任务</th><th>触发方式</th><th>状态</th><th>计数</th><th>开始时间</th></tr></thead>
        <tbody>
          <tr v-for="execution in executions.data.value.items" :key="execution.executionId">
            <td>{{ execution.jobName }}</td>
            <td>{{ execution.triggerType }}</td>
            <td>{{ execution.status }}</td>
            <td>{{ executionSummary(execution) }}</td>
            <td>{{ execution.startedAt ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else-if="executions.data.value" class="admin-empty">最近没有任务执行记录。</p>
      <div v-else class="page-loading"><span /><span /><span /></div>
    </section>
  </div>
</template>
