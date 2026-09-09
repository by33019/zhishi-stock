<script setup lang="ts">
import {
  Bell,
  CircleUserRound,
  History,
  LayoutDashboard,
  Layers3,
  ListFilter,
  Menu,
  Newspaper,
  PanelRightOpen,
  Settings,
  Sparkles,
  Star,
} from '@lucide/vue'
import { storeToRefs } from 'pinia'
import { RouterLink, RouterView } from 'vue-router'

import AiResearchPanel from '@/components/AiResearchPanel.vue'
import BrandMark from '@/components/BrandMark.vue'
import GlobalSearch from '@/components/GlobalSearch.vue'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
const { aiPanelOpen, mobileNavOpen } = storeToRefs(ui)

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
        <div class="data-source">
          <span class="live-dot" />
          <span><strong>数据链路正常</strong><small>延迟 26 秒</small></span>
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
          <span class="market-state"><i />交易中 <b>14:32</b></span>
          <button class="icon-button" type="button" aria-label="消息通知"><Bell :size="18" /></button>
          <button class="ai-toggle" data-testid="ai-panel-toggle" type="button" @click="ui.toggleAiPanel">
            <PanelRightOpen :size="17" />
            <span>AI 助手</span>
          </button>
          <RouterLink class="user-entry" to="/login"><CircleUserRound :size="21" /><span>研究员</span></RouterLink>
        </div>
      </header>

      <main class="page-stage">
        <RouterView />
      </main>
    </section>

    <AiResearchPanel v-if="aiPanelOpen" @close="ui.closeAiPanel" />
  </div>
</template>
