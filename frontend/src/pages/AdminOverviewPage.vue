<script setup lang="ts">
import { ref } from 'vue'
import { Activity, Bot, FileClock, ListTree, ScrollText, Users } from '@lucide/vue'

import PageHeader from '@/components/PageHeader.vue'
import AdminOverviewSection from './admin/AdminOverviewSection.vue'
import AdminUsersSection from './admin/AdminUsersSection.vue'
import AdminJobsSection from './admin/AdminJobsSection.vue'
import AdminLogsSection from './admin/AdminLogsSection.vue'
import AdminAiSection from './admin/AdminAiSection.vue'
import AdminNewsSection from './admin/AdminNewsSection.vue'

/**
 * 后台管理面（M3-11）。设计决策：**单路由 + Tab 分区**，不再拆子路由——
 * 后台各分区共享同一个权限闸（`admin:access`）与同一个壳，拆路由只会多出
 * 六条重复的守卫配置；分区内的数据各自按需加载（切到哪个 Tab 才发哪个请求）。
 *
 * 原型页的"注册用户 12,680 / 在线会话 486"等数字没有任何后端来源，
 * 已整体移除——画出来就是编造（项目纪律：没有数据源的字段直接不渲染）。
 */
const tabs = [
  { key: 'overview', label: '总览', icon: ListTree },
  { key: 'users', label: '用户管理', icon: Users },
  { key: 'jobs', label: '定时任务', icon: Activity },
  { key: 'logs', label: '操作日志', icon: ScrollText },
  { key: 'ai', label: 'AI 运营', icon: Bot },
  { key: 'news', label: '资讯治理', icon: FileClock },
] as const

type TabKey = (typeof tabs)[number]['key']

const activeTab = ref<TabKey>('overview')
</script>

<template>
  <div class="business-page page-enter">
    <PageHeader
      eyebrow="SYSTEM OPERATIONS"
      title="系统运营"
      description="用户、任务、日志、AI 用量与资讯治理的后台管理面。所有数据来自实时接口，不存在演示数值。"
      data-time=""
    />

    <nav class="admin-tabs" aria-label="后台分区">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        type="button"
        class="admin-tab"
        :class="{ active: activeTab === tab.key }"
        @click="activeTab = tab.key"
      >
        <component :is="tab.icon" :size="15" />
        {{ tab.label }}
      </button>
    </nav>

    <AdminOverviewSection v-if="activeTab === 'overview'" />
    <AdminUsersSection v-else-if="activeTab === 'users'" />
    <AdminJobsSection v-else-if="activeTab === 'jobs'" />
    <AdminLogsSection v-else-if="activeTab === 'logs'" />
    <AdminAiSection v-else-if="activeTab === 'ai'" />
    <AdminNewsSection v-else />
  </div>
</template>

<style scoped>
.admin-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 18px 0 22px;
}

.admin-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--paper);
  color: var(--ink-soft);
  font-size: var(--text-label);
  font-weight: 700;
  cursor: pointer;
}

.admin-tab:hover { border-color: var(--ink); color: var(--ink); }

.admin-tab.active {
  background: var(--ink);
  border-color: var(--ink);
  color: var(--white);
}
</style>
