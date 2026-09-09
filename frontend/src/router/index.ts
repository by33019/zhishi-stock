import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import type { AccessLevel } from '@/types/domain'

declare module 'vue-router' {
  interface RouteMeta {
    access: AccessLevel
    title: string
    shell?: boolean
  }
}

const MarketOverview = () => import('@/pages/MarketOverview.vue')
const RankingsPage = () => import('@/pages/RankingsPage.vue')
const SectorsPage = () => import('@/pages/SectorsPage.vue')
const SectorDetailPage = () => import('@/pages/SectorDetailPage.vue')
const StockDetailPage = () => import('@/pages/StockDetailPage.vue')
const NewsPage = () => import('@/pages/NewsPage.vue')
const WatchlistPage = () => import('@/pages/WatchlistPage.vue')
const AiWorkspacePage = () => import('@/pages/AiWorkspacePage.vue')
const HistoryPage = () => import('@/pages/HistoryPage.vue')
const LoginPage = () => import('@/pages/LoginPage.vue')
const AdminOverviewPage = () => import('@/pages/AdminOverviewPage.vue')

export const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/market', meta: { access: 'PUBLIC', title: '市场总览' } },
  { path: '/market', name: 'market', component: MarketOverview, meta: { access: 'PUBLIC', title: '市场总览', shell: true } },
  { path: '/rankings', name: 'rankings', component: RankingsPage, meta: { access: 'PUBLIC', title: '行情榜单', shell: true } },
  { path: '/sectors', name: 'sectors', component: SectorsPage, meta: { access: 'PUBLIC', title: '板块分析', shell: true } },
  { path: '/sectors/:id', name: 'sector-detail', component: SectorDetailPage, meta: { access: 'PUBLIC', title: '板块详情', shell: true } },
  { path: '/stocks/:id', name: 'stock-detail', component: StockDetailPage, meta: { access: 'PUBLIC', title: '个股详情', shell: true } },
  { path: '/news', name: 'news', component: NewsPage, meta: { access: 'PUBLIC', title: '资讯中心', shell: true } },
  { path: '/watchlist', name: 'watchlist', component: WatchlistPage, meta: { access: 'USER', title: '我的自选', shell: true } },
  { path: '/ai', name: 'ai-workspace', component: AiWorkspacePage, meta: { access: 'USER', title: 'AI 研究', shell: true } },
  { path: '/history', name: 'history', component: HistoryPage, meta: { access: 'USER', title: '分析历史', shell: true } },
  { path: '/login', name: 'login', component: LoginPage, meta: { access: 'PUBLIC', title: '登录', shell: false } },
  { path: '/admin', name: 'admin', component: AdminOverviewPage, meta: { access: 'ADMIN', title: '系统运营', shell: true } },
  { path: '/:pathMatch(.*)*', redirect: '/market', meta: { access: 'PUBLIC', title: '页面不存在' } },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.afterEach((to) => {
  document.title = `${to.meta.title} · 知势`
})
