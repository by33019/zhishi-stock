<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { useRemoteData } from '@/composables/useRemoteData'
import {
  getAdminJobDefinitions,
  getAdminJobExecutions,
  retryAdminJobExecution,
  triggerAdminJob,
} from '@/services/adminApi'
import type { JobExecution } from '@/types/admin'

/**
 * 定时任务分区（ADM-JOB-01~05）。触发与重试都是 202：响应里的记录是
 * RUNNING 摘要，执行结果靠刷新执行历史确认——页面不假装"已经成功"。
 */
const definitions = useRemoteData(() => getAdminJobDefinitions())
const statusFilter = ref('')
const executions = useRemoteData(() =>
  getAdminJobExecutions({ status: statusFilter.value || undefined, page: 1, size: 20 }),
)
const notice = ref('')
const actionError = ref('')

onMounted(() => {
  definitions.reload()
  executions.reload()
})

function refreshExecutions() {
  void executions.reload()
}

async function trigger(jobName: string, displayName: string) {
  notice.value = ''
  actionError.value = ''
  try {
    const execution = await triggerAdminJob(
      jobName,
      { reason: '后台人工触发' },
      crypto.randomUUID(),
    )
    notice.value = `${displayName} 已提交（执行 ${execution.executionId}，RUNNING），结果以执行历史为准`
    await executions.reload()
  } catch (cause) {
    actionError.value = (cause as { message?: string }).message ?? '触发失败'
  }
}

async function retry(execution: JobExecution) {
  notice.value = ''
  actionError.value = ''
  try {
    const next = await retryAdminJobExecution(
      execution.executionId,
      { reason: `重试执行 ${execution.executionId}` },
      crypto.randomUUID(),
    )
    notice.value = `已创建重试执行 ${next.executionId}（attempt ${next.attemptNo}）`
    await executions.reload()
  } catch (cause) {
    actionError.value = (cause as { message?: string }).message ?? '重试失败'
  }
}

function retryable(execution: JobExecution): boolean {
  return execution.status === 'FAILED' || execution.status === 'PARTIALLY_SUCCEEDED'
}
</script>

<template>
  <div>
    <p v-if="notice" class="admin-notice">{{ notice }}</p>
    <p v-if="actionError" class="admin-error">{{ actionError }}</p>

    <section class="research-panel">
      <div class="section-heading"><div><span class="eyebrow">JOB DEFINITIONS</span><h2>任务定义</h2></div></div>
      <p v-if="definitions.error.value" class="admin-error">{{ definitions.error.value.message }}</p>
      <table v-else-if="definitions.data.value" class="quote-table">
        <thead><tr><th>任务</th><th>调度周期</th><th>作用范围</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="definition in definitions.data.value" :key="definition.jobName">
            <td><strong>{{ definition.displayName }}</strong><small>{{ definition.handlerName }}</small></td>
            <td>{{ definition.scheduleDescription }}</td>
            <td>{{ definition.defaultScopeKey ?? '—' }}</td>
            <td>
              <button
                v-if="definition.supportsManualTrigger && definition.enabled"
                type="button"
                @click="trigger(definition.jobName, definition.displayName)"
              >
                人工触发
              </button>
              <span v-else class="status-label flat">不可触发</span>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="page-loading"><span /><span /><span /></div>
    </section>

    <section class="research-panel">
      <div class="section-heading">
        <div><span class="eyebrow">EXECUTION HISTORY</span><h2>执行历史</h2></div>
        <div class="admin-search">
          <select v-model="statusFilter" aria-label="按状态筛选" @change="refreshExecutions">
            <option value="">全部状态</option>
            <option value="RUNNING">RUNNING</option>
            <option value="SUCCEEDED">SUCCEEDED</option>
            <option value="FAILED">FAILED</option>
            <option value="PARTIALLY_SUCCEEDED">PARTIALLY_SUCCEEDED</option>
            <option value="CANCELED">CANCELED</option>
          </select>
          <button class="secondary-button" type="button" @click="refreshExecutions">刷新</button>
        </div>
      </div>
      <p v-if="executions.error.value" class="admin-error">{{ executions.error.value.message }}</p>
      <table v-else-if="executions.data.value && executions.data.value.items.length" class="quote-table">
        <thead><tr><th>执行</th><th>任务</th><th>触发</th><th>状态</th><th>计数</th><th>错误</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="execution in executions.data.value.items" :key="execution.executionId">
            <td><strong>#{{ execution.executionId }}</strong><small>attempt {{ execution.attemptNo }}</small></td>
            <td>{{ execution.jobName }}</td>
            <td>{{ execution.triggerType }}</td>
            <td>{{ execution.status }}</td>
            <td>
              <template v-if="execution.counts?.countsAvailable">
                {{ execution.counts?.inputCount ?? 0 }}/{{ execution.counts?.successCount ?? 0 }}/{{ execution.counts?.failureCount ?? 0 }}
              </template>
              <template v-else>—</template>
            </td>
            <td>{{ execution.errorCategory ?? '—' }}</td>
            <td>
              <button v-if="retryable(execution)" type="button" @click="retry(execution)">重试</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else-if="executions.data.value" class="admin-empty">没有符合条件的执行记录。</p>
      <div v-else class="page-loading"><span /><span /><span /></div>
    </section>
  </div>
</template>
