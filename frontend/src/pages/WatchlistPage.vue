<script setup lang="ts">
import { MoreHorizontal, Plus, Sparkles, Star, Trash2 } from '@lucide/vue'
import { computed, onMounted, ref } from 'vue'

import PageHeader from '@/components/PageHeader.vue'
import { useRemoteData, type RemoteError } from '@/composables/useRemoteData'
import { searchSecurities } from '@/services/securityApi'
import {
  addWatchlistItem,
  createWatchlistGroup,
  deleteWatchlistGroup,
  getWatchlistOverview,
  moveWatchlistItem,
  removeWatchlistItem,
  renameWatchlistGroup,
  reorderWatchlistGroups,
  reorderWatchlistItems,
} from '@/services/watchlistApi'
import type { WatchlistGroup, WatchlistItem } from '@/types/domain'
import { formatChangeRate, formatDateTime, formatMoney, trendClass } from '@/utils/format'

/** PRD QTE-01：「最多 10 条匹配股票」。显式传值，不依赖后端默认值。 */
const RESULT_LIMIT = 10

/**
 * 首屏一次 WAT-11 取全（分组 + 自选行情 + 市场状态 + 数据状态）。
 *
 * **不传 `groupId`**：传它需要"先请求一次拿分组清单、再带 `groupId` 请求第二次"，
 * 中间那一帧会把所有分组的股票都画出来再收敛到当前组（用户看到卡片闪一下）。
 * 不传时服务端返回本人全部有效分组的全部自选项，因此客户端按 `groupId` 选择
 * 不是"在残缺数据上过滤"——侧栏的 `itemCount` 与列表行数同源，不会互相矛盾。
 */
const { data: overview, loading, error, reload } = useRemoteData(() => getWatchlistOverview())

/** 用户点选的分组；未点选时回退到默认分组（不写状态，因此首屏不会多一次请求）。 */
const activeGroupId = ref<string>()

const groups = computed(() => overview.value?.groups ?? [])

const activeGroup = computed(
  () =>
    groups.value.find((group) => group.groupId === activeGroupId.value) ??
    groups.value.find((group) => group.isDefault) ??
    groups.value[0],
)

const visibleItems = computed(() => {
  const groupId = activeGroup.value?.groupId
  if (!groupId) return []
  return (overview.value?.items ?? []).filter((item) => item.groupId === groupId)
})

const dataTimeText = computed(() => formatDateTime(overview.value?.dataTime ?? null))

/**
 * 数据时效提示条：只有**真的画出了卡片**才解释这些卡片的时效。
 *
 * 用户自选为空时后端给 `dataStatus = 'UNAVAILABLE'`、`snapshotVersion = ''`、`dataTime = null`
 * （`WatchlistItemService.overview`：批次为 null 的口径，且**不发起整批取数**）。
 * 此时再挂一条"当前展示最近有效快照（数据截止 --）"就是在编造一次不存在的快照——
 * 空自选账号（新注册用户）必然走到这条路径，实测确认。
 *
 * 反过来，只要当前分组有卡片，这批快照就一定存在，`dataTime` 也一定非空。
 */
const showsStaleNotice = computed(
  () => visibleItems.value.length > 0 && overview.value?.dataStatus !== 'REALTIME',
)

/** 写操作的失败只提示，不替换整页数据——一次写失败不该把已经拿到的自选清空。 */
const mutating = ref(false)
const actionError = ref<RemoteError>()

/**
 * 一次**用户意图**一个幂等键，重试时复用。
 *
 * 复用而不是每次重发都换新键：WAT-02 的名称唯一索引会让"第一次其实成功了但响应丢了"
 * 的重试直接撞 409 `WATCHLIST_GROUP_NAME_EXISTS`，用户看到的是"这个名字已存在"，
 * 而那个名字正是他自己刚创建的。成功即清除，因此"先删掉再加同名分组"仍是一次新意图。
 */
const intentKeys = new Map<string, string>()

function idempotencyKeyFor(intent: string): string {
  const existing = intentKeys.get(intent)
  if (existing) return existing
  const created = crypto.randomUUID()
  intentKeys.set(intent, created)
  return created
}

async function mutate(action: () => Promise<unknown>, intent?: string) {
  mutating.value = true
  actionError.value = undefined
  try {
    await action()
    if (intent) intentKeys.delete(intent)
    // 服务端会改写 sortNo / version，WAT-09 还可能删掉源行，本地推断必然失真。
    await reload()
  } catch (cause) {
    const failure = cause as { message?: string; traceId?: string }
    actionError.value = {
      message: failure?.message ?? '操作失败，请稍后重试',
      traceId: failure?.traceId,
    }
  } finally {
    mutating.value = false
  }
}

// ---------- 选股面板（WAT-07） ----------

const adding = ref(false)
const keyword = ref('')

/**
 * 显式提交而不是输入即搜：本面板是"往这个分组加一只股票"的明确动作，
 * 打 6 个字符发 6 次请求并不划算，也让"空输入"没法被明确拒绝。
 */
const { data: searchResult, loading: searching, error: searchError, reload: runSearch } =
  useRemoteData(() => searchSecurities(keyword.value.trim(), RESULT_LIMIT))

const matches = computed(() => searchResult.value?.items ?? [])

function openAddPanel() {
  adding.value = true
  keyword.value = ''
  searchResult.value = undefined
}

function closeAddPanel() {
  adding.value = false
}

function submitSearch() {
  if (!keyword.value.trim()) return
  void runSearch()
}

async function addItem(securityId: string) {
  const groupId = activeGroup.value?.groupId
  if (!groupId) return
  const intent = `add:${groupId}:${securityId}`
  await mutate(
    () => addWatchlistItem(groupId, securityId, idempotencyKeyFor(intent)),
    intent,
  )
  closeAddPanel()
}

// ---------- 自选项操作（WAT-08 / WAT-09 / WAT-10） ----------

/** 当前展开操作菜单的自选项；同时只允许一个（两张卡片的菜单都开着会让"移出"目标含混）。 */
const openItemMenu = ref<string>()

function toggleItemMenu(itemId: string) {
  openItemMenu.value = openItemMenu.value === itemId ? undefined : itemId
}

function removeItem(item: WatchlistItem) {
  void mutate(() => removeWatchlistItem(item.groupId, item.itemId))
}

function moveItem(item: WatchlistItem, targetGroupId: string) {
  openItemMenu.value = undefined
  void mutate(() => moveWatchlistItem(item.groupId, item.itemId, targetGroupId, item.version))
}

/** 可移动到的目标分组：排除它自己所在的那一组（WAT-09 不接受同组）。 */
function otherGroups(groupId: string): WatchlistGroup[] {
  return groups.value.filter((group) => group.groupId !== groupId)
}

const dragItemIndex = ref<number>()

function onItemDragStart(index: number) {
  dragItemIndex.value = index
}

function onItemDrop(index: number) {
  const from = dragItemIndex.value
  dragItemIndex.value = undefined
  const groupId = activeGroup.value?.groupId
  if (from === undefined || from === index || !groupId) return
  const itemIds = visibleItems.value.map((item) => item.itemId)
  const [moved] = itemIds.splice(from, 1)
  itemIds.splice(index, 0, moved!)
  // WAT-10 要求 itemIds 是**组内全部当前项**的排列；这里用的正是该组的完整列表。
  void mutate(() => reorderWatchlistItems(groupId, itemIds))
}

// ---------- 分组操作（WAT-02 / WAT-03 / WAT-04 / WAT-05） ----------

const creatingGroup = ref(false)
const openGroupMenu = ref<string>()
const renamingGroupId = ref<string>()
const deletingGroupId = ref<string>()
const groupNameDraft = ref('')
const moveTargetId = ref('')

function startCreateGroup() {
  creatingGroup.value = true
  groupNameDraft.value = ''
}

async function submitCreateGroup() {
  const name = groupNameDraft.value.trim()
  if (!name) return
  const intent = `create:${name}`
  await mutate(() => createWatchlistGroup(name, idempotencyKeyFor(intent)), intent)
  creatingGroup.value = false
}

function toggleGroupMenu(groupId: string) {
  openGroupMenu.value = openGroupMenu.value === groupId ? undefined : groupId
}

function startRename(group: WatchlistGroup) {
  openGroupMenu.value = undefined
  renamingGroupId.value = group.groupId
  groupNameDraft.value = group.groupName
}

async function submitRename(group: WatchlistGroup) {
  const name = groupNameDraft.value.trim()
  if (!name) return
  renamingGroupId.value = undefined
  await mutate(() => renameWatchlistGroup(group.groupId, name, group.version))
}

function startDelete(group: WatchlistGroup) {
  openGroupMenu.value = undefined
  deletingGroupId.value = group.groupId
  moveTargetId.value = ''
}

function submitDelete(group: WatchlistGroup) {
  if (group.itemCount > 0 && !moveTargetId.value) {
    actionError.value = { message: '请先选择要搬移自选项的目标分组' }
    return
  }
  deletingGroupId.value = undefined
  void mutate(() =>
    deleteWatchlistGroup(
      group.groupId,
      group.version,
      group.itemCount > 0 ? moveTargetId.value : undefined,
    ),
  )
}

const dragGroupIndex = ref<number>()

function onGroupDragStart(index: number) {
  dragGroupIndex.value = index
}

function onGroupDrop(index: number) {
  const from = dragGroupIndex.value
  dragGroupIndex.value = undefined
  if (from === undefined || from === index) return
  const groupIds = groups.value.map((group) => group.groupId)
  const [moved] = groupIds.splice(from, 1)
  groupIds.splice(index, 0, moved!)
  // WAT-05 要求 groupIds 是本人**全部有效分组**的排列，因此从响应里的全量顺序推导。
  void mutate(() => reorderWatchlistGroups(groupIds))
}

// ---------- 今日摘要（由真实行情算出，不是写死的句子） ----------

function rateOf(row: WatchlistItem): number | null {
  const raw = row.quote?.changeRate
  if (raw === null || raw === undefined || raw === '') return null
  const value = Number(raw)
  return Number.isFinite(value) ? value : null
}

function amountOf(row: WatchlistItem): number | null {
  const raw = row.quote?.tradeAmount
  if (raw === null || raw === undefined || raw === '') return null
  const value = Number(raw)
  return Number.isFinite(value) ? value : null
}

/**
 * 这只标的是否有可用于涨跌统计的行情。
 *
 * **停牌不算**。真实数据里 `sim-300750` 是 `isSuspended: true` 但**有**快照，
 * 且 `changeRate` 是 `"0.0000"`（最新价等于昨收）——按数值算它会被计入"平盘"，
 * 可它今天根本没有价格发现。这是"数字看起来对、结论其实错"的典型：
 * 停牌 / 缺快照 / 快照里没有涨跌幅的一律单独计数，不进涨跌分母。
 */
function isQuotable(row: WatchlistItem): boolean {
  return row.quote !== null && row.security?.isSuspended !== true
}

/** 取某个数值维度的第一名名称；名称缺失（悬空证券）的条目不参与。 */
function topBy(rows: WatchlistItem[], score: (row: WatchlistItem) => number | null) {
  let best: { name: string; value: number } | undefined
  for (const row of rows) {
    const value = score(row)
    const name = row.security?.securityName
    if (value === null || !name) continue
    if (!best || value > best.value) best = { name, value }
  }
  return best?.name ?? null
}

const summaryText = computed(() => {
  const rows = visibleItems.value
  if (!rows.length) return '这个分组还没有自选标的。'

  const quoted = rows
    .filter(isQuotable)
    .map((row) => ({ row, rate: rateOf(row) }))
    .filter((entry): entry is { row: WatchlistItem; rate: number } => entry.rate !== null)
  const up = quoted.filter((entry) => entry.rate > 0).length
  const down = quoted.filter((entry) => entry.rate < 0).length
  const flat = quoted.filter((entry) => entry.rate === 0).length
  // 停牌 / 无快照的标的既不是上涨也不是平盘，必须单独说明而不是算进任何一档。
  const excluded = rows.length - quoted.length

  const sentences = [
    `共 ${rows.length} 只标的，${up} 只上涨、${down} 只下跌`
      + `${flat ? `、${flat} 只平盘` : ''}`
      + `${excluded ? `（${excluded} 只无有效行情，未计入涨跌）` : ''}。`,
  ]

  // 居首只在"有有效行情"的标的里选：停牌股的成交额来自上一个交易日，不能算今天的第一。
  const quotableRows = quoted.map((entry) => entry.row)
  const leader = topBy(quotableRows, rateOf)
  if (leader) sentences.push(`涨幅居首 ${leader}。`)
  const richest = topBy(quotableRows, amountOf)
  if (richest) sentences.push(`成交额居首 ${richest}。`)
  return sentences.join(' ')
})

onMounted(() => {
  void reload()
})
</script>

<template>
  <section v-if="error" class="market-state-panel" role="alert">
    <h1>自选暂时无法加载</h1>
    <p>{{ error.message }}</p>
    <small v-if="error.traceId">追踪编号：{{ error.traceId }}</small>
    <button data-testid="watchlist-retry" type="button" @click="reload">重新加载</button>
  </section>

  <div v-else-if="overview" class="business-page page-enter">
    <PageHeader
      eyebrow="PERSONAL RADAR"
      title="我的自选"
      description="分组与行情都来自服务端；行情是整批快照，页面标注的截止时间即该批快照的时间。"
      :data-time="dataTimeText"
    >
      <button class="primary-button" data-testid="add-item-open" type="button" @click="openAddPanel">
        <Plus :size="15" /> 添加股票
      </button>
    </PageHeader>

    <p v-if="showsStaleNotice" class="component-unavailable" data-testid="watchlist-data-status">
      当前展示最近有效快照（数据截止 {{ dataTimeText }}）。
    </p>

    <ul
      v-if="overview.limitations.length"
      class="watch-limitations"
      data-testid="watchlist-limitations"
    >
      <li v-for="text in overview.limitations" :key="text">{{ text }}</li>
    </ul>

    <p v-if="actionError" class="watch-action-error" data-testid="watchlist-action-error" role="alert">
      <span>{{ actionError.message }}</span>
      <small v-if="actionError.traceId">追踪编号：{{ actionError.traceId }}</small>
    </p>

    <section v-if="adding" class="watch-add-panel" data-testid="add-item-panel">
      <p>把证券加入「{{ activeGroup?.groupName }}」</p>
      <form data-testid="add-item-search" @submit.prevent="submitSearch">
        <input
          v-model="keyword"
          aria-label="搜索证券"
          maxlength="50"
          placeholder="输入代码或名称，例如 600519"
          data-testid="add-item-input"
        />
        <button type="submit" :disabled="searching">搜索</button>
      </form>
      <p v-if="searchError" role="alert">搜索失败：{{ searchError.message }}</p>
      <div v-else-if="matches.length" class="watch-add-options">
        <button
          v-for="match in matches"
          :key="match.security.securityId"
          data-testid="add-item-option"
          type="button"
          :disabled="mutating"
          @click="addItem(match.security.securityId)"
        >
          <span>{{ match.security.securityName }}</span>
          <small>{{ match.security.fullSymbol }}</small>
        </button>
      </div>
      <p v-else-if="searchResult">没有匹配的证券。</p>
      <button type="button" @click="closeAddPanel">取消</button>
    </section>

    <section class="watchlist-layout">
      <aside class="watch-groups">
        <header>
          <span>自选分组</span>
          <button data-testid="group-create-open" type="button" @click="startCreateGroup">
            <Plus :size="14" />
          </button>
        </header>

        <form
          v-if="creatingGroup"
          class="watch-group-form"
          data-testid="group-create-form"
          @submit.prevent="submitCreateGroup"
        >
          <input
            v-model="groupNameDraft"
            aria-label="新分组名称"
            maxlength="20"
            placeholder="分组名称"
            data-testid="group-create-input"
          />
          <button type="submit" data-testid="group-create-submit" :disabled="mutating">
            创建
          </button>
        </form>

        <div v-for="(group, index) in groups" :key="group.groupId" class="watch-group">
          <button
            class="watch-group-tab"
            data-testid="group-tab"
            type="button"
            :data-group-id="group.groupId"
            :class="{ active: group.groupId === activeGroup?.groupId }"
            draggable="true"
            @click="activeGroupId = group.groupId"
            @dragstart="onGroupDragStart(index)"
            @dragover.prevent
            @drop="onGroupDrop(index)"
          >
            <span><Star :size="13" />{{ group.groupName }}</span>
            <b>{{ group.itemCount }}</b>
          </button>
          <button
            data-testid="group-actions-toggle"
            type="button"
            :data-group-id="group.groupId"
            :aria-label="`${group.groupName} 的分组操作`"
            @click="toggleGroupMenu(group.groupId)"
          >
            <MoreHorizontal :size="15" />
          </button>

          <div v-if="openGroupMenu === group.groupId" class="watch-group-menu" data-testid="group-menu">
            <button data-testid="group-rename" type="button" @click="startRename(group)">
              重命名
            </button>
            <button
              data-testid="group-delete"
              type="button"
              :disabled="group.isDefault"
              :title="group.isDefault ? '默认分组不可删除' : '删除该分组'"
              @click="startDelete(group)"
            >
              删除
            </button>
          </div>

          <form
            v-if="renamingGroupId === group.groupId"
            class="watch-group-form"
            data-testid="group-rename-form"
            @submit.prevent="submitRename(group)"
          >
            <input
              v-model="groupNameDraft"
              aria-label="分组名称"
              maxlength="20"
              data-testid="group-rename-input"
            />
            <button type="submit" data-testid="group-rename-submit" :disabled="mutating">
              保存
            </button>
          </form>

          <div
            v-if="deletingGroupId === group.groupId"
            class="watch-group-form"
            data-testid="group-delete-form"
          >
            <select
              v-if="group.itemCount > 0"
              v-model="moveTargetId"
              aria-label="自选项搬移到的目标分组"
              data-testid="group-delete-target"
            >
              <option value="">选择目标分组</option>
              <option
                v-for="target in otherGroups(group.groupId)"
                :key="target.groupId"
                :value="target.groupId"
              >
                {{ target.groupName }}
              </option>
            </select>
            <button
              type="button"
              data-testid="group-delete-submit"
              :disabled="mutating"
              @click="submitDelete(group)"
            >
              确认删除
            </button>
          </div>
        </div>

        <footer>拖动分组可调整顺序</footer>
      </aside>

      <div class="watch-content">
        <section class="watch-insight">
          <div>
            <span class="eyebrow">DAILY WATCH</span>
            <h2>{{ activeGroup?.groupName ?? '暂无分组' }} · 今日摘要</h2>
            <p data-testid="watch-summary">{{ summaryText }}</p>
          </div>
          <button type="button" disabled title="AI 解读待接入（M3-10）">
            <Sparkles :size="15" /> 分析本组
          </button>
        </section>

        <div class="watch-cards">
          <article
            v-for="(item, index) in visibleItems"
            :key="item.itemId"
            data-testid="watch-card"
            :data-item-id="item.itemId"
            draggable="true"
            @dragstart="onItemDragStart(index)"
            @dragover.prevent
            @drop="onItemDrop(index)"
          >
            <header>
              <RouterLink v-if="item.security" :to="`/stocks/${item.security.securityId}`">
                <strong>{{ item.security.securityName }}</strong>
                <span>{{ item.security.fullSymbol }}</span>
              </RouterLink>
              <span v-else data-testid="card-missing-security">
                <strong>证券主数据缺失</strong>
                <span>{{ item.itemId }}</span>
              </span>
              <button
                data-testid="item-actions-toggle"
                type="button"
                :data-item-id="item.itemId"
                aria-label="自选项操作"
                @click="toggleItemMenu(item.itemId)"
              >
                <MoreHorizontal :size="16" />
              </button>
            </header>

            <div class="watch-price">
              <strong>{{ item.quote?.latestPrice ?? '--' }}</strong>
              <span :class="trendClass(item.quote?.changeRate ?? null)">
                {{ formatChangeRate(item.quote?.changeRate ?? null) }}
              </span>
            </div>

            <dl>
              <div><dt>成交额</dt><dd>{{ formatMoney(item.quote?.tradeAmount ?? null) }}</dd></div>
              <div><dt>换手率</dt><dd>{{ formatChangeRate(item.quote?.turnoverRate ?? null) }}</dd></div>
            </dl>

            <div v-if="openItemMenu === item.itemId" class="watch-item-menu" data-testid="item-menu">
              <button
                v-for="target in otherGroups(item.groupId)"
                :key="target.groupId"
                data-testid="item-move-target"
                type="button"
                :data-group-id="target.groupId"
                :disabled="mutating"
                @click="moveItem(item, target.groupId)"
              >
                移动到「{{ target.groupName }}」
              </button>
              <small v-if="!otherGroups(item.groupId).length">还没有其他分组可以移动。</small>
            </div>

            <footer>
              <RouterLink
                v-if="item.security"
                class="watch-detail-link"
                :to="`/stocks/${item.security.securityId}`"
              >
                查看详情
              </RouterLink>
              <div
                class="watch-card-actions"
                data-testid="watch-card-actions"
                role="group"
                aria-label="股票快捷操作"
              >
                <button
                  class="watch-card-action watch-card-action--ai"
                  data-action="ai"
                  type="button"
                  disabled
                  title="AI 解读待接入（M3-10）"
                >
                  <Sparkles :size="15" />
                  <span>AI 解读</span>
                </button>
                <button
                  class="watch-card-action watch-card-action--remove"
                  data-action="remove"
                  type="button"
                  aria-label="移出自选"
                  title="移出自选"
                  :disabled="mutating"
                  @click="removeItem(item)"
                >
                  <Trash2 :size="15" />
                </button>
              </div>
            </footer>
          </article>
        </div>

        <p v-if="!visibleItems.length" class="component-unavailable" data-testid="watch-empty">
          {{ groups.length ? '这个分组还没有自选标的。' : '还没有任何自选分组。' }}
        </p>
      </div>
    </section>
  </div>

  <div v-else-if="loading" class="page-loading" aria-label="正在加载自选">
    <span /><span /><span />
  </div>
</template>
