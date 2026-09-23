<script setup lang="ts">
import { Download, Sparkles } from '@lucide/vue'
import { computed, onMounted, ref, watch } from 'vue'

import PageHeader from '@/components/PageHeader.vue'
import { useRankingExport } from '@/composables/useRankingExport'
import { useRemoteData } from '@/composables/useRemoteData'
import { getStockRankings } from '@/services/rankingApi'
import type { RankingType } from '@/types/domain'
import { formatChangeRate, formatDateTime, formatMoney, formatVolume, trendClass } from '@/utils/format'

/**
 * 榜单口径只有契约 QTE-01 白名单里的三种。
 *
 * 原型里还有一档"换手率榜"——契约没有这个口径，客户端按 `turnoverRate` 排序就是自造榜单，
 * 且只能对**当前页**排序（服务端已按 size 分页），与服务端榜单在数据范围上不一致。
 */
const RANKING_TYPES: { value: RankingType; label: string }[] = [
  { value: 'GAINERS', label: '涨幅榜' },
  { value: 'LOSERS', label: '跌幅榜' },
  { value: 'TURNOVER', label: '成交额榜' },
]

/** 交易所筛选。契约支持逗号分隔多值，MVP 先做单选。 */
const EXCHANGES: { value: string; label: string }[] = [
  { value: '', label: '沪深京' },
  { value: 'SH', label: '沪市' },
  { value: 'SZ', label: '深市' },
  { value: 'BJ', label: '北交所' },
]

const PAGE_SIZE = 20

const rankingType = ref<RankingType>('GAINERS')
const exchange = ref('')
const page = ref(1)

const {
  data: snapshot,
  loading: loadingRanking,
  error: rankingError,
  reload: reloadRanking,
} = useRemoteData(() => getStockRankings({
  rankingType: rankingType.value,
  exchangeCodes: exchange.value || undefined,
  page: page.value,
  size: PAGE_SIZE,
}))

/** "成交额最高"卡片：另取一次成交额榜的第一名，而不是从当前页里挑。 */
const { data: turnoverTop, reload: loadTurnoverTop } =
  useRemoteData(() => getStockRankings({ rankingType: 'TURNOVER', size: 1 }))

/**
 * 用一个查询键驱动重新请求，而不是给每个筛选条件各挂一个 `watch`。
 *
 * 多个 `watch` 会在"改口径同时把页码归 1"时触发两次请求，其中一次的结果必然被覆盖——
 * 用户看到的是闪烁，日志里是两份重复查询。
 */
const queryKey = computed(() => `${rankingType.value}|${exchange.value}|${page.value}`)
watch(queryKey, () => reloadRanking())

onMounted(() => {
  reloadRanking()
  loadTurnoverTop()
})

function selectType(next: RankingType) {
  rankingType.value = next
  page.value = 1
}

function selectExchange(next: string) {
  exchange.value = next
  page.value = 1
}

const leader = computed(() => snapshot.value?.items[0])
const turnoverLeader = computed(() => turnoverTop.value?.items[0])
const isDelayed = computed(() =>
  snapshot.value !== undefined && snapshot.value.dataStatus !== 'REALTIME')

/** 行号是**全榜单**的名次，不是当前页内的序号。 */
function rankOf(index: number): string {
  const current = snapshot.value
  if (!current) return '--'
  return String((current.page - 1) * current.size + index + 1).padStart(2, '0')
}

// ---------------------------------------------------------------------------
// Excel 导出（契约 §9.2 EXP-01~EXP-03）
// ---------------------------------------------------------------------------

const {
  phase: exportPhase,
  progress: exportProgress,
  failure: exportFailure,
  busy: exportBusy,
  run: runExport,
} = useRankingExport()

/**
 * 导出按钮的文案。
 *
 * 四个阶段各给一句话，而不是笼统的"处理中"：用户对"排队中"和"正在下载"的耐心完全不同，
 * 而一个不变的转圈图标在两种情况下都像是在卡住。
 */
const exportLabel = computed(() => {
  switch (exportPhase.value) {
    case 'QUEUED':
      return '排队中…'
    case 'RUNNING':
      return `生成中 ${exportProgress.value}%`
    case 'DOWNLOADING':
      return '正在下载…'
    default:
      return '导出 Excel'
  }
})

/**
 * 发起导出。
 *
 * **只带筛选条件，不带页码和每页条数**：导出取的是全市场榜单（契约 §9.2 上限 5,000 行），
 * 把当前页的 `page` / `size` 一起传过去，用户会以为导出的是整份榜单，
 * 而文件里只有当前页那 20 行——这种错在文件打开前看不出来。
 *
 * `columns` 同理不传：由服务端套用默认列集。
 */
function exportRanking() {
  runExport({
    rankingType: rankingType.value,
    exchangeCodes: exchange.value || undefined,
  })
}
</script>

<template>
  <section v-if="rankingError" class="market-state-panel" role="alert">
    <h1>榜单暂时无法加载</h1>
    <p>{{ rankingError.message }}</p>
    <small v-if="rankingError.traceId">追踪编号：{{ rankingError.traceId }}</small>
    <button data-testid="ranking-retry" type="button" @click="reloadRanking">重新加载</button>
  </section>

  <div v-else-if="snapshot" class="business-page page-enter">
    <PageHeader
      eyebrow="MARKET RANKING"
      title="行情榜单"
      description="用涨跌与成交额交叉识别活跃标的；榜单由服务端按全市场排序后分页返回。"
      :data-time="snapshot.dataTime ? formatDateTime(snapshot.dataTime) : ''"
    >
      <button class="secondary-button" type="button" :disabled="exportBusy" @click="exportRanking">
        <Download :size="15" /> {{ exportLabel }}
      </button>
    </PageHeader>

    <p v-if="exportFailure" class="component-unavailable" role="alert" data-testid="ranking-export-error">
      导出失败：{{ exportFailure.message }}
      <small v-if="exportFailure.traceId">追踪编号：{{ exportFailure.traceId }}</small>
    </p>

    <section class="ranking-summary">
      <div>
        <span>领涨标的</span>
        <strong>{{ leader?.security.securityName ?? '暂无数据' }}</strong>
        <b :class="trendClass(leader?.changeRate ?? null)" class="mono">
          {{ formatChangeRate(leader?.changeRate ?? null) }}
        </b>
      </div>
      <div>
        <span>成交额最高</span>
        <strong>{{ turnoverLeader?.security.securityName ?? '暂无数据' }}</strong>
        <b class="mono">{{ formatMoney(turnoverLeader?.tradeAmount ?? null) }}</b>
      </div>
      <div>
        <span>数据状态</span>
        <strong>{{ snapshot.dataStatus === 'REALTIME' ? '实时行情' : '非实时' }}</strong>
        <b class="mono">{{ formatDateTime(snapshot.dataTime) }}</b>
      </div>
      <p>
        <Sparkles :size="16" />
        <span>
          <strong>口径说明</strong>
          榜单按服务端全市场排序返回，共 {{ snapshot.total }} 个标的；快照版本
          {{ snapshot.snapshotVersion || '--' }}。
        </span>
      </p>
    </section>

    <section class="data-workbench">
      <header class="workbench-toolbar">
        <div class="segmented-tabs">
          <button
            v-for="item in RANKING_TYPES"
            :key="item.value"
            :class="{ active: rankingType === item.value }"
            type="button"
            @click="selectType(item.value)"
          >
            {{ item.label }}
          </button>
        </div>
        <div class="toolbar-actions">
          <div class="segmented-tabs" aria-label="交易所筛选">
            <button
              v-for="item in EXCHANGES"
              :key="item.value || 'ALL'"
              :class="{ active: exchange === item.value }"
              type="button"
              @click="selectExchange(item.value)"
            >
              {{ item.label }}
            </button>
          </div>
        </div>
      </header>

      <p v-if="isDelayed" class="component-unavailable" data-testid="ranking-data-status">
        当前展示最近有效快照（数据截止 {{ formatDateTime(snapshot.dataTime) }}）。
      </p>

      <div class="quote-table-wrap">
        <table class="quote-table ranking-table">
          <thead>
            <tr>
              <th>排名</th><th>股票</th><th>最新价</th><th>涨跌额</th><th>涨跌幅</th>
              <th>成交量</th><th>成交额</th><th>换手率</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(stock, index) in snapshot.items" :key="stock.security.securityId">
              <td class="rank-cell">{{ rankOf(index) }}</td>
              <td>
                <RouterLink :to="`/stocks/${stock.security.securityId}`">
                  <strong>{{ stock.security.securityName }}</strong>
                  <small>{{ stock.security.fullSymbol }}</small>
                </RouterLink>
              </td>
              <td class="mono">{{ stock.latestPrice ?? '--' }}</td>
              <td :class="trendClass(stock.changeAmount)" class="mono">
                {{ stock.changeAmount ?? '--' }}
              </td>
              <td :class="trendClass(stock.changeRate)" class="mono strong">
                {{ formatChangeRate(stock.changeRate) }}
              </td>
              <td class="mono">{{ formatVolume(stock.tradeVolume) }}</td>
              <td class="mono">{{ formatMoney(stock.tradeAmount) }}</td>
              <td class="mono">{{ formatChangeRate(stock.turnoverRate) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-if="!snapshot.items.length" class="component-unavailable">
          当前条件下没有符合条件的标的。
        </p>
      </div>

      <footer class="table-footer">
        <span>
          共 {{ snapshot.total }} 个交易标的 · 第 {{ snapshot.page }} / {{ snapshot.totalPages }} 页
        </span>
        <div>
          <button type="button" :disabled="snapshot.page <= 1" @click="page = snapshot.page - 1">
            上一页
          </button>
          <button class="active" type="button">{{ snapshot.page }}</button>
          <button type="button" :disabled="!snapshot.hasNext" @click="page = snapshot.page + 1">
            下一页
          </button>
        </div>
      </footer>
    </section>
  </div>

  <div v-else-if="loadingRanking" class="page-loading" aria-label="正在加载榜单">
    <span /><span /><span />
  </div>
</template>
