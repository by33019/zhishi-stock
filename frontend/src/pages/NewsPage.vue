<script setup lang="ts">
import { ExternalLink, Filter, Search } from '@lucide/vue'
import { computed, onMounted, ref, watch } from 'vue'

import PageHeader from '@/components/PageHeader.vue'
import { useRemoteData } from '@/composables/useRemoteData'
import { getNews, getNewsOptions } from '@/services/newsApi'
import type { NewsRelationSummary, NewsSummary } from '@/types/domain'
import { formatDateTime } from '@/utils/format'

const PAGE_SIZE = 20

/** 「全部」标签的取值：**不传** `newsTypes`（不是传空串让服务端去猜）。 */
const ALL_TYPES = ''

/**
 * 枚举值 → 中文名。这是**显示层**的映射，不是可选值清单——可选值来自 NEWS-04 的
 * `newsTypes`，未知取值回退为原值，不渲染成空白（否则服务端新增类型时前端会静默丢标签）。
 */
const NEWS_TYPE_LABELS: Record<string, string> = {
  NEWS: '快讯',
  ANNOUNCEMENT: '公告',
  RESEARCH: '研报',
  OTHER: '其他',
}

const activeType = ref(ALL_TYPES)
const draftKeyword = ref('')
const keyword = ref('')
const page = ref(1)

const { data: options, reload: reloadOptions } = useRemoteData(() => getNewsOptions())

const {
  data: feed,
  loading,
  error,
  reload,
} = useRemoteData(() => getNews({
  newsTypes: activeType.value || undefined,
  keyword: keyword.value || undefined,
  page: page.value,
  size: PAGE_SIZE,
}))

/**
 * 用一个查询键驱动重新请求，而不是给每个条件各挂一个 `watch`：多个 `watch` 会在
 * 「切类型同时把页码归 1」时触发两次请求，其中一次的结果必然被覆盖。
 */
const queryKey = computed(() => `${activeType.value}|${keyword.value}|${page.value}`)
watch(queryKey, () => reload())

onMounted(() => {
  reloadOptions()
  reload()
})

/** 标签 = 「全部」+ 服务端给的可用类型，顺序也来自服务端。 */
const typeTabs = computed(() => [
  { value: ALL_TYPES, label: '全部' },
  ...(options.value?.newsTypes ?? []).map((value) => ({ value, label: newsTypeLabel(value) })),
])

function newsTypeLabel(value: string): string {
  return NEWS_TYPE_LABELS[value] ?? value
}

function selectType(next: string) {
  activeType.value = next
  page.value = 1
}

/** 关键字**显式提交**：输入即搜会让每个按键都发一次请求（同 M3-03 的选股面板约定）。 */
function submitKeyword() {
  keyword.value = draftKeyword.value.trim()
  page.value = 1
}

/**
 * `DELAYED` 与 `UNAVAILABLE` 是**两件事**，不能合成一个提示条：
 * 前者表示「有最近一次有效快照，但不是实时的」，后者表示「整批取数失败」。
 * 给 `UNAVAILABLE` 挂「当前展示最近有效快照」是在编造一次并不存在的快照
 * （M3-03 在自选导语上踩过同一个坑）。
 */
const isDelayed = computed(() => feed.value?.dataStatus === 'DELAYED')
const isUnavailable = computed(() => feed.value?.dataStatus === 'UNAVAILABLE')

interface RelationTag {
  key: string
  label: string
  to?: string
}

/**
 * 关联标签。`targetId` 是**对外标识**，可直接用于路由。
 *
 * `MARKET`（如 `CN`）没有对应页面，`targetId` 为空时也拼不出路由——
 * 两种情况都只渲染文本，不渲染链接（M2-06 / M2-11 的教训）。
 */
function relationTags(item: NewsSummary): RelationTag[] {
  return item.relations.map((relation) => ({
    key: `${relation.targetType}-${relation.targetId}`,
    label: relation.targetCode || relation.targetName,
    to: relationHref(relation),
  }))
}

function relationHref(relation: NewsRelationSummary): string | undefined {
  if (!relation.targetId) return undefined
  if (relation.targetType === 'SECURITY') return `/stocks/${relation.targetId}`
  if (relation.targetType === 'SECTOR') return `/sectors/${relation.targetId}`
  return undefined
}
</script>

<template>
  <section v-if="error" class="market-state-panel" role="alert">
    <h1>资讯暂时无法加载</h1>
    <p>{{ error.message }}</p>
    <small v-if="error.traceId">追踪编号：{{ error.traceId }}</small>
    <button data-testid="news-retry" type="button" @click="reload">重新加载</button>
  </section>

  <div v-else-if="feed" class="business-page page-enter">
    <PageHeader
      eyebrow="EVENT INTELLIGENCE"
      title="资讯中心"
      description="将授权新闻、交易所公告和研究摘要关联到具体标的，降低信息噪声。"
      :data-time="feed.lastSuccessfulSyncAt ? formatDateTime(feed.lastSuccessfulSyncAt) : ''"
    />

    <section class="news-layout">
      <div class="news-main">
        <header class="news-toolbar">
          <div class="segmented-tabs">
            <button
              v-for="tab in typeTabs"
              :key="tab.value || 'ALL'"
              data-testid="news-type-tab"
              :class="{ active: activeType === tab.value }"
              type="button"
              @click="selectType(tab.value)"
            >
              {{ tab.label }}
            </button>
          </div>
          <div class="toolbar-actions">
            <label class="inline-search">
              <Search :size="14" />
              <input
                v-model="draftKeyword"
                data-testid="news-keyword"
                placeholder="搜索事件"
                @keyup.enter="submitKeyword"
              />
            </label>
            <button data-testid="news-search" type="button" @click="submitKeyword">搜索</button>
            <button
              class="filter-button"
              data-testid="news-time-filter"
              type="button"
              disabled
              title="时间筛选待接入：NEWS-01 已支持 startAt / endAt，但原型未提供设计稿"
            >
              <Filter :size="14" /> 时间筛选
            </button>
          </div>
        </header>

        <p v-if="isDelayed" class="component-unavailable" data-testid="news-data-status">
          当前展示最近有效快照（数据截止 {{ formatDateTime(feed.lastSuccessfulSyncAt) }}）。
        </p>

        <div class="news-feed">
          <article v-for="(item, index) in feed.items" :key="item.newsId">
            <div class="news-feed__time">
              <strong>{{ formatDateTime(item.publishedAt).split(' ')[1] }}</strong>
              <span>{{ formatDateTime(item.publishedAt).split(' ')[0] }}</span>
            </div>
            <div class="news-feed__content">
              <div>
                <span class="content-type">{{ newsTypeLabel(item.newsType) }}</span>
                <span
                  v-for="tag in relationTags(item)"
                  :key="tag.key"
                  class="symbol-tag"
                  data-testid="news-relation"
                >
                  <RouterLink v-if="tag.to" :to="tag.to">{{ tag.label }}</RouterLink>
                  <template v-else>{{ tag.label }}</template>
                </span>
              </div>
              <h2>{{ item.title }}</h2>
              <p>{{ item.summary ?? '来源未提供授权范围内的摘要' }}</p>
              <footer>
                <span>{{ item.sourceName }} · 已通过来源校验</span>
                <span v-if="item.originalAccessStatus === 'UNAVAILABLE'" class="original-unavailable">
                  原文不可用
                </span>
                <template v-else>
                  <a
                    data-testid="news-original"
                    :href="item.originalUrl"
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    查看原文 <ExternalLink :size="13" />
                  </a>
                  <span v-if="item.originalAccessStatus === 'UNKNOWN'" class="original-unknown">
                    （原文状态未知）
                  </span>
                </template>
              </footer>
            </div>
            <span class="news-feed__number">{{ String(index + 1).padStart(2, '0') }}</span>
          </article>
        </div>

        <p v-if="!feed.items.length" class="component-unavailable" data-testid="news-empty">
          {{ isUnavailable ? '资讯源暂不可用，当前无法获取资讯' : '暂无符合条件的资讯' }}
        </p>

        <footer class="table-footer">
          <span>共 {{ feed.total }} 条资讯 · 第 {{ feed.page }} / {{ feed.totalPages }} 页</span>
          <div>
            <button type="button" :disabled="feed.page <= 1" @click="page = feed.page - 1">
              上一页
            </button>
            <button class="active" type="button">{{ feed.page }}</button>
            <button
              data-testid="news-next"
              type="button"
              :disabled="!feed.hasNext"
              @click="page = feed.page + 1"
            >
              下一页
            </button>
          </div>
        </footer>
      </div>

      <aside class="news-sidebar">
        <section>
          <span class="eyebrow">TODAY</span>
          <h2>今日事件密度</h2>
          <p class="component-unavailable">尚未实现：平台暂无事件密度聚合口径。</p>
        </section>
        <section>
          <span class="eyebrow">TOPICS</span>
          <h2>高频主题</h2>
          <p class="component-unavailable">尚未实现：平台暂无主题聚类口径。</p>
        </section>
      </aside>
    </section>
  </div>

  <div v-else-if="loading" class="page-loading" aria-label="正在加载资讯">
    <span /><span /><span />
  </div>
</template>
