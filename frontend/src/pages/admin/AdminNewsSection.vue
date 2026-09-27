<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { useRemoteData } from '@/composables/useRemoteData'
import {
  createAdminNewsRelation,
  createAdminNewsSource,
  deleteAdminNewsRelation,
  getAdminNewsRelations,
  getAdminNewsSources,
  patchAdminNewsSource,
  reviewAdminNewsRelation,
} from '@/services/adminApi'
import type { AdminNewsRelation, AdminNewsSource, NewsRelationStatus } from '@/types/admin'

/**
 * 资讯治理分区（ADM-NEWS-01~08 的页面覆盖）。
 *
 * 关联列表缺省查 CANDIDATE（契约："默认查看低置信候选，供人工复核"）。
 * 来源编辑只允许改授权区间 / AI 许可 / 运行状态——授权状态是服务端
 * 从区间推导的，前端不提供"直接标为有效"的入口。
 */
const relationsStatus = ref<NewsRelationStatus>('CANDIDATE')
const sources = useRemoteData(() => getAdminNewsSources())
const relations = useRemoteData(() => getAdminNewsRelations({ relationStatus: relationsStatus.value }))

const notice = ref('')
const actionError = ref('')

onMounted(() => {
  sources.reload()
  relations.reload()
})

function switchRelationsStatus(status: NewsRelationStatus) {
  relationsStatus.value = status
  void relations.reload()
}

async function toggleAiAnalysis(source: AdminNewsSource) {
  notice.value = ''
  actionError.value = ''
  try {
    await patchAdminNewsSource(source.sourceId, source.version, {
      allowAiAnalysis: !source.allowAiAnalysis,
    })
    notice.value = `来源 ${source.sourceCode} 的 AI 使用许可已${source.allowAiAnalysis ? '关闭' : '开启'}`
    await sources.reload()
  } catch (cause) {
    actionError.value = (cause as { message?: string }).message ?? '修改失败'
  }
}

async function suspend(source: AdminNewsSource) {
  notice.value = ''
  actionError.value = ''
  try {
    await patchAdminNewsSource(source.sourceId, source.version, {
      authorizationStatus: 'SUSPENDED',
      status: 'DISABLED',
    })
    notice.value = `来源 ${source.sourceCode} 已暂停授权并停用采集`
    await sources.reload()
  } catch (cause) {
    actionError.value = (cause as { message?: string }).message ?? '暂停失败'
  }
}

async function createSource() {
  const code = window.prompt('新来源编码（唯一，如 sim-media-b）')
  if (!code) return
  const name = window.prompt('来源名称') ?? ''
  if (!name) return
  const from = window.prompt('授权起始日（YYYY-MM-DD，可空）') || undefined
  const to = window.prompt('授权截止日（YYYY-MM-DD，可空）') || undefined
  notice.value = ''
  actionError.value = ''
  try {
    await createAdminNewsSource(
      { sourceCode: code, sourceName: name, sourceType: 'MEDIA', rightsValidFrom: from, rightsValidTo: to },
      crypto.randomUUID(),
    )
    notice.value = `来源 ${code} 已创建；授权状态由服务端按授权区间推导`
    await sources.reload()
  } catch (cause) {
    actionError.value = (cause as { message?: string }).message ?? '创建失败'
  }
}

async function review(relation: AdminNewsRelation, decision: 'CONFIRMED' | 'REJECTED') {
  notice.value = ''
  actionError.value = ''
  try {
    await reviewAdminNewsRelation(relation.relationId, decision)
    notice.value = `关联 ${relation.relationId} 已${decision === 'CONFIRMED' ? '确认' : '拒绝'}`
    await relations.reload()
  } catch (cause) {
    actionError.value = (cause as { message?: string }).message ?? '复核失败'
  }
}

function authorizeLabel(source: AdminNewsSource): string {
  if (source.authorizationStatus === 'AUTHORIZED') return '授权有效'
  if (source.authorizationStatus === 'EXPIRED') return '授权已到期'
  if (source.authorizationStatus === 'SUSPENDED') return '授权已暂停'
  return '未登记授权'
}

// ---------- ADM-NEWS-07：手工关联 ----------

const manualRelation = ref({ newsId: '', targetType: 'SECURITY' as string, targetId: '', reason: '' })

async function submitManualRelation() {
  notice.value = ''
  actionError.value = ''
  try {
    const created = await createAdminNewsRelation(
      Number(manualRelation.value.newsId),
      {
        targetType: manualRelation.value.targetType,
        targetId: manualRelation.value.targetId,
        reasonSummary: manualRelation.value.reason || undefined,
      },
      crypto.randomUUID(),
    )
    notice.value = `已建立手工关联 ${created.relationId}（MANUAL + CONFIRMED）`
    await relations.reload()
  } catch (cause) {
    actionError.value = (cause as { message?: string }).message ?? '建立关联失败'
  }
}

// ---------- ADM-NEWS-08：删除关联（置 REJECTED，保留审计） ----------

async function removeRelation(relation: AdminNewsRelation) {
  const reason = window.prompt('删除关联的原因（审计留痕）') ?? ''
  notice.value = ''
  actionError.value = ''
  try {
    await deleteAdminNewsRelation(relation.relationId, reason || undefined)
    notice.value = `关联 ${relation.relationId} 已置为 REJECTED`
    await relations.reload()
  } catch (cause) {
    actionError.value = (cause as { message?: string }).message ?? '删除失败'
  }
}
</script>

<template>
  <div>
    <p v-if="notice" class="admin-notice">{{ notice }}</p>
    <p v-if="actionError" class="admin-error">{{ actionError }}</p>

    <section class="research-panel">
      <div class="section-heading">
        <div><span class="eyebrow">NEWS SOURCES</span><h2>资讯来源</h2></div>
        <button class="secondary-button" type="button" @click="createSource">新建来源</button>
      </div>
      <p v-if="sources.error.value" class="admin-error">{{ sources.error.value.message }}</p>
      <table v-else-if="sources.data.value && sources.data.value.items.length" class="quote-table">
        <thead><tr><th>来源</th><th>类型</th><th>授权</th><th>区间</th><th>AI 许可</th><th>运行</th><th>最近成功</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="source in sources.data.value.items" :key="source.sourceId">
            <td><strong>{{ source.sourceName }}</strong><small>{{ source.sourceCode }}</small></td>
            <td>{{ source.sourceType }}</td>
            <td>{{ authorizeLabel(source) }}</td>
            <td><small>{{ source.rightsValidFrom ?? '—' }} ~ {{ source.rightsValidTo ?? '—' }}</small></td>
            <td>{{ source.allowAiAnalysis ? '允许' : '禁止' }}</td>
            <td>{{ source.status }}</td>
            <td>{{ source.lastSuccessAt ?? '—' }}</td>
            <td class="admin-actions">
              <button type="button" @click="toggleAiAnalysis(source)">
                {{ source.allowAiAnalysis ? '禁 AI' : '启 AI' }}
              </button>
              <button
                v-if="source.authorizationStatus !== 'SUSPENDED'"
                type="button"
                @click="suspend(source)"
              >
                暂停
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else-if="sources.data.value" class="admin-empty">还没有登记任何资讯来源。</p>
      <div v-else class="page-loading"><span /><span /><span /></div>
    </section>

    <section class="research-panel">
      <div class="section-heading">
        <div><span class="eyebrow">RELATION REVIEW</span><h2>关联审核</h2></div>
        <div class="admin-search">
          <select :value="relationsStatus" aria-label="关联状态" @change="switchRelationsStatus(($event.target as HTMLSelectElement).value as NewsRelationStatus)">
            <option value="CANDIDATE">候选</option>
            <option value="CONFIRMED">已确认</option>
            <option value="REJECTED">已拒绝</option>
          </select>
        </div>
      </div>

      <form class="admin-create-form" @submit.prevent="submitManualRelation">
        <input v-model="manualRelation.newsId" placeholder="新闻 ID（必填）" required />
        <select v-model="manualRelation.targetType" aria-label="目标类型">
          <option value="SECURITY">证券</option>
          <option value="SECTOR">板块</option>
          <option value="MARKET">市场</option>
        </select>
        <input v-model="manualRelation.targetId" placeholder="目标标识（如 sim-600000 / CN）" required />
        <input v-model="manualRelation.reason" placeholder="关联依据（审计留痕）" />
        <button class="primary-button" type="submit">手工关联</button>
      </form>
      <p v-if="relations.error.value" class="admin-error">{{ relations.error.value.message }}</p>
      <table v-else-if="relations.data.value && relations.data.value.items.length" class="quote-table">
        <thead><tr><th>新闻</th><th>目标</th><th>方法</th><th>置信度</th><th>依据</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="relation in relations.data.value.items" :key="relation.relationId">
            <td><strong>{{ relation.newsTitle }}</strong><small>#{{ relation.newsId }}</small></td>
            <td>{{ relation.targetType }} · {{ relation.targetCode ?? relation.targetId }}<small>{{ relation.targetName }}</small></td>
            <td>{{ relation.relationMethod }}</td>
            <td>{{ relation.confidenceScore ?? '—' }}</td>
            <td><small>{{ relation.reasonSummary ?? '—' }}</small></td>
            <td class="admin-actions">
              <template v-if="relation.relationStatus === 'CANDIDATE'">
                <button type="button" @click="review(relation, 'CONFIRMED')">确认</button>
                <button type="button" @click="review(relation, 'REJECTED')">拒绝</button>
              </template>
              <span v-else class="status-label flat">{{ relation.relationStatus }}</span>
              <button
                v-if="relation.relationStatus !== 'REJECTED'"
                type="button"
                @click="removeRelation(relation)"
              >
                删除
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else-if="relations.data.value" class="admin-empty">没有待处理的关联。</p>
      <div v-else class="page-loading"><span /><span /><span /></div>
    </section>
  </div>
</template>
