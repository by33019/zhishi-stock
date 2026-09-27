<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { useRemoteData } from '@/composables/useRemoteData'
import {
  cancelAdminAiTask,
  getAdminAiFeedbackStatistics,
  getAdminAiOverview,
  getAdminAiTasks,
  getAdminAiUsage,
} from '@/services/adminApi'
import type { AdminAiTaskSummary, AiTaskStatus } from '@/types/admin'

/**
 * AI 运营分区（ADM-AI-01/02/04/05/06）。任务列表只有元数据——
 * 后端契约不提供用户问题与报告正文的读取，页面不请求、不渲染。
 */
const groupBy = ref<'DAY' | 'PROVIDER' | 'MODEL' | 'SCENE'>('DAY')
const statusFilter = ref('')
const notice = ref('')
const actionError = ref('')

const overview = useRemoteData(() => getAdminAiOverview())
const usage = useRemoteData(() => getAdminAiUsage({ groupBy: groupBy.value }))
const tasks = useRemoteData(() =>
  getAdminAiTasks({ status: (statusFilter.value || undefined) as AiTaskStatus | undefined }),
)
const feedback = useRemoteData(() => getAdminAiFeedbackStatistics())

onMounted(() => {
  overview.reload()
  tasks.reload()
  feedback.reload()
  void usage.reload()
})

function switchGroupBy(next: 'DAY' | 'PROVIDER' | 'MODEL' | 'SCENE') {
  groupBy.value = next
  void usage.reload()
}

function filterTasks() {
  void tasks.reload()
}

async function cancel(task: AdminAiTaskSummary) {
  notice.value = ''
  actionError.value = ''
  try {
    const result = await cancelAdminAiTask(task.taskId, crypto.randomUUID(), '后台取消卡死任务')
    notice.value = `任务 ${result.taskId} 的取消意图已置入（状态 ${result.status}），由 worker 兑现`
    await tasks.reload()
  } catch (cause) {
    actionError.value = (cause as { message?: string }).message ?? '取消失败'
  }
}

function cancellable(task: AdminAiTaskSummary): boolean {
  return !['COMPLETED', 'CANCELED', 'FAILED', 'TIMED_OUT'].includes(task.status)
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
    <p v-if="notice" class="admin-notice">{{ notice }}</p>
    <p v-if="actionError" class="admin-error">{{ actionError }}</p>

    <section class="research-panel">
      <div class="section-heading">
        <div><span class="eyebrow">AI OVERVIEW</span><h2>运营总览</h2></div>
        <button class="secondary-button" type="button" @click="overview.reload()">刷新</button>
      </div>
      <p v-if="overview.error.value" class="admin-error">{{ overview.error.value.message }}</p>
      <div v-else-if="overview.data.value" class="ops-metrics">
        <article><span><strong>{{ formatNumber(overview.data.value.taskCount) }}</strong><small>任务总量</small></span></article>
        <article><span><strong>{{ formatPercent(overview.data.value.successRate) }}</strong><small>成功率</small></span></article>
        <article><span><strong>{{ formatNumber(overview.data.value.queuedCount) }}</strong><small>队列长度</small></span></article>
        <article><span><strong>{{ formatNumber(overview.data.value.totalTokens) }}</strong><small>Token 用量</small></span></article>
      </div>
      <div v-else class="page-loading"><span /><span /><span /></div>
    </section>

    <section class="research-panel">
      <div class="section-heading">
        <div><span class="eyebrow">USAGE</span><h2>分组用量</h2></div>
        <div class="admin-search">
          <select :value="groupBy" aria-label="分组维度" @change="switchGroupBy(($event.target as HTMLSelectElement).value as typeof groupBy)">
            <option value="DAY">按日</option>
            <option value="PROVIDER">按 Provider</option>
            <option value="MODEL">按模型</option>
            <option value="SCENE">按场景</option>
          </select>
        </div>
      </div>
      <p v-if="usage.error.value" class="admin-error">{{ usage.error.value.message }}</p>
      <table v-else-if="usage.data.value && usage.data.value.length" class="quote-table">
        <thead><tr><th>分组</th><th>调用</th><th>成功率</th><th>Prompt</th><th>Completion</th><th>缓存</th><th>平均总耗时</th></tr></thead>
        <tbody>
          <tr v-for="group in usage.data.value" :key="group.groupKey">
            <td><strong>{{ group.groupKey }}</strong></td>
            <td>{{ group.calls }}</td>
            <td>{{ formatPercent(group.successRate) }}</td>
            <td>{{ formatNumber(group.promptTokens) }}</td>
            <td>{{ formatNumber(group.completionTokens) }}</td>
            <td>{{ formatNumber(group.cachedTokens) }}</td>
            <td>{{ group.avgTotalLatencyMillis == null ? '—' : `${group.avgTotalLatencyMillis} ms` }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else-if="usage.data.value" class="admin-empty">窗口内没有 AI 调用。</p>
      <div v-else class="page-loading"><span /><span /><span /></div>
    </section>

    <section class="research-panel">
      <div class="section-heading">
        <div><span class="eyebrow">TASKS</span><h2>任务列表</h2></div>
        <div class="admin-search">
          <select v-model="statusFilter" aria-label="按状态筛选" @change="filterTasks">
            <option value="">全部状态</option>
            <option value="QUEUED">QUEUED</option>
            <option value="RUNNING">RUNNING</option>
            <option value="COMPLETED">COMPLETED</option>
            <option value="FAILED">FAILED</option>
            <option value="CANCELED">CANCELED</option>
            <option value="TIMED_OUT">TIMED_OUT</option>
          </select>
        </div>
      </div>
      <p v-if="tasks.error.value" class="admin-error">{{ tasks.error.value.message }}</p>
      <table v-else-if="tasks.data.value && tasks.data.value.items.length" class="quote-table">
        <thead><tr><th>任务</th><th>场景</th><th>状态</th><th>目标</th><th>Provider</th><th>创建时间</th><th></th></tr></thead>
        <tbody>
          <tr v-for="task in tasks.data.value.items" :key="task.taskId">
            <td><strong>#{{ task.taskId }}</strong><small>{{ task.traceId }}</small></td>
            <td>{{ task.scene }}</td>
            <td>{{ task.status }}</td>
            <td>{{ task.targets.map((target) => target.targetName).join('、') || '—' }}</td>
            <td>{{ task.providerCode ?? '—' }}</td>
            <td>{{ task.createdAt }}</td>
            <td>
              <button v-if="cancellable(task)" type="button" @click="cancel(task)">取消</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else-if="tasks.data.value" class="admin-empty">没有符合条件的任务。</p>
      <div v-else class="page-loading"><span /><span /><span /></div>
    </section>

    <section class="research-panel">
      <div class="section-heading"><div><span class="eyebrow">FEEDBACK</span><h2>反馈统计</h2></div></div>
      <p v-if="feedback.error.value" class="admin-error">{{ feedback.error.value.message }}</p>
      <div v-else-if="feedback.data.value" class="admin-facts">
        <div><dt>总反馈</dt><dd>{{ feedback.data.value.total }}</dd></div>
        <div><dt>有帮助 / 没帮助</dt><dd>{{ feedback.data.value.helpfulCount }} / {{ feedback.data.value.notHelpfulCount }}</dd></div>
        <div><dt>好评率</dt><dd>{{ formatPercent(feedback.data.value.helpfulRate) }}</dd></div>
        <div v-for="reason in feedback.data.value.reasonCounts" :key="reason.reasonCode">
          <dt>原因 {{ reason.reasonCode ?? '未选择' }}</dt><dd>{{ reason.count }}</dd>
        </div>
      </div>
      <div v-else class="page-loading"><span /><span /><span /></div>
    </section>
  </div>
</template>
