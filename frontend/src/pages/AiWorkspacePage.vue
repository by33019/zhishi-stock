<script setup lang="ts">
import { Search, Sparkles } from '@lucide/vue'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

import PageHeader from '@/components/PageHeader.vue'
import { useRemoteData } from '@/composables/useRemoteData'
import { createTask, getReport, getScenes, getTask, previewContext } from '@/services/aiApi'
import { searchSecurities } from '@/services/securityApi'
import type {
  AiContextPreview,
  AiContextTarget,
  AiReportDetail,
  AiScene,
  AiTaskQuota,
  AiTaskSummary,
} from '@/types/domain'
import { formatDateTime } from '@/utils/format'

/**
 * AI 研究工作台（契约 AI-01 / AI-02 / AI-03 / AI-04 + HIS-06）。
 *
 * 这一页此前是纯静态原型：写死的场景下拉、写死的标的「浦发银行 SH.600000」、
 * 写死的「今日剩余 18 次分析」、写死的"已纳入实时行情与授权资讯"，
 * 以及一个 180 毫秒后塞进一份**编造报告**的 `generateReport()`。
 *
 * ## 本轮范围与边界（都在界面上有交代，不留"点了没反应"的入口）
 *
 * - **走轮询而不是 SSE**：AI-04 已能表达"排队中 / 运行中 / 已完成"，
 *   而 SSE（AI-05）要额外维护断线重连与 `Last-Event-ID` 续传，与"先把闭环跑通"
 *   不是同一件事，留作独立增量。
 * - **只支持单标的场景**：证券走 STK-01 检索；市场用契约文档写明的对外标识 `CN`。
 *   板块与多标的对比需要各自的检索与多选交互，**界面直接说明并不给提交入口**。
 * - 取消 / 重试 / 追问（AI-06 / AI-07 / AI-08）与来源引用（HIS-07）未接入。
 */

const { data: scenes, error: scenesError, reload: reloadScenes } = useRemoteData(getScenes)

const scene = ref<AiScene>('STOCK')
const question = ref('')

const sceneDefinition = computed(() =>
  scenes.value?.find((definition) => definition.scene === scene.value),
)

/** 本轮支持的场景：恰好一个 SECURITY 或 MARKET 目标。 */
const sceneSupported = computed(() => {
  const definition = sceneDefinition.value
  if (!definition) return false
  if (definition.minTargets !== 1 || definition.maxTargets !== 1) return false
  const types = definition.allowedTargetTypes
  return types.length === 1 && (types[0] === 'SECURITY' || types[0] === 'MARKET')
})

const needsSecuritySearch = computed(
  () => sceneDefinition.value?.allowedTargetTypes[0] === 'SECURITY',
)

// ---------- 目标选择 ----------

const targetQuery = ref('')
const targetResults = ref<AiContextTarget[]>([])
const target = ref<AiContextTarget | null>(null)
const searching = ref(false)
const searchError = ref('')

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
  target.value = candidate
  targetResults.value = []
  targetQuery.value = ''
  // 换了目标就丢掉上一次的预览：它描述的是旧目标的取数结果，留着会误导
  preview.value = undefined
}

/** 场景一换，目标与预览都失效——不同场景接受的标的类型不同。 */
watch(scene, () => {
  target.value = needsSecuritySearch.value ? null : marketTarget()
  targetResults.value = []
  preview.value = undefined
  searchError.value = ''
})

// ---------- 预览（AI-02，不消耗配额）----------

const preview = ref<AiContextPreview>()
const previewError = ref('')
const previewing = ref(false)

async function runPreview() {
  if (!target.value) return
  previewing.value = true
  previewError.value = ''
  try {
    preview.value = await previewContext({ scene: scene.value, targets: [target.value] })
  } catch (cause) {
    const failure = cause as { message?: string; traceId?: string }
    previewError.value = failure.message ?? '预览失败，请稍后重试。'
  } finally {
    previewing.value = false
  }
}

// ---------- 提交与轮询（AI-03 / AI-04 / HIS-06）----------

const submitting = ref(false)
const submitError = ref('')
const task = ref<AiTaskSummary>()
const quota = ref<AiTaskQuota>()
const report = ref<AiReportDetail>()

const TERMINAL = new Set(['COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELED'])

/** 轮询的墙钟上限：任务卡死时不能让页面一直转圈。 */
const POLL_TIMEOUT_MS = 180_000
let pollTimer: ReturnType<typeof setInterval> | undefined
let pollStartedAt = 0

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = undefined
  }
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
    if (latest.status === 'COMPLETED' && latest.reportId) {
      report.value = await getReport(latest.reportId)
    }
  } catch (cause) {
    stopPolling()
    const failure = cause as { message?: string }
    submitError.value = failure.message ?? '查询任务状态失败。'
  }
}

async function submit() {
  if (!target.value) return
  submitting.value = true
  submitError.value = ''
  report.value = undefined
  task.value = undefined
  try {
    const accepted = await createTask(
      {
        scene: scene.value,
        targets: [target.value],
        question: question.value.trim() || undefined,
      },
      // 键的语义是"一次用户意图"：这里一次点击只提交一次，所以每次点击都是新意图
      crypto.randomUUID(),
    )
    task.value = accepted.task
    quota.value = accepted.quota
    pollStartedAt = Date.now()
    stopPolling()
    pollTimer = setInterval(() => void pollOnce(accepted.task.taskId), 3000)
    void pollOnce(accepted.task.taskId)
  } catch (cause) {
    const failure = cause as { message?: string; code?: string; traceId?: string }
    submitError.value = failure.message ?? '提交失败，请稍后重试。'
  } finally {
    submitting.value = false
  }
}

// `useRemoteData` 只提供 reload，不会自动执行——不挂载时调一次，页面会永远停在"加载中"。
onMounted(reloadScenes)
onUnmounted(stopPolling)

const running = computed(() => Boolean(task.value) && !TERMINAL.has(task.value!.status))

const qualityLabel = computed(() =>
  report.value?.qualityStatus === 'LIMITED' ? '受限分析' : '证据完整',
)

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

        <template v-if="sceneSupported">
          <label v-if="needsSecuritySearch">
            分析标的
            <span class="inline-search">
              <Search :size="15" />
              <input
                v-model="targetQuery"
                type="search"
                placeholder="输入代码或名称，回车检索"
                @keyup.enter="searchTargets"
              />
            </span>
          </label>
          <p v-if="searching" class="state-note">正在检索…</p>
          <p v-else-if="searchError" class="state-note state-note--error">{{ searchError }}</p>
          <ul v-if="targetResults.length" class="target-candidates">
            <li v-for="candidate in targetResults" :key="candidate.targetId">
              <button class="link-button" type="button" @click="chooseTarget(candidate)">
                {{ candidate.targetName }}（{{ candidate.targetCode }}）
              </button>
            </li>
          </ul>
          <p v-if="target" class="selected-target">
            已选：{{ target.targetName }}（{{ target.targetCode }}）
          </p>
          <p v-else-if="!needsSecuritySearch" class="selected-target">已选：中国 A 股（CN）</p>
        </template>
        <!-- 不支持就不给入口：一个点了没反应的按钮比禁用更糟。 -->
        <p v-else-if="sceneDefinition" class="state-note state-note--warn">
          该场景本轮尚未接入标的检索（板块需板块检索，多标的对比需多选），因此不能在此提交。
          已有结果可到「分析历史」查看。
        </p>

        <div class="setup-divider" />
        <button
          class="secondary-button"
          type="button"
          :disabled="!target || previewing"
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

        <!-- 配额只在提交后才有权威来源（AI-03 的响应）；提交前不显示，不编一个数 -->
        <p v-if="quota" class="quota-note">
          今日剩余 {{ quota.remainingCount }} / {{ quota.dailyLimit }} 次 · 并发上限
          {{ quota.concurrentLimit }}
        </p>
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
              :disabled="!target || submitting || running || preview?.canGenerate === false"
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
          <p>任务 {{ task?.taskId }} · 每 3 秒刷新一次状态</p>
        </div>

        <article v-else-if="task && task.status !== 'COMPLETED'" class="ai-report">
          <header><div><span class="quality-badge">未产出报告</span></div></header>
          <section class="report-lead">
            <span class="report-index">!</span>
            <div>
              <h2>本次分析未产出报告</h2>
              <p>任务状态：{{ task.status }}</p>
              <p v-if="task.error">{{ task.error.message }}（{{ task.error.code }}）</p>
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
        </article>

        <div v-else class="report-empty">
          <span><Sparkles :size="28" /></span>
          <h2>研究画布等待你的问题</h2>
          <p>选择左侧场景与标的，预览数据后开始分析。结果会同时保存到「分析历史」。</p>
        </div>
      </main>

      <aside class="evidence-drawer">
        <header><span>来源引用</span><b>0</b></header>
        <!--
          引用的读接口（HIS-07）尚未实现：报告正文里的 [n] 编号在库里暂时**没有行可反查**。
          原型这里渲染的是一份写死的 evidence 数组，删掉它而不是留着——
          一份看起来可靠的来源列表比留空更糟。
        -->
        <div class="evidence-empty">
          <p>来源引用将在 HIS-07 交付后列在这里。当前报告正文中的 [n] 编号尚无接口可反查。</p>
        </div>
      </aside>
    </section>
  </div>
</template>
