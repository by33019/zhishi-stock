<script setup lang="ts">
import { ArrowLeft, Sparkles } from '@lucide/vue'
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'

import { useRemoteData } from '@/composables/useRemoteData'
import { getSectorConstituents, getSectorDetail } from '@/services/sectorApi'
import { formatChangeRate, formatDateTime, formatMoney, trendClass } from '@/utils/format'

const route = useRoute()
const sectorId = computed(() => String(route.params.id))

const {
  data: detail,
  loading: loadingDetail,
  error: detailError,
  reload: reloadDetail,
} = useRemoteData(() => getSectorDetail(sectorId.value))

/**
 * `size=100` 是契约上限，一次取回全部能取的成分股。
 *
 * 板块最多约 257 只（20 个二级行业之一），100 只覆盖不到一半——
 * 因此下面的涨跌家数**必须写明分母**，否则"38 家上涨"会被读成整个板块。
 * 翻三次页只为算一个家数不值得（见设计文档 §6.5）。
 */
const {
  data: constituents,
  loading: loadingConstituents,
  error: constituentsError,
  reload: reloadConstituents,
} = useRemoteData(() => getSectorConstituents(sectorId.value, { size: 100 }))

onMounted(() => {
  reloadDetail()
  reloadConstituents()
})

const items = computed(() => constituents.value?.items ?? [])

/**
 * 涨跌家数由**真实成分股**的涨跌幅重新计数。
 *
 * 这是对已取回数据的再统计，不是新造数据。停牌成分股的 `changeRate` 为 `null`，
 * 既不算涨也不算跌——补成 0 会让停牌股看起来是平盘，那是最容易误导的一类错。
 */
const breadth = computed(() => {
  let rising = 0
  let falling = 0
  let suspended = 0
  for (const item of items.value) {
    const rate = item.quote.changeRate
    if (rate === null || rate === '') {
      suspended += 1
      continue
    }
    const value = Number(rate)
    if (value > 0) rising += 1
    else if (value < 0) falling += 1
  }
  return { rising, falling, suspended, total: items.value.length }
})

const quote = computed(() => detail.value?.quote)
const subtitle = computed(() => {
  const current = detail.value
  if (!current) return ''
  const parts = [`${current.quote.companyCount} 家成分股`]
  if (current.parent) parts.push(current.parent.sectorName)
  parts.push(`数据截止 ${formatDateTime(current.quote.dataTime)}`)
  return parts.join(' · ')
})
</script>

<template>
  <section v-if="detailError" class="market-state-panel" role="alert">
    <h1>板块数据暂时无法加载</h1>
    <p>{{ detailError.message }}</p>
    <small v-if="detailError.traceId">追踪编号：{{ detailError.traceId }}</small>
    <button data-testid="sector-detail-retry" type="button" @click="reloadDetail">重新加载</button>
  </section>

  <div v-else-if="detail" class="business-page page-enter">
    <RouterLink class="back-link" to="/sectors"><ArrowLeft :size="14" /> 返回板块</RouterLink>

    <section class="detail-hero sector-detail-hero">
      <div>
        <span class="eyebrow">SECTOR · {{ detail.sector.sectorCode }}</span>
        <h1>{{ detail.sector.sectorName }}</h1>
        <p>{{ subtitle }}</p>
      </div>
      <div class="detail-price">
        <strong :class="trendClass(quote?.changeRate ?? null)" class="mono">
          {{ formatChangeRate(quote?.changeRate ?? null) }}
        </strong>
        <span>成分股等权涨跌幅</span>
      </div>
      <button class="primary-button" type="button" disabled title="AI 板块解读待接入（M3-06）">
        <Sparkles :size="16" /> AI 板块解读
      </button>
    </section>

    <section class="detail-layout">
      <article class="chart-card">
        <header>
          <div><span class="eyebrow">INTRADAY</span><h2>板块分时走势</h2></div>
        </header>
        <p class="component-unavailable" data-testid="sector-trend-unavailable">
          板块走势（SEC-05）后端尚未实现，暂不展示。此处不再绘制示意曲线——
          一条与真实行情无关的曲线会被当成真实走势。
        </p>
      </article>

      <aside class="evidence-note">
        <span class="eyebrow">STRUCTURE</span>
        <h2>强度拆解</h2>
        <dl>
          <div>
            <dt>上涨 / 下跌</dt>
            <dd>
              <b class="trend-up">{{ breadth.rising }}</b> /
              <b class="trend-down">{{ breadth.falling }}</b>
            </dd>
          </div>
          <div><dt>停牌</dt><dd>{{ breadth.suspended }} 只</dd></div>
          <div><dt>板块成交额</dt><dd>{{ formatMoney(quote?.tradeAmount ?? null) }}</dd></div>
          <div><dt>领涨股</dt><dd>{{ quote?.leadingStock?.security.securityName ?? '--' }}</dd></div>
        </dl>
        <p v-if="breadth.total">
          以上家数基于已取回的 {{ breadth.total }} 只成分股统计，并非全部
          {{ quote?.companyCount ?? 0 }} 只。
        </p>
      </aside>
    </section>

    <section class="data-workbench component-table">
      <header class="section-heading">
        <div>
          <span class="section-index">01</span>
          <div><h2>成分股表现</h2><p>按涨跌幅降序，序号为板块内贡献度排名</p></div>
        </div>
        <span class="page-header__time">
          已展示 {{ items.length }} / {{ quote?.companyCount ?? 0 }} 只
        </span>
      </header>

      <p v-if="constituentsError" class="component-unavailable" role="alert">
        成分股加载失败：{{ constituentsError.message }}
        <button data-testid="constituents-retry" type="button" @click="reloadConstituents">
          重新加载
        </button>
      </p>

      <div v-else class="quote-table-wrap">
        <table class="quote-table ranking-table">
          <thead>
            <tr>
              <th>排名</th><th>股票</th><th>最新价</th><th>涨跌幅</th>
              <th>成交额</th><th>换手率</th><th>关系</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in items" :key="item.quote.security.securityId">
              <td class="rank-cell">{{ item.contributionRank }}</td>
              <td>
                <RouterLink :to="`/stocks/${item.quote.security.securityId}`">
                  <strong>{{ item.quote.security.securityName }}</strong>
                  <small>{{ item.quote.security.fullSymbol }}</small>
                </RouterLink>
              </td>
              <td class="mono">{{ item.quote.latestPrice ?? '--' }}</td>
              <td :class="trendClass(item.quote.changeRate)" class="mono strong">
                {{ formatChangeRate(item.quote.changeRate) }}
              </td>
              <td class="mono">{{ formatMoney(item.quote.tradeAmount) }}</td>
              <td class="mono">{{ formatChangeRate(item.quote.turnoverRate) }}</td>
              <td>{{ item.isPrimary ? '主营' : '关联' }}</td>
            </tr>
          </tbody>
        </table>
        <p v-if="!items.length && !loadingConstituents" class="component-unavailable">
          该板块暂无成分股。
        </p>
      </div>
    </section>
  </div>

  <div v-else-if="loadingDetail" class="page-loading" aria-label="正在加载板块详情">
    <span /><span /><span />
  </div>
</template>
