<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { useRemoteData } from '@/composables/useRemoteData'
import { getAdminOperationLog, getAdminOperationLogs } from '@/services/adminApi'
import type { AdminOperationLogDetail } from '@/types/admin'

/**
 * 操作日志分区（LOG-01/02）。日志是审计记录：只有查询，没有编辑与删除。
 * 参数摘要是后端脱敏后的文本，页面原样展示（含 `password=***` 之类的遮蔽）。
 */
const operation = ref('')
const logs = useRemoteData(() =>
  getAdminOperationLogs({ operation: operation.value || undefined, page: 1, size: 20 }),
)
const detail = ref<AdminOperationLogDetail>()
const detailError = ref('')

onMounted(() => logs.reload())

async function openDetail(logId: number) {
  detailError.value = ''
  try {
    detail.value = await getAdminOperationLog(logId)
  } catch (cause) {
    detailError.value = (cause as { message?: string }).message ?? '加载详情失败'
  }
}

function search() {
  void logs.reload()
}
</script>

<template>
  <div>
    <section class="research-panel">
      <div class="section-heading">
        <div><span class="eyebrow">AUDIT TRAIL</span><h2>操作日志</h2></div>
        <form class="admin-search" @submit.prevent="search">
          <input v-model="operation" type="search" placeholder="操作名（如 ADMIN_USER_CREATE）" aria-label="按操作名筛选" />
          <button class="secondary-button" type="submit">筛选</button>
        </form>
      </div>

      <p v-if="logs.error.value" class="admin-error">{{ logs.error.value.message }}</p>
      <table v-else-if="logs.data.value && logs.data.value.items.length" class="quote-table">
        <thead><tr><th>时间</th><th>操作人</th><th>操作</th><th>结果</th><th>请求</th><th>IP</th><th></th></tr></thead>
        <tbody>
          <tr v-for="log in logs.data.value.items" :key="log.logId">
            <td>{{ log.createdAt }}</td>
            <td>{{ log.username ?? log.userId ?? '—' }}</td>
            <td><strong>{{ log.operation }}</strong></td>
            <td>
              <span class="status-label" :class="log.resultStatus === 'SUCCESS' ? 'up' : 'down'">
                {{ log.resultStatus }}
              </span>
            </td>
            <td><small>{{ log.httpMethod }} {{ log.requestUri }}</small></td>
            <td>{{ log.ip ?? '—' }}</td>
            <td><button type="button" @click="openDetail(log.logId)">详情</button></td>
          </tr>
        </tbody>
      </table>
      <p v-else-if="logs.data.value" class="admin-empty">没有符合条件的日志。</p>
      <div v-else class="page-loading"><span /><span /><span /></div>
    </section>

    <section v-if="detail" class="research-panel">
      <div class="section-heading"><div><span class="eyebrow">LOG DETAIL</span><h2>日志 #{{ detail.logId }}</h2></div></div>
      <p v-if="detailError" class="admin-error">{{ detailError }}</p>
      <dl class="admin-facts">
        <div><dt>Trace ID</dt><dd>{{ detail.traceId ?? '—' }}</dd></div>
        <div><dt>控制层方法</dt><dd>{{ detail.method ?? '—' }}</dd></div>
        <div><dt>耗时</dt><dd>{{ detail.durationMillis == null ? '—' : `${detail.durationMillis} ms` }}</dd></div>
        <div><dt>遗留用户引用</dt><dd>{{ detail.legacyUserRef ?? '—' }}</dd></div>
        <div><dt>脱敏参数摘要</dt><dd>{{ detail.paramsSummary ?? '—' }}</dd></div>
      </dl>
    </section>
  </div>
</template>
