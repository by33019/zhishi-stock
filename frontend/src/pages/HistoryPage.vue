<script setup lang="ts">
import { Download, Search } from '@lucide/vue'
import { computed, onMounted, ref, watch } from 'vue'

import PageHeader from '@/components/PageHeader.vue'
import { useRemoteData } from '@/composables/useRemoteData'
import { ApiError } from '@/services/apiClient'
import {
  deleteSession,
  getSession,
  getSessionMessages,
  getSessions,
  updateSession,
} from '@/services/historyApi'
import type { AiMessage, AiScene, AiSessionQuery, AiSessionSummary } from '@/types/domain'
import { formatDate, formatDateTime, formatTime } from '@/utils/format'

/**
 * 分析历史（契约 HIS-01 / HIS-02 / HIS-05）。
 *
 * 这一页此前是静态原型：4 条写死的报告、写死的筛选计数（24 / 12 / 3）、
 * 以及一排点了没有任何反应的按钮。现在全部换成真实接口。
 *
 * ## 原型里有、这一版**故意没有**的东西
 *
 * - **每个研究类型的条数**：HIS-01 不返回按场景分组的计数，逐类调一次接口是 5 次请求。
 *   画一个数字出来就是编造，所以整个计数位置删掉。
 * - **"按更新时间 / 创建时间"排序下拉**：服务端固定按最后活动时间倒序，没有排序参数。
 *   留一个下拉框等于让用户以为能改，而点了没反应比禁用更糟。
 * - **"再次分析"**：AI-07 只对 FAILED / TIMED_OUT 的任务生效，对已完成的会话重建任务
 *   是另一件事（不在契约里）。改成"在 AI 研究中继续"——但 `/ai` 尚未接入，因此这一版
 *   也不给这个入口。
 * - **重命名 / 收藏 / 删除**：HIS-03 / HIS-04 需要 `If-Match` 乐观锁，尚未实现。
 */

const sceneOptions: { value: AiScene | ''; label: string }[] = [
  { value: '', label: '全部研究' },
  { value: 'MARKET', label: '市场解读' },
  { value: 'SECTOR', label: '板块解读' },
  { value: 'STOCK', label: '个股研究' },
  { value: 'STOCK_RISK', label: '风险梳理' },
  { value: 'COMPARE', label: '多标的对比' },
]

const rangeOptions = [
  { value: 30, label: '近 30 天' },
  { value: 90, label: '近 3 个月' },
  { value: 0, label: '全部时间' },
]

const PAGE_SIZE = 20

const scene = ref<AiScene | ''>('')
const rangeDays = ref(30)
const keywordInput = ref('')
const keyword = ref('')
const page = ref(1)

/** 场景码 → 中文名。未知取值回退为原值，服务端新增场景时界面不会显示空白。 */
function sceneLabel(value: AiScene) {
  return sceneOptions.find((option) => option.value === value)?.label ?? value
}

/**
 * 时间范围换算成 `startAt`。
 *
 * `0` 表示"全部时间"，此时**不传**这个参数——传一个很早的时间是猜，而"不传"
 * 在接口上的语义就是"不筛选"。
 */
const startAt = computed(() => {
  if (rangeDays.value <= 0) return undefined
  const from = new Date(Date.now() - rangeDays.value * 24 * 60 * 60 * 1000)
  return from.toISOString()
})

const query = computed<AiSessionQuery>(() => ({
  scene: scene.value,
  keyword: keyword.value,
  startAt: startAt.value,
  page: page.value,
  size: PAGE_SIZE,
}))

const { data: pageData, loading, error, reload } = useRemoteData(() => getSessions(query.value))

/** 详情与消息各自一次请求：进页面就全量拉消息会让首屏变成一个 N+1。 */
const selectedId = ref<string | null>(null)
const {
  data: detail,
  loading: detailLoading,
  error: detailError,
  reload: reloadDetail,
} = useRemoteData(() => (selectedId.value ? getSession(selectedId.value) : Promise.resolve(undefined)))
const {
  data: messages,
  loading: messagesLoading,
  error: messagesError,
  reload: reloadMessages,
} = useRemoteData(() =>
  selectedId.value
    ? getSessionMessages(selectedId.value, { page: 1, size: 50 })
    : Promise.resolve(undefined),
)

const rows = computed<AiSessionSummary[]>(() => pageData.value?.items ?? [])
const totalPages = computed(() => pageData.value?.totalPages ?? 0)
const total = computed(() => pageData.value?.total ?? 0)

/** 选中的行消失（换页/换筛选）时清掉详情，否则右侧会停在上一次点开的内容上。 */
watch(rows, (list) => {
  if (selectedId.value && !list.some((row) => row.sessionId === selectedId.value)) {
    selectedId.value = null
  }
})

function select(sessionId: string) {
  selectedId.value = sessionId
  void reloadDetail()
  void reloadMessages()
}

function applyKeyword() {
  keyword.value = keywordInput.value.trim()
  page.value = 1
  void reload()
}

function chooseScene(value: AiScene | '') {
  scene.value = value
  page.value = 1
  void reload()
}

function chooseRange(days: number) {
  rangeDays.value = days
  page.value = 1
  void reload()
}

function goPage(next: number) {
  if (next < 1 || (totalPages.value > 0 && next > totalPages.value)) return
  page.value = next
  void reload()
}

/** 关联标的只对证券与板块给跳转；市场级目标没有对应详情页。 */
function targetLink(targetType: string, targetId: string): string | null {
  if (targetType === 'SECURITY') return `/stocks/${targetId}`
  if (targetType === 'SECTOR') return `/sectors/${targetId}`
  return null
}

function roleLabel(role: AiMessage['roleType']) {
  return role === 'USER' ? '提问' : role === 'ASSISTANT' ? '分析结论' : ''
}

// ---------- HIS-03 / HIS-04：改名、收藏、删除 ----------

/** 写操作的提示。与列表的加载错误分开：一个是"数据没读到"，一个是"刚做的操作结果"。 */
const actionNotice = ref('')
const actionError = ref('')
const mutating = ref(false)
const renaming = ref(false)
const renameInput = ref('')
const confirmingDelete = ref(false)

/**
 * 统一的写操作流程。
 *
 * **409 单独处理**：它不是"操作非法"，而是"这份会话在你操作期间被别人改过"。
 * 此时正确响应是重新拉取并请用户再试，而不是把错误原样抛给他——
 * 用户改一次标题却看到一句"版本冲突"，无从知道下一步该做什么。
 */
async function runMutation(operation: () => Promise<string>) {
  mutating.value = true
  actionError.value = ''
  actionNotice.value = ''
  try {
    actionNotice.value = await operation()
    await reload()
    await reloadDetail()
    await reloadMessages()
  } catch (cause) {
    const failure = cause as Partial<ApiError>
    if (failure.status === 409) {
      actionError.value = '这个会话刚被修改过，已为你刷新，请再试一次。'
      await reload()
      await reloadDetail()
    } else {
      actionError.value = failure.message ?? '操作失败，请稍后重试。'
    }
  } finally {
    mutating.value = false
  }
}

function toggleFavorite() {
  const current = detail.value
  if (!current) return
  void runMutation(async () => {
    // 用当前渲染的那一版提交。自己重新取版本就等于"用最新版写"，乐观锁会失效。
    const result = await updateSession(current.sessionId, current.version, {
      isFavorite: !current.isFavorite,
    })
    return result.isFavorite ? '已加入收藏。' : '已取消收藏。'
  })
}

function startRename() {
  renameInput.value = detail.value?.title ?? ''
  renaming.value = true
  confirmingDelete.value = false
  actionError.value = ''
}

function saveRename() {
  const current = detail.value
  if (!current) return
  const title = renameInput.value.trim()
  if (!title) {
    // 前端先拦一次给出即时反馈；服务端仍会独立校验（它才是权威）。
    actionError.value = '标题不能为空。'
    return
  }
  void runMutation(async () => {
    await updateSession(current.sessionId, current.version, { title })
    renaming.value = false
    return '标题已更新。'
  })
}

function removeSession() {
  const current = detail.value
  if (!current) return
  void runMutation(async () => {
    const result = await deleteSession(current.sessionId, current.version)
    selectedId.value = null
    confirmingDelete.value = false
    return `已删除，${formatDate(result.purgeAfter)} 之后彻底清理。`
  })
}

onMounted(reload)
</script>

<template>
  <div class="business-page page-enter">
    <PageHeader
      eyebrow="RESEARCH ARCHIVE"
      title="分析历史"
      description="保存每次研究的问题与结论。点击任一条可查看当次的分析目标与完整正文。"
    >
      <!-- 导出属 M3-12，后端未实现：禁用并写明归属，可点但无反应更糟。 -->
      <button class="secondary-button" type="button" disabled title="导出能力归属 M3-12">
        <Download :size="15" /> 批量导出
      </button>
    </PageHeader>

    <section class="history-layout">
      <aside class="history-filter">
        <span>研究类型</span>
        <button
          v-for="option in sceneOptions"
          :key="option.value || 'all'"
          type="button"
          :class="{ active: scene === option.value }"
          @click="chooseScene(option.value)"
        >
          {{ option.label }}
        </button>
        <div />
        <span>时间范围</span>
        <button
          v-for="option in rangeOptions"
          :key="option.value"
          type="button"
          :class="{ active: rangeDays === option.value }"
          @click="chooseRange(option.value)"
        >
          {{ option.label }}
        </button>
      </aside>

      <main class="history-main">
        <header>
          <label class="inline-search">
            <Search :size="15" />
            <!-- 服务端的 keyword 只匹配会话标题，提示语不能写成"搜索报告内容"。 -->
            <input
              v-model="keywordInput"
              type="search"
              placeholder="搜索会话标题，回车确认"
              @keyup.enter="applyKeyword"
            />
          </label>
          <button class="secondary-button" type="button" @click="applyKeyword">搜索</button>
        </header>

        <p v-if="loading" class="state-note">正在加载分析历史…</p>
        <p v-else-if="error" class="state-note state-note--error">
          {{ error.message }}
          <template v-if="error.traceId">（追踪号 {{ error.traceId }}）</template>
          <button class="link-button" type="button" @click="reload()">重试</button>
        </p>
        <p v-else-if="rows.length === 0" class="state-note">
          还没有分析记录。到「AI 研究」发起一次分析后，结果会出现在这里。
        </p>

        <template v-else>
          <p class="state-note">共 {{ total }} 条，第 {{ page }} / {{ Math.max(totalPages, 1) }} 页</p>
          <div class="report-list">
            <article
              v-for="row in rows"
              :key="row.sessionId"
              :class="{ selected: row.sessionId === selectedId }"
            >
              <div class="report-date">
                <strong>{{ formatDate(row.lastActivityAt) }}</strong>
                <span>{{ formatTime(row.lastActivityAt) }}</span>
              </div>
              <div class="report-info">
                <div>
                  <span>{{ sceneLabel(row.scene) }}</span>
                  <!-- 从未跑过任务时明说"尚未分析"，不写"状态未知"。 -->
                  <b :class="{ limited: row.lastTask?.status !== 'COMPLETED' }">
                    {{ row.lastTask ? row.lastTask.status : '尚未分析' }}
                  </b>
                  <span v-if="row.isFavorite">已收藏</span>
                </div>
                <h2>
                  <button class="link-button" type="button" @click="select(row.sessionId)">
                    {{ row.title }}
                  </button>
                </h2>
                <p>{{ row.lastTask ? `最近任务 ${row.lastTask.taskId}` : '还没有发起过分析' }}</p>
              </div>
            </article>
          </div>

          <div class="history-pager">
            <button
              class="secondary-button"
              type="button"
              :disabled="page <= 1"
              @click="goPage(page - 1)"
            >
              上一页
            </button>
            <button
              class="secondary-button"
              type="button"
              :disabled="totalPages === 0 || page >= totalPages"
              @click="goPage(page + 1)"
            >
              下一页
            </button>
          </div>
        </template>

        <!--
          写操作的提示放在详情区**之外**：删除成功后详情会收起（那个会话已经不在列表里），
          提示若挂在详情区内就会跟着消失——用户点完删除看到列表少了一行，
          却不知道发生了什么、数据什么时候清理。
        -->
        <p v-if="actionError" class="state-note state-note--error">{{ actionError }}</p>
        <p v-else-if="actionNotice" class="state-note">{{ actionNotice }}</p>

        <section v-if="selectedId" class="history-detail">
          <p v-if="detailLoading" class="state-note">正在加载会话详情…</p>
          <p v-else-if="detailError" class="state-note state-note--error">
            {{ detailError.message }}
            <button class="link-button" type="button" @click="reloadDetail()">重试</button>
          </p>
          <template v-else-if="detail">
            <div class="history-actions">
              <button class="secondary-button" type="button" :disabled="mutating" @click="toggleFavorite">
                {{ detail.isFavorite ? '取消收藏' : '收藏' }}
              </button>
              <button class="secondary-button" type="button" :disabled="mutating" @click="startRename">
                重命名
              </button>
              <button class="secondary-button" type="button" :disabled="mutating" @click="confirmingDelete = true">
                删除
              </button>
            </div>

            <form v-if="renaming" class="history-rename" @submit.prevent="saveRename">
              <!-- maxlength 与服务端的 1~60 对齐，让用户不必先提交才知道上限 -->
              <input v-model="renameInput" maxlength="60" aria-label="会话标题" />
              <button class="secondary-button" type="submit" :disabled="mutating">保存</button>
              <button class="secondary-button" type="button" @click="renaming = false">取消</button>
            </form>

            <!-- 删除是两步：一次点击就删掉一条研究会话太容易误触 -->
            <div v-if="confirmingDelete" class="history-confirm">
              <p>删除后不再出现在列表里，30 天后彻底清理。确定删除这个会话？</p>
              <button class="secondary-button" type="button" :disabled="mutating" @click="removeSession">
                确定删除
              </button>
              <button class="secondary-button" type="button" @click="confirmingDelete = false">取消</button>
            </div>

            <h3>分析目标</h3>
            <ul v-if="detail.targets.length" class="target-list">
              <li v-for="target in detail.targets" :key="target.targetId">
                <RouterLink
                  v-if="targetLink(target.targetType, target.targetId)"
                  :to="targetLink(target.targetType, target.targetId) as string"
                >
                  {{ target.targetName }}（{{ target.targetCode }}）
                </RouterLink>
                <span v-else>{{ target.targetName }}（{{ target.targetCode }}）</span>
                <em>{{ target.targetRole }}</em>
              </li>
            </ul>
            <p v-else class="state-note">这个会话还没有关联分析目标。</p>

            <h3>最近任务</h3>
            <p v-if="detail.lastTask" class="state-note">
              {{ detail.lastTask.taskId }} · {{ detail.lastTask.status }} ·
              {{ detail.lastTask.progressStage }}
              <template v-if="detail.lastTask.error">
                <br />失败原因：{{ detail.lastTask.error.message }}（{{ detail.lastTask.error.code }}）
              </template>
            </p>
            <p v-else class="state-note">尚未发起过分析任务。</p>

            <h3>报告</h3>
            <p v-if="detail.lastReport" class="state-note">
              报告 {{ detail.lastReport.reportId }} · {{ detail.lastReport.qualityStatus }}
              <template v-if="detail.lastReport.isLimited">（受限：结论受数据缺口约束）</template>
              · 生成于 {{ formatDateTime(detail.lastReport.generatedAt) }}
            </p>
            <p v-else class="state-note">这次任务没有产出报告（可能是失败、超时或仍在进行中）。</p>

            <h3>正文与提问</h3>
            <p v-if="messagesLoading" class="state-note">正在加载正文…</p>
            <p v-else-if="messagesError" class="state-note state-note--error">
              {{ messagesError.message }}
              <button class="link-button" type="button" @click="reloadMessages()">重试</button>
            </p>
            <template v-else>
              <article
                v-for="message in messages?.items ?? []"
                :key="message.messageId"
                class="message-block"
              >
                <header>
                  <strong>#{{ message.sequenceNo }} {{ roleLabel(message.roleType) }}</strong>
                  <span v-if="message.dataCutoffAt">
                    数据截止 {{ formatDateTime(message.dataCutoffAt) }}
                  </span>
                </header>
                <!-- 结论是服务端渲染好的 Markdown，这里按纯文本展示（不引入 Markdown 渲染库）。 -->
                <pre>{{ message.content }}</pre>
              </article>
              <p v-if="(messages?.items ?? []).length === 0" class="state-note">暂无消息。</p>
            </template>
          </template>
        </section>
      </main>
    </section>
  </div>
</template>
