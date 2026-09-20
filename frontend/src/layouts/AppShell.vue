<script setup lang="ts">
import {
  Bell,
  CircleUserRound,
  History,
  LayoutDashboard,
  Layers3,
  ListFilter,
  LogOut,
  Menu,
  Newspaper,
  PanelRightOpen,
  Settings,
  Sparkles,
  Star,
} from '@lucide/vue'
import { storeToRefs } from 'pinia'
import { computed } from 'vue'
import { RouterLink, RouterView } from 'vue-router'
import { useRouter } from 'vue-router'

import AiResearchPanel from '@/components/AiResearchPanel.vue'
import BrandMark from '@/components/BrandMark.vue'
import GlobalSearch from '@/components/GlobalSearch.vue'
import { useMarketStatus } from '@/composables/useMarketStatus'
import { useUiStore } from '@/stores/ui'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime, formatTime } from '@/utils/format'

const ui = useUiStore()
const { aiPanelOpen, mobileNavOpen } = storeToRefs(ui)
const auth = useAuthStore()
const { authenticated, user } = storeToRefs(auth)
const router = useRouter()

// 顶栏与侧栏此前是写死的"交易中 14:32"与"数据链路正常 / 延迟 26 秒"，
// 而这两个值出现在**每一个页面**上。改为消费 MKT-02——它早已实现且能正确判定非交易日。
const {
  status: marketStatus,
  label: marketLabel,
  isLive: marketIsLive,
  now: marketNow,
  nextSessionLabel,
  isFirstLoad: marketIsFirstLoad,
  error: marketError,
  reload: reloadMarketStatus,
} = useMarketStatus()

/** 拿不到状态就明说"状态未知"，不猜也不沿用上一次的文案。 */
const marketStateLabel = computed(() => {
  if (marketError.value) return '状态未知'
  if (marketLabel.value) return marketLabel.value
  return marketIsFirstLoad.value ? '加载中' : '状态未知'
})

/**
 * 完整信息放进 `title`。
 *
 * "下一时段何时开始"只在收盘后有用，而顶栏横向空间有限——挤压布局
 * 去显示一句多数时候用不到的话不划算。
 */
const marketStateTitle = computed(() => {
  if (marketError.value) {
    const trace = marketError.value.traceId ? `（追踪号 ${marketError.value.traceId}）` : ''
    return `${marketError.value.message}${trace}，点击重试`
  }
  if (!marketStatus.value) return '正在读取市场状态'
  const parts = [
    `${marketStatus.value.tradeDate} · ${marketStatus.value.isTradingDay ? '交易日' : '非交易日'}`,
  ]
  if (nextSessionLabel.value) parts.push(`下一时段 ${nextSessionLabel.value}`)
  return parts.join(' · ')
})

/** 侧栏：显示交易日历数据源时间。原型那行"延迟 26 秒"没有任何数据来源。 */
const calendarLabel = computed(() => (marketStatus.value
  ? `更新于 ${formatDateTime(marketStatus.value.calendarSourceTime)}`
  : '--'))

const calendarTitle = computed(() => (marketStatus.value
  ? `交易日历数据源时间 ${formatDateTime(marketStatus.value.calendarSourceTime)}`
  : '交易日历数据源时间未知'))

async function logout() {
  await auth.logout()
  await router.push('/market')
}

const navigation = [
  { label: '市场总览', to: '/market', icon: LayoutDashboard },
  { label: '行情榜单', to: '/rankings', icon: ListFilter },
  { label: '板块分析', to: '/sectors', icon: Layers3 },
  { label: '资讯中心', to: '/news', icon: Newspaper },
  { label: '我的自选', to: '/watchlist', icon: Star },
  { label: 'AI 研究', to: '/ai', icon: Sparkles },
  { label: '分析历史', to: '/history', icon: History },
]
</script>

<template>
  <div class="app-shell" :class="{ 'has-ai-panel': aiPanelOpen }">
    <aside class="sidebar" :class="{ 'is-open': mobileNavOpen }">
      <BrandMark />
      <nav aria-label="主导航">
        <span class="nav-kicker">研究工作台</span>
        <RouterLink
          v-for="item in navigation"
          :key="item.to"
          :to="item.to"
          @click="ui.closeMobileNav"
        >
          <component :is="item.icon" :size="18" />
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>
      <div class="sidebar__foot">
        <RouterLink to="/admin"><Settings :size="17" /> 系统运营</RouterLink>
        <div
          class="data-source"
          data-testid="calendar-source"
          :title="calendarTitle"
        >
          <span class="live-dot" :class="{ 'is-idle': !marketIsLive }" />
          <span>
            <strong>{{ marketError ? '状态未知' : '交易日历' }}</strong>
            <small>{{ calendarLabel }}</small>
          </span>
        </div>
      </div>
    </aside>

    <div v-if="mobileNavOpen" class="nav-scrim" @click="ui.closeMobileNav" />

    <section class="workspace">
      <header class="topbar">
        <button class="mobile-menu" type="button" aria-label="打开导航" @click="ui.toggleMobileNav">
          <Menu :size="20" />
        </button>
        <GlobalSearch />
        <div class="topbar__actions">
          <button
            class="market-state"
            :class="{ 'is-idle': !marketIsLive }"
            data-testid="market-state"
            type="button"
            :title="marketStateTitle"
            @click="reloadMarketStatus()"
          >
            <i />{{ marketStateLabel }}<b v-if="marketIsLive">{{ formatTime(marketNow) }}</b>
          </button>
          <button class="icon-button" type="button" aria-label="消息通知"><Bell :size="18" /></button>
          <button class="ai-toggle" data-testid="ai-panel-toggle" type="button" @click="ui.toggleAiPanel">
            <PanelRightOpen :size="17" />
            <span>AI 助手</span>
          </button>
          <div v-if="authenticated" class="user-session">
            <span class="user-entry"><CircleUserRound :size="21" /><span>{{ user?.displayName }}</span></span>
            <button data-testid="logout-button" type="button" aria-label="退出登录" @click="logout">
              <LogOut :size="16" />
            </button>
          </div>
          <RouterLink v-else class="user-entry" to="/login"><CircleUserRound :size="21" /><span>登录</span></RouterLink>
        </div>
      </header>

      <main class="page-stage">
        <RouterView />
      </main>
    </section>

    <AiResearchPanel v-if="aiPanelOpen" @close="ui.closeAiPanel" />
  </div>
</template>
