<script setup lang="ts">
import { Search, Sparkles, X } from '@lucide/vue'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

import PageHeader from '@/components/PageHeader.vue'
import { useRemoteData } from '@/composables/useRemoteData'
import {
  cancelTask,
  createFollowUpTask,
  createTask,
  getMyAiQuota,
  getReport,
  getReportEvidence,
  getScenes,
  getTask,
  previewContext,
  retryTask,
  streamTaskEvents,
} from '@/services/aiApi'
import { getSectorRankings } from '@/services/sectorApi'
import { searchSecurities } from '@/services/securityApi'
import type { StreamHandle } from '@/services/apiClient'
import type {
  AiContextPreview,
  AiContextTarget,
  AiReportDetail,
  AiReportEvidence,
  AiScene,
  AiStreamEvent,
  AiTaskAccepted,
  AiTaskQuota,
  AiTaskSummary,
  SectorQuote,
} from '@/types/domain'
import { formatDateTime } from '@/utils/format'

/**
 * AI 研究工作台（契约 AI-01~08 + HIS-06 + HIS-07 + USER-07）。
 *
 * ## 运行期走 SSE（AI-05），轮询只是兜底
 *
 * 建流后状态与临时文本实时到达（snapshot / status / chunk / report / error / done）。
 * 流连不上（网关不支持、网络问题）时**退回每 3 秒轮询**：闭环不能因为
 * 一条流挂掉就整体不可用，而轮询的 AI-04 本来就能表达同样的状态机。
 *
 * ## 场景与目标的支撑范围
 *
 * 五个场景全部可提交，目标选择随 AI-01 的定义走：
 * MARKET 用契约写明的对外标识 `CN`；SECURITY 走 STK-01 检索（单选或 2~3 只多选）；
 * SECTOR 走 SEC-02 榜单取候选（该接口一次返回全部板块行情，取首页后本地过滤）。
 */

const { data: scenes, error: scenesError, reload: reloadScenes } = useRemoteData(getScenes)

const scene = ref<AiScene>('STOCK')
const question = ref('')

const sceneDefinition = computed(() =>
  scenes.value?.find((definition) => definition.scene === scene.value),
)

/**
 * 目标选择形态。由场景定义推导，不在前端硬编码场景清单：
 * 服务端新增一个场景时，只要它的目标类型是这三种之一，界面就自动可用。
 */
type TargetMode = 'MARKET' | 'SECURITY_SINGLE' | 'SECURITY_MULTI' | 'SECTOR'

const targetMode = computed<TargetMode | null>(() => {
  const definition = sceneDefinition.value
  if (!definition || definition.allowedTargetTypes.length !== 1) return null
  switch (definition.allowedTargetTypes[0]) {
    case 'MARKET':
      return 'MARKET'
    case 'SECURITY':
      return definition.minTargets > 1 || definition.maxTargets > 1
        ? 'SECURITY_MULTI'
        : 'SECURITY_SINGLE'
    case 'SECTOR':
      return 'SECTOR'
    default:
      return null
  }
})

// ---------- 目标选择 ----------

const targetQuery = ref('')
const targetResults = ref<AiContextTarget[]>([])
/** 单选场景（MARKET / SECURITY_SINGLE / SECTOR）选中的目标。 */
const target = ref<AiContextTarget | null>(null)
/** 多选场景（SECURITY_MULTI）选中的目标，2~3 只。 */
const selectedTargets = ref<AiContextTarget[]>([])
const searching = ref(false)
const searchError = ref('')

/** 检索框上方标签随场景变化；多选场景顺带显示进度。 */
const targetLabel = computed(() => {
  switch (targetMode.value) {
    case 'SECURITY_MULTI':
      return `对比标的（${selectedTargets.value.length} / ${sceneDefinition.value?.maxTargets ?? 3}）`
    case 'SECTOR':
      return '分析板块'
    case 'MARKET':
      return '分析市场'
    default:
      return '分析标的'
  }
})

/** 当前场景下将要提交的目标集合。 */
const currentTargets = computed<AiContextTarget[]>(() =>
  targetMode.value === 'SECURITY_MULTI' ? selectedTargets.value : target.value ? [target.value] : [],
)

/** 目标数量是否达到场景要求的区间——没达到就不给提交。 */
const targetsValid = computed(() => {
  const definition = sceneDefinition.value
  if (!definition) return false
  const count = currentTargets.value.length
  return count >= definition.minTargets && count <= definition.maxTargets
})

/**
 * 市场场景的目标是固定的市场代码。
 *
 * `CN` 取自 `AiTargetRequest` 的 javadoc（「目标对外标识：`CN` / `sim-bk0001` /
 * `sim-600519`」），是契约写明的取值，不是界面猜的。
 */
function marketTarget(): AiContextTarget {
  return {
    targetType: 'MARKET',
    targetId: 'CN',
    targetCode: 'CN',
    targetName: '中国 A 股',
    targetRole: 'PRIMARY',
  }
}

async function searchTargets() {
  const query = targetQuery.value.trim()
  if (!query) return
  searching.value = true
  searchError.value = ''
  try {
    const result = await searchSecurities(query, 8)
    targetResults.value = result.items.map((match) => ({
      targetType: 'SECURITY',
      targetId: match.security.securityId,
      targetCode: match.security.securityCode,
      targetName: match.security.securityName,
      targetRole: 'PRIMARY',
    }))
    if (targetResults.value.length === 0) searchError.value = '没有匹配的证券。'
  } catch (cause) {
    const failure = cause as { message?: string; traceId?: string }
    searchError.value = failure.message ?? '检索失败，请稍后重试。'
  } finally {
    searching.value = false
  }
}

function chooseTarget(candidate: AiContextTarget) {
  if (targetMode.value === 'SECURITY_MULTI') {
    // 已选就忽略（移除走标签上的 ×）；选满后不再加，并说明原因。
    const exists = selectedTargets.value.some((item) => item.targetId === candidate.targetId)
    const max = sceneDefinition.value?.maxTargets ?? 3
    if (exists) return
    if (selectedTargets.value.length >= max) {
      searchError.value = `该场景最多对比 ${max} 只证券。`
      return
    }
    selectedTargets.value = [...selectedTargets.value, candidate]
    targetResults.value = []
    targetQuery.value = ''
  } else {
    target.value = candidate
    targetResults.value = []
    targetQuery.value = ''
  }
  // 换了目标就丢掉上一次的预览：它描述的是旧目标的取数结果，留着会误导
  preview.value = undefined
}

function removeSelectedTarget(targetId: string) {
  selectedTargets.value = selectedTargets.value.filter((item) => item.targetId !== targetId)
  preview.value = undefined
}

// ---------- 板块候选（SECTOR 场景）----------

const sectorCandidates = ref<SectorQuote[]>([])
const loadingSectors = ref(false)

/**
 * 板块候选来自 SEC-02 榜单：它一次返回同一快照下的全部板块行情，
 * 取一大页后按关键字本地过滤即可，不需要后端新增检索接口。
 * 榜单按成交额排，冷门板块可能不在首页——筛不到时如实说，不假装搜过了。
 */
async function searchSectors() {
  loadingSectors.value = true
  searchError.value = ''
  try {
    const result = await getSectorRankings({ page: 1, size: 100 })
    const query = targetQuery.value.trim().toLowerCase()
    const items = query
      ? result.items.filter(
          (item) =>
            item.sectorName.toLowerCase().includes(query) ||
            item.sectorCode.toLowerCase().includes(query),
        )
      : result.items
    sectorCandidates.value = items.slice(0, 8)
    if (sectorCandidates.value.length === 0) searchError.value = '没有匹配的板块。'
  } catch (cause) {
    const failure = cause as { message?: string }
    searchError.value = failure.message ?? '板块检索失败，请稍后重试。'
  } finally {
    loadingSectors.value = false
  }
}

function chooseSector(item: SectorQuote) {
  target.value = {
    targetType: 'SECTOR',
    targetId: item.sectorId,
    targetCode: item.sectorCode,
    targetName: item.sectorName,
    targetRole: 'PRIMARY',
  }
  sectorCandidates.value = []
  targetQuery.value = ''
  preview.value = undefined
}

/** 场景一换，目标与预览都失效——不同场景接受的标的类型不同。 */
watch(scene, () => {
  target.value = targetMode.value === 'MARKET' ? marketTarget() : null
  selectedTargets.value = []
  targetResults.value = []
  sectorCandidates.value = []
  preview.value = undefined
  searchError.value = ''
})

// ---------- 预览（AI-02，不消耗配额）----------

const preview = ref<AiContextPreview>()
const previewError = ref('')
const previewing = ref(false)

async function runPreview() {
  if (currentTargets.value.length === 0) return
  previewing.value = true
  previewError.value = ''
  try {
    preview.value = await previewContext({
      scene: scene.value,
      targets: currentTargets.value,
    })
  } catch (cause) {
    const failure = cause as { message?: string; traceId?: string }
    previewError.value = failure.message ?? '预览失败，请稍后重试。'
  } finally {
    previewing.value = false
  }
}

// ---------- 提交与运行期（AI-03 / AI-04 / AI-05 / HIS-06）----------

const submitting = ref(false)
const submitError = ref('')
const task = ref<AiTaskSummary>()
const quota = ref<AiTaskQuota>()
const quotaError = ref('')
const report = ref<AiReportDetail>()
/** SSE 正在推送的临时文本；报告就位后由报告视图取代。 */
const streamingText = ref('')
/** SSE 不可用、已退回轮询的提示。 */
const pollingFallbackNote = ref('')

/**
 * USER-07：打开页面就取一次"今天还剩几次"。
 *
 * 提交后的 AI-03 响应里也有 `quota`，但那是**提交之后**才有的数——只靠它，
 * 用户在点"开始分析"之前根本不知道额度还剩多少，只能点下去靠错误码告诉他。
 * 取不到就如实留空并说明原因，**不编一个默认值**：显示"今日剩余 20 次"
 * 而实际是 0，比不显示更糟。
 */
async function loadQuota() {
  quotaError.value = ''
  try {
    quota.value = await getMyAiQuota()
  } catch (cause) {
    const failure = cause as { message?: string }
    quotaError.value = failure.message ?? '配额信息暂时取不到。'
  }
}

const TERMINAL = new Set(['COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELED'])

/** 轮询的墙钟上限：任务卡死时不能让页面一直转圈。 */
const POLL_TIMEOUT_MS = 180_000
let pollTimer: ReturnType<typeof setInterval> | undefined
let pollStartedAt = 0
let streamHandle: StreamHandle | undefined
/** 已应用到界面的事件序号：重连补发时用它去重，避免把同一段文本拼两遍。 */
let appliedSequence = 0

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = undefined
  }
}

function stopStream() {
  streamHandle?.close()
  streamHandle = undefined
}

async function pollOnce(taskId: string) {
  try {
    const latest = await getTask(taskId)
    task.value = latest
    if (!TERMINAL.has(latest.status)) {
      if (Date.now() - pollStartedAt > POLL_TIMEOUT_MS) {
        stopPolling()
        submitError.value = '任务执行时间超出预期，已停止等待。可到「分析历史」查看最终结果。'
      }
      return
    }
    stopPolling()
    if (latest.status === 'COMPLETED' && latest.reportId) await loadReport(latest.reportId)
  } catch (cause) {
    stopPolling()
    const failure = cause as { message?: string }
    submitError.value = failure.message ?? '查询任务状态失败。'
  }
}

function subscribeStream(streamUrl: string) {
  pollingFallbackNote.value = ''
  streamHandle = streamTaskEvents(streamUrl, handleStreamEvent, () => {
    // 流不可用（网关不支持、网络断、重连次数用尽）：退回轮询，闭环不断。
    // 这里不把它当错误展示——对用户来说"能等到结果"比"知道流挂了"重要。
    streamHandle = undefined
    if (!task.value) return
    pollingFallbackNote.value = '实时流不可用，已改为每 3 秒刷新一次状态。'
    pollStartedAt = Date.now()
    stopPolling()
    pollTimer = setInterval(() => void pollOnce(task.value!.taskId), 3000)
    void pollOnce(task.value.taskId)
  })
}

function handleStreamEvent(event: AiStreamEvent) {
  switch (event.kind) {
    case 'snapshot':
      // snapshot 是建连/重连那一刻的完整状态，直接取代本地累积的文本。
      task.value = event.task
      appliedSequence = event.lastSequence
      streamingText.value = event.partialContent ?? ''
      break
    case 'status':
      if (task.value) {
        task.value = { ...task.value, status: event.status, progressStage: event.progressStage }
      }
      break
    case 'chunk':
      if (event.sequence <= appliedSequence) return
      appliedSequence = event.sequence
      streamingText.value += event.delta
      break
    case 'report':
      void loadReport(event.reportId)
      break
    case 'error':
      // 失败详情任务摘要里也会带（task.error），这里只补一条即时提示。
      submitError.value = `${event.message}（${event.errorCode}）`
      break
    case 'done':
      stopStream()
      void syncTaskAfterDone(event.taskId, event.finalStatus)
      break
  }
}

async function loadReport(reportId: string) {
  if (report.value?.reportId === reportId) return
  report.value = await getReport(reportId)
}

/** done 之后以 AI-04 的任务摘要为准对齐一次（status 事件可能只是中间态）。 */
async function syncTaskAfterDone(taskId: string, finalStatus: string) {
  try {
    const latest = await getTask(taskId)
    task.value = latest
    if (finalStatus === 'COMPLETED' && latest.reportId) await loadReport(latest.reportId)
  } catch (cause) {
    const failure = cause as { message?: string }
    submitError.value = failure.message ?? '确认任务结果失败。'
  }
}

function startRun(accepted: AiTaskAccepted) {
  task.value = accepted.task
  quota.value = accepted.quota
  report.value = undefined
  streamingText.value = ''
  appliedSequence = 0
  submitError.value = ''
  stopPolling()
  stopStream()
  if (accepted.streamUrl) {
    subscribeStream(accepted.streamUrl)
  } else {
    // 没有流入口就不假装有：直接走轮询。
    pollingFallbackNote.value = '以每 3 秒刷新一次状态。'
    pollStartedAt = Date.now()
    pollTimer = setInterval(() => void pollOnce(accepted.task.taskId), 3000)
    void pollOnce(accepted.task.taskId)
  }
}

async function submit() {
  if (!targetsValid.value) return
  submitting.value = true
  submitError.value = ''
  report.value = undefined
  task.value = undefined
  streamingText.value = ''
  try {
    const accepted = await createTask(
      {
        scene: scene.value,
        targets: currentTargets.value,
        question: question.value.trim() || undefined,
      },
      // 键的语义是"一次用户意图"：这里一次点击只提交一次，所以每次点击都是新意图
      crypto.randomUUID(),
    )
    startRun(accepted)
  } catch (cause) {
    const failure = cause as { message?: string; code?: string; traceId?: string }
    submitError.value = failure.message ?? '提交失败，请稍后重试。'
  } finally {
    submitting.value = false
  }
}

// ---------- 取消 / 重试 / 追问（AI-06 / AI-07 / AI-08）----------

const canceling = ref(false)
const cancelNote = ref('')

async function cancelRun() {
  if (!task.value || !running.value) return
  canceling.value = true
  cancelNote.value = ''
  try {
    const result = await cancelTask(task.value.taskId, crypto.randomUUID())
    cancelNote.value = result.effectiveImmediately
      ? '已请求取消。'
      : '任务已经完成，取消未生效。'
  } catch (cause) {
    const failure = cause as { message?: string }
    cancelNote.value = failure.message ?? '取消失败，请稍后重试。'
  } finally {
    canceling.value = false
  }
}

/** 契约只允许对 FAILED / TIMED_OUT 重试；CANCELED 与 COMPLETED 没有"再来一次"的语义。 */
const retryable = computed(() =>
  Boolean(task.value && ['FAILED', 'TIMED_OUT'].includes(task.value.status)),
)

const retrying = ref(false)

async function retryRun() {
  if (!task.value || !retryable.value) return
  retrying.value = true
  cancelNote.value = ''
  try {
    const accepted = await retryTask(
      task.value.taskId,
      crypto.randomUUID(),
      question.value.trim() || undefined,
    )
    startRun(accepted)
  } catch (cause) {
    const failure = cause as { message?: string }
    submitError.value = failure.message ?? '重试失败，请稍后重试。'
  } finally {
    retrying.value = false
  }
}

const followUpQuestion = ref('')
const followUpSubmitting = ref(false)

async function submitFollowUp() {
  const current = task.value
  const text = followUpQuestion.value.trim()
  if (!current || !text) return
  followUpSubmitting.value = true
  cancelNote.value = ''
  try {
    const accepted = await createFollowUpTask(
      current.sessionId,
      { question: text },
      crypto.randomUUID(),
    )
    followUpQuestion.value = ''
    startRun(accepted)
  } catch (cause) {
    const failure = cause as { message?: string }
    submitError.value = failure.message ?? '追问提交失败，请稍后重试。'
  } finally {
    followUpSubmitting.value = false
  }
}

// `useRemoteData` 只提供 reload，不会自动执行——不挂载时调一次，页面会永远停在"加载中"。
onMounted(() => {
  void reloadScenes()
  void loadQuota()
})
onUnmounted(() => {
  stopPolling()
  stopStream()
})

const running = computed(() => Boolean(task.value) && !TERMINAL.has(task.value!.status))

const qualityLabel = computed(() =>
  report.value?.qualityStatus === 'LIMITED' ? '受限分析' : '证据完整',
)

// ---------- 来源引用（HIS-07）----------

const evidences = ref<AiReportEvidence[]>([])
const evidenceError = ref('')
const loadingEvidence = ref(false)

/**
 * 请求序号：用来丢弃**迟到的响应**。
 *
 * 用户可能在等引用列表时又提交了一次分析（或点了重试），旧请求回来时
 * 页面已经属于另一份报告了。不做这个判断的话，旧响应会覆盖新数据，
 * 而界面上表现成"引用列表和报告对不上"——这种错配不会报错，只会让人看不懂。
 */
let evidenceRequest = 0

async function loadEvidence(reportId: string) {
  const token = ++evidenceRequest
  loadingEvidence.value = true
  evidenceError.value = ''
  try {
    const loaded = await getReportEvidence(reportId)
    if (token !== evidenceRequest) return
    evidences.value = loaded
  } catch (cause) {
    if (token !== evidenceRequest) return
    evidences.value = []
    const failure = cause as { message?: string; traceId?: string }
    evidenceError.value = failure.message ?? '来源引用加载失败。'
  } finally {
    if (token === evidenceRequest) loadingEvidence.value = false
  }
}

/**
 * 报告一就位就去取引用，报告被清空（重新提交）时一起清掉。
 *
 * 挂在 `reportId` 而不是 `report` 上：后者是对象，引用栏只需要知道"是哪份报告"。
 */
watch(
  () => report.value?.reportId,
  (reportId) => {
    evidences.value = []
    evidenceError.value = ''
    if (reportId) void loadEvidence(reportId)
  },
)

/** 类型与访问状态都是后端枚举，这里只做展示映射；未知取值原样显示而不是吞掉。 */
const EVIDENCE_TYPE_LABELS: Record<string, string> = {
  QUOTE: '行情',
  KLINE: 'K线',
  NEWS: '资讯',
  ANNOUNCEMENT: '公告',
  BUSINESS: '经营',
  SECTOR: '板块',
  RULE: '规则',
}

const ACCESS_LABELS: Record<string, string> = {
  AVAILABLE: '来源可见',
  UNAVAILABLE: '原文已下线',
  RESTRICTED: '授权受限，仅摘要',
}

function evidenceTypeLabel(type: string) {
  return EVIDENCE_TYPE_LABELS[type] ?? type
}

function accessLabel(status: string) {
  return ACCESS_LABELS[status] ?? status
}

/** 章节顺序由后端的 `AiReportSection.inOrder()` 决定，前端不重排。 */
const sections = computed(() => {
  const current = report.value
  if (!current) return []
  return [
    { title: '核心结论', body: current.coreConclusion },
    { title: '行情与量价依据', body: current.quoteEvidence },
    { title: '对比分析', body: current.comparisonAnalysis },
    { title: '资讯与事件线索', body: current.eventClues },
    { title: '风险与不确定性', body: current.riskAndUncertainty },
  ].filter((section) => Boolean(section.body))
})
</script>

<template>
  <div class="ai-workspace-page page-enter">
    <PageHeader
      eyebrow="AI RESEARCH STUDIO"
      title="AI 研究"
      description="先预览将使用的数据，再提交分析。结论与证据同源，数据截止时间随报告一起给出。"
    />

    <section class="ai-studio-grid">
      <aside class="research-setup">
        <header><span>研究设置</span></header>

        <p v-if="scenesError" class="state-note state-note--error">
          {{ scenesError.message }}
          <button class="link-button" type="button" @click="reloadScenes()">重试</button>
        </p>
        <p v-else-if="!scenes" class="state-note">正在加载可用场景…</p>

        <label>
          分析场景
          <select v-model="scene" :disabled="!scenes">
            <option v-for="item in scenes ?? []" :key="item.scene" :value="item.scene">
              {{ item.name }}
            </option>
          </select>
        </label>
        <p v-if="sceneDefinition" class="state-note">{{ sceneDefinition.description }}</p>

        <!-- 市场场景：目标是固定的 CN，不需要检索 -->
        <p v-if="targetMode === 'MARKET'" class="selected-target">已选：中国 A 股（CN）</p>

        <template v-else>
          <label>
            {{ targetLabel }}
            <span class="inline-search">
              <Search :size="15" />
              <input
                v-model="targetQuery"
                type="search"
                :placeholder="targetMode === 'SECTOR' ? '输入板块名称过滤，回车检索' : '输入代码或名称，回车检索'"
                @keyup.enter="targetMode === 'SECTOR' ? searchSectors() : searchTargets()"
              />
            </span>
          </label>

          <!-- 多选场景：已选目标以标签呈现，可单独移除 -->
          <ul v-if="selectedTargets.length" class="selected-targets">
            <li v-for="item in selectedTargets" :key="item.targetId" class="target-chip">
              {{ item.targetName }}（{{ item.targetCode }}）
              <button
                class="chip-remove"
                type="button"
                :aria-label="`移除 ${item.targetName}`"
                @click="removeSelectedTarget(item.targetId)"
              >
                <X :size="12" />
              </button>
            </li>
          </ul>

          <p v-if="target && targetMode !== 'SECURITY_MULTI'" class="selected-target">
            已选：{{ target.targetName }}（{{ target.targetCode }}）
          </p>

          <p v-if="searching || loadingSectors" class="state-note">正在检索…</p>
          <p v-else-if="searchError" class="state-note state-note--error">{{ searchError }}</p>

          <ul v-if="targetResults.length" class="target-candidates">
            <li v-for="candidate in targetResults" :key="candidate.targetId">
              <button class="link-button" type="button" @click="chooseTarget(candidate)">
                {{ candidate.targetName }}（{{ candidate.targetCode }}）
              </button>
            </li>
          </ul>
          <ul v-if="sectorCandidates.length" class="target-candidates">
            <li v-for="item in sectorCandidates" :key="item.sectorId">
              <button class="link-button" type="button" @click="chooseSector(item)">
                {{ item.sectorName }}（{{ item.sectorCode }}）
              </button>
            </li>
          </ul>
        </template>

        <div class="setup-divider" />
        <button
          class="secondary-button"
          type="button"
          :disabled="currentTargets.length === 0 || previewing"
          @click="runPreview"
        >
          预览将使用的数据
        </button>
        <p v-if="previewError" class="state-note state-note--error">{{ previewError }}</p>
        <template v-if="preview">
          <!-- 核心行情缺失时服务端会拒绝创建，界面据此禁用提交，不让用户白跑一次 -->
          <p v-if="!preview.canGenerate" class="state-note state-note--error">
            核心行情缺失，无法生成报告。
          </p>
          <p v-for="category in preview.dataCategories" :key="category.category" class="state-note">
            {{ category.category }} · 数据截止
            {{ category.dataCutoffAt ? formatDateTime(category.dataCutoffAt) : '未知' }}
          </p>
          <p class="state-note">资讯 {{ preview.newsCount }} 条</p>
          <!-- limitations 与报告里的 limitedReason 同源，提前说清楚 -->
          <p
            v-for="limitation in preview.limitations"
            :key="limitation"
            class="state-note state-note--warn"
          >
            {{ limitation }}
          </p>
        </template>

        <!--
          配额有两条来源，都是服务端同一份事实：进页面时走 USER-07，
          提交后用 AI-03 响应里的 quota 覆盖（那是刚扣过额度的最新值）。
          两处都不编数：取不到就如实说明。
        -->
        <p v-if="quota" class="quota-note">
          今日剩余 {{ quota.remainingCount }} / {{ quota.dailyLimit }} 次 · 并发上限
          {{ quota.concurrentLimit }} · 重置于 {{ formatDateTime(quota.resetsAt) }}
        </p>
        <p v-else-if="quotaError" class="state-note state-note--warn">{{ quotaError }}</p>
      </aside>

      <main class="report-canvas">
        <div class="question-composer">
          <textarea
            v-model="question"
            rows="3"
            :maxlength="sceneDefinition?.questionMaxLength ?? 500"
            placeholder="描述你希望验证的问题，例如：这只股票近期的量价特征与主要不确定性？"
          />
          <div>
            <span>提交前请先预览数据；未预览也可以直接提交</span>
            <button
              type="button"
              :disabled="!targetsValid || submitting || running || preview?.canGenerate === false"
              @click="submit"
            >
              <Sparkles :size="15" /> {{ running ? '分析进行中' : '开始分析' }}
            </button>
          </div>
        </div>

        <p v-if="submitError" class="state-note state-note--error">{{ submitError }}</p>

        <div v-if="running" class="report-generating">
          <span class="ai-orbit"><Sparkles :size="22" /></span>
          <h2>{{ task?.progressStage || '正在分析' }}</h2>
          <p>
            任务 {{ task?.taskId }} ·
            {{ pollingFallbackNote ? pollingFallbackNote : '实时接收生成内容' }}
          </p>
          <!-- 生成中的临时文本：SSE 的 chunk 逐段到达；轮询兜底时它保持为空 -->
          <pre v-if="streamingText" class="streaming-text">{{ streamingText }}</pre>
          <div class="run-actions">
            <button
              class="secondary-button"
              type="button"
              :disabled="canceling"
              @click="cancelRun"
            >
              {{ canceling ? '正在取消…' : '取消本次分析' }}
            </button>
          </div>
          <p v-if="cancelNote" class="state-note">{{ cancelNote }}</p>
        </div>

        <article v-else-if="task && task.status !== 'COMPLETED'" class="ai-report">
          <header><div><span class="quality-badge">未产出报告</span></div></header>
          <section class="report-lead">
            <span class="report-index">!</span>
            <div>
              <h2>本次分析未产出报告</h2>
              <p>任务状态：{{ task.status }}</p>
              <p v-if="task.error">{{ task.error.message }}（{{ task.error.code }}）</p>
              <div v-if="retryable" class="run-actions">
                <button
                  class="secondary-button"
                  type="button"
                  :disabled="retrying"
                  @click="retryRun"
                >
                  {{ retrying ? '正在创建重试…' : '重试本次分析' }}
                </button>
              </div>
            </div>
          </section>
        </article>

        <article v-else-if="report" class="ai-report">
          <header>
            <div>
              <span class="quality-badge">{{ qualityLabel }}</span>
              <span>报告 {{ report.reportId }} · 生成于 {{ formatDateTime(report.generatedAt) }}</span>
            </div>
          </header>
          <section v-if="report.isLimited" class="report-lead">
            <span class="report-index">!</span>
            <div>
              <h2>受限分析</h2>
              <p>{{ report.limitedReason }}</p>
            </div>
          </section>
          <section
            v-for="(section, index) in sections"
            :key="section.title"
            :class="{ 'report-lead': index === 0, 'risk-section': section.title === '风险与不确定性' }"
          >
            <span class="report-index">{{ String(index + 1).padStart(2, '0') }}</span>
            <div>
              <h2>{{ section.title }}</h2>
              <p>{{ section.body }}</p>
            </div>
          </section>
          <section>
            <span class="report-index">·</span>
            <div>
              <h2>数据截止</h2>
              <p>
                行情 {{ formatDateTime(report.marketDataCutoffAt) }}
                <template v-if="report.newsDataCutoffAt">
                  · 资讯 {{ formatDateTime(report.newsDataCutoffAt) }}
                </template>
              </p>
            </div>
          </section>
          <footer>
            <p>
              {{ report.disclaimer }}（模型 {{ report.modelCode }} · 模板
              {{ report.promptVersion }}）
            </p>
          </footer>
          <!-- AI-08：在本次任务的会话里追问。问题非空才可提交。 -->
          <div class="follow-up-composer">
            <textarea
              v-model="followUpQuestion"
              rows="2"
              :maxlength="sceneDefinition?.questionMaxLength ?? 500"
              placeholder="针对这份报告继续追问，例如：把风险部分展开成可核对的检查清单？"
            />
            <div>
              <button
                type="button"
                :disabled="!followUpQuestion.trim() || followUpSubmitting"
                @click="submitFollowUp"
              >
                {{ followUpSubmitting ? '正在提交追问…' : '追问' }}
              </button>
            </div>
          </div>
        </article>

        <div v-else class="report-empty">
          <span><Sparkles :size="28" /></span>
          <h2>研究画布等待你的问题</h2>
          <p>选择左侧场景与标的，预览数据后开始分析。结果会同时保存到「分析历史」。</p>
        </div>
      </main>

      <aside class="evidence-drawer">
        <header><span>来源引用</span><b>{{ evidences.length }}</b></header>

        <!--
          四种状态必须分开表达：加载失败（可重试）、还没有报告、加载中、报告没有引用。
          合并任意两个都会让用户把"读不到"当成"没有"——而这两者的处置完全不同。
        -->
        <p v-if="evidenceError" class="state-note state-note--error">
          {{ evidenceError }}
          <button
            v-if="report"
            class="link-button"
            type="button"
            @click="loadEvidence(report.reportId)"
          >
            重试
          </button>
        </p>
        <p v-else-if="!report" class="state-note">报告生成后，这里会列出它引用的来源。</p>
        <p v-else-if="loadingEvidence" class="state-note">正在加载来源引用…</p>
        <div v-else-if="evidences.length === 0" class="evidence-empty">
          <p>这份报告没有引用任何来源。</p>
        </div>

        <ul v-else class="evidence-list">
          <!--
            按 `evidenceNo` 渲染而不是数组下标：正文里的 [n] 就是这个编号。
            用下标会让"服务端少返回一条"表现成编号整体错位，而错位不会报错。
          -->
          <li v-for="item in evidences" :key="item.evidenceNo">
            <div class="evidence-head">
              <span class="evidence-no">[{{ item.evidenceNo }}]</span>
              <span class="evidence-type">{{ evidenceTypeLabel(item.evidenceType) }}</span>
            </div>
            <p class="evidence-title">{{ item.sourceTitle }}</p>
            <p class="evidence-summary">{{ item.evidenceSummary }}</p>
            <p class="evidence-meta">
              {{ accessLabel(item.accessStatus) }}
              <template v-if="item.sourcePublishedAt">
                · 发布 {{ formatDateTime(item.sourcePublishedAt) }}
              </template>
              <template v-if="item.dataTime"> · 数据 {{ formatDateTime(item.dataTime) }}</template>
            </p>
            <!--
              没有 sourceUrl 就不给链接：授权受限与协议不合规都已由服务端判过，
              前端不再猜是哪一种。`noopener noreferrer` 是契约 §24 的硬要求。
            -->
            <a
              v-if="item.sourceUrl"
              class="evidence-link"
              :href="item.sourceUrl"
              target="_blank"
              rel="noopener noreferrer"
            >
              查看原文
            </a>
          </li>
        </ul>
      </aside>
    </section>
  </div>
</template>
