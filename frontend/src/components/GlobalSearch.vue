<script setup lang="ts">
import { Search } from '@lucide/vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import { useRemoteData } from '@/composables/useRemoteData'
import { searchSecurities } from '@/services/securityApi'
import type { SecuritySearchMatch, SecuritySummary } from '@/types/domain'

/** PRD QTE-01：300 毫秒防抖。 */
const DEBOUNCE_MS = 300

/**
 * 契约上限是 50，PRD 写的是"1-20 字符"。
 *
 * 取 50 而不是 20：取 20 会在用户**粘贴**长文本时静默截断，被截掉的部分用户看不见；
 * 取 50 既不会给后端送必然 400 的请求，也不修改用户输入。
 */
const MAX_QUERY_LENGTH = 50

/** PRD QTE-01：「最多 10 条匹配股票」。显式传值，不依赖后端默认值。 */
const RESULT_LIMIT = 10

const router = useRouter()
const root = ref<HTMLElement>()
const input = ref<HTMLInputElement>()

const keyword = ref('')
/** 视觉上的聚焦态。与 `open` 分开：面板可以因点击外部而收起，输入框却仍然握着光标。 */
const focused = ref(false)
const open = ref(false)
const activeIndex = ref(-1)

const trimmed = computed(() => keyword.value.trim())

const {
  data: result,
  loading,
  error,
  reload,
} = useRemoteData(() => searchSecurities(trimmed.value, RESULT_LIMIT))

/**
 * `trimmed` 为空时**必须**隐藏结果。
 *
 * `useRemoteData` 不会在重新请求前清掉上一次的 `data`，所以用户清空输入后
 * 面板里会继续挂着上一次的结果——看起来像"搜到了什么"，其实输入框是空的。
 */
const items = computed(() => (trimmed.value ? result.value?.items ?? [] : []))

/** 键盘快捷键提示：Mac 显示 ⌘K，其余平台显示 Ctrl K。 */
const shortcutHint = computed(() =>
  typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.userAgent)
    ? '⌘ K'
    : 'Ctrl K')

let debounceTimer: number | undefined

watch(trimmed, (value) => {
  activeIndex.value = -1
  window.clearTimeout(debounceTimer)
  // 空输入不发请求：契约要求 q 裁剪后 1–50 字符，否则必然 400。
  if (!value) return
  debounceTimer = window.setTimeout(() => reload(), DEBOUNCE_MS)
})

function select(match: SecuritySearchMatch) {
  open.value = false
  activeIndex.value = -1
  keyword.value = ''
  // 用响应里的 securityId，而不是任何写死的 ID——写死的 ID 在主数据里不存在，点开就是 404。
  void router.push(`/stocks/${match.security.securityId}`)
}

function move(step: number) {
  const size = items.value.length
  if (!size) return
  open.value = true
  // 环绕：从 -1 往下走是 0，从末项往下走回到 -1（未选中）
  const next = activeIndex.value + step
  activeIndex.value = next < -1 ? size - 1 : next >= size ? -1 : next
}

function commit() {
  if (!items.value.length) return
  select(items.value[activeIndex.value >= 0 ? activeIndex.value : 0]!)
}

function onKeydown(event: KeyboardEvent) {
  switch (event.key) {
    case 'ArrowDown':
      event.preventDefault()
      move(1)
      break
    case 'ArrowUp':
      event.preventDefault()
      move(-1)
      break
    case 'Enter':
      commit()
      break
    case 'Escape':
      open.value = false
      activeIndex.value = -1
      break
    default:
      break
  }
}

/** `⌘K` / `Ctrl+K` 聚焦输入框。没有这个监听，界面上那个 `<kbd>` 就是假的。 */
function onWindowKeydown(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    input.value?.focus()
  }
}

/**
 * 点击组件外部关闭面板。
 *
 * 原型用 `@blur` + `setTimeout(120ms)` 再配合 `@mousedown.prevent` 让"点击先于失焦生效"——
 * 那是靠时间窗赌顺序，机器卡顿时 120ms 不够，点击就会丢。这里改成判断点击落点，行为确定。
 */
function onDocumentPointerDown(event: MouseEvent) {
  if (root.value && !root.value.contains(event.target as Node)) {
    open.value = false
    activeIndex.value = -1
  }
}

onMounted(() => {
  window.addEventListener('keydown', onWindowKeydown)
  document.addEventListener('mousedown', onDocumentPointerDown)
})

onBeforeUnmount(() => {
  window.clearTimeout(debounceTimer)
  window.removeEventListener('keydown', onWindowKeydown)
  document.removeEventListener('mousedown', onDocumentPointerDown)
})

/**
 * 把 `highlight` 原文子串从展示文本里切出来。
 *
 * 后端只说命中"哪个类别"（`matchedField`），没说具体是哪个字段的哪一段，
 * 因此对代码与名称各试一次：谁包含这个子串就切谁。这样 CODE 与 NAME 两种情况
 * 共用一条逻辑，也不会因为将来后端调整 `matchedField` 取值而把高亮打到错误的字段上。
 */
function split(value: string, highlight: string | null) {
  // 先单独收窄 highlight，不能把判空塞进三元表达式：`index >= 0` 反推不出 `highlight` 非空。
  if (!highlight) return { before: value, hit: '', after: '' }
  const index = value.toLowerCase().indexOf(highlight.toLowerCase())
  if (index < 0) return { before: value, hit: '', after: '' }
  return {
    before: value.slice(0, index),
    hit: value.slice(index, index + highlight.length),
    after: value.slice(index + highlight.length),
  }
}

/**
 * 状态标记，优先级：停牌 → 上市状态 → ST。
 *
 * 停牌排在 ST 前面：一只停牌的 ST 股，用户更需要知道它现在停牌了。
 */
function statusLabel(security: SecuritySummary): string | null {
  if (security.isSuspended) return '停牌'
  switch (security.listingStatus) {
    case 'DELISTED':
      return '退市'
    case 'PRELISTED':
      return '待上市'
    case 'SUSPENDED':
      return '停牌'
    default:
      return security.isSt ? 'ST' : null
  }
}

const rows = computed(() => items.value.map((match) => ({
  match,
  security: match.security,
  name: split(match.security.securityName, match.highlight),
  code: split(match.security.securityCode, match.highlight),
  status: statusLabel(match.security),
})))
</script>

<template>
  <div ref="root" class="global-search" :class="{ 'is-focused': focused }">
    <Search :size="17" />
    <input
      ref="input"
      v-model="keyword"
      aria-label="全局证券搜索"
      :maxlength="MAX_QUERY_LENGTH"
      placeholder="输入代码或名称搜索证券"
      role="combobox"
      aria-autocomplete="list"
      aria-controls="global-search-results"
      :aria-expanded="open"
      :aria-activedescendant="activeIndex >= 0 ? `global-search-option-${activeIndex}` : undefined"
      :aria-busy="loading"
      @focus="focused = true; open = true"
      @blur="focused = false"
      @keydown="onKeydown"
    />
    <kbd>{{ shortcutHint }}</kbd>

    <div
      v-if="open"
      id="global-search-results"
      class="global-search__results"
      role="listbox"
      aria-label="搜索建议"
    >
      <template v-if="error">
        <p>搜索失败</p>
        <div class="global-search__status" role="alert">
          <span>{{ error.message }}</span>
          <small v-if="error.traceId">追踪编号：{{ error.traceId }}</small>
          <button data-testid="search-retry" type="button" @click="reload">重试</button>
        </div>
      </template>

      <template v-else-if="!trimmed">
        <p>证券搜索</p>
        <div class="global-search__status">
          输入代码或名称开始搜索，例如 600000 或 浦发银行。
        </div>
      </template>

      <template v-else-if="loading && !rows.length">
        <p>搜索中</p>
        <div class="global-search__status">正在检索「{{ trimmed }}」…</div>
      </template>

      <template v-else-if="rows.length">
        <p>匹配 {{ rows.length }} 条</p>
        <button
          v-for="(row, index) in rows"
          :key="row.security.securityId"
          :id="`global-search-option-${index}`"
          type="button"
          role="option"
          :aria-selected="index === activeIndex"
          :class="{ 'is-active': index === activeIndex }"
          @click="select(row.match)"
        >
          <span>
            <strong>
              <span>{{ row.name.before }}</span><mark v-if="row.name.hit">{{ row.name.hit }}</mark><span>{{ row.name.after }}</span>
            </strong>
            <small>
              {{ row.security.exchangeCode }} ·
              <span>{{ row.code.before }}</span><mark v-if="row.code.hit">{{ row.code.hit }}</mark><span>{{ row.code.after }}</span>
            </small>
          </span>
          <span v-if="row.status" class="global-search__badge">{{ row.status }}</span>
        </button>
      </template>

      <template v-else>
        <p>无匹配结果</p>
        <div class="global-search__status">没有匹配「{{ trimmed }}」的证券。</div>
      </template>
    </div>
  </div>
</template>
