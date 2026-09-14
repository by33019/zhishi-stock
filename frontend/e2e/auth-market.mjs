// 快速 UI 流程测试：使用受控 API fixture，不替代真实 Compose 纵向验收。
import assert from 'node:assert/strict'

import { chromium } from 'playwright'
import { createServer } from 'vite'

const baseURL = 'http://127.0.0.1:4173'
const viteServer = await createServer({
  configLoader: 'runner',
  server: { host: '127.0.0.1', port: 4173, strictPort: true },
})
await viteServer.listen()

let browser
try {
  browser = await chromium.launch({ headless: true })
  const context = await browser.newContext()
  const page = await context.newPage()
  let refreshSessionActive = false
  let refreshObservedCookie = false

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/markets/overview') {
      return fulfill(route, marketOverview())
    }
    if (path === '/api/v1/auth/login') {
      refreshSessionActive = true
      return fulfill(route, tokenResponse(true), 200, {
        'set-cookie': 'refresh_token=e2e-refresh; HttpOnly; Path=/api/v1/auth; SameSite=Strict',
      })
    }
    if (path === '/api/v1/auth/token/refresh') {
      refreshObservedCookie ||= request.headers().cookie?.includes('refresh_token=e2e-refresh') ?? false
      return refreshSessionActive
        ? fulfill(route, tokenResponse(false))
        : fulfillError(route, 401, 'INVALID_REFRESH_TOKEN', '刷新令牌无效')
    }
    if (path === '/api/v1/users/me') {
      return fulfill(route, { userId: '9900000000003', username: 'demo', displayName: '开发测试用户', status: 'ACTIVE' })
    }
    if (path === '/api/v1/auth/logout') {
      refreshSessionActive = false
      return fulfill(route, { loggedOut: true, revokedSessionCount: 1 }, 200, {
        'set-cookie': 'refresh_token=; HttpOnly; Max-Age=0; Path=/api/v1/auth; SameSite=Strict',
      })
    }
    return fulfillError(route, 404, 'NOT_FOUND', '接口不存在')
  })

  await page.goto(`${baseURL}/market`)
  await page.getByText('上证指数').waitFor()
  assert.match(await page.locator('body').innerText(), /市场广度/)

  await page.goto(`${baseURL}/watchlist`)
  await page.waitForURL(/\/login\?redirect=/)
  assert.equal(await page.getByRole('heading', { name: '登录研究工作台' }).isVisible(), true)

  await page.getByLabel('手机号或用户名').fill('demo')
  await page.getByLabel('登录密码').fill('Stock@123')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.waitForURL((url) => url.pathname === '/watchlist')
  assert.match(await page.locator('body').innerText(), /我的自选/)

  await page.reload()
  await page.getByText('开发测试用户').waitFor()
  assert.equal(refreshObservedCookie, true)

  await page.getByTestId('logout-button').click()
  await page.waitForURL('**/market')
  await page.goto(`${baseURL}/watchlist`)
  await page.waitForURL(/\/login\?redirect=/)
  assert.equal(refreshSessionActive, false)

  console.log('Playwright UI 流程通过：游客市场、受限路由、登录回跳、刷新恢复、退出撤销')
} finally {
  await browser?.close()
  await viteServer.close()
}

function envelope(data) {
  return {
    success: true,
    code: 'SUCCESS',
    message: '成功',
    data,
    traceId: 'e2e-trace',
    timestamp: '2026-09-13T14:32:00+08:00',
  }
}

function tokenResponse(withUser) {
  return {
    accessToken: 'e2e-access-token',
    accessExpiresInSeconds: 900,
    refreshExpiresInSeconds: 604800,
    user: withUser ? { userId: '9900000000003', username: 'demo', displayName: '开发测试用户' } : null,
    permissions: ['user:self:read'],
  }
}

async function fulfill(route, data, status = 200, headers = {}) {
  await route.fulfill({
    status,
    contentType: 'application/json',
    headers,
    body: JSON.stringify(envelope(data)),
  })
}

async function fulfillError(route, status, code, message) {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify({ success: false, code, message, traceId: 'e2e-error' }),
  })
}

function marketOverview() {
  return {
    marketCode: 'CN', marketStatus: 'TRADING', tradeDate: '2026-09-13',
    dataTime: '2026-09-13T14:32:00+08:00', dataStatus: 'REALTIME',
    indices: [{ indexId: '1', indexCode: '000001', indexName: '上证指数', latestPoint: '3200', changeAmount: '10', changeRate: '0.005', region: 'DOMESTIC', sparkline: [3190, 3200] }],
    breadth: { riseCount: 2, fallCount: 1, flatCount: 0, limitUpCount: 1, limitDownCount: 0 },
    turnover: { amount: '100000000', previousAmount: '90000000', points: [1, 2] },
    sectors: [{ sectorId: '1', sectorCode: 'BK-AI', sectorName: '人工智能', changeRate: '0.02', tradeAmount: '100000000', leadingStock: '示例股份', companyCount: 20 }],
    rankings: [{ securityId: '1', securityCode: '600000', securityName: '示例股份', exchangeCode: 'SH', latestPrice: '10', changeAmount: '0.1', changeRate: '0.01', tradeVolume: '1000', tradeAmount: '100000000', turnoverRate: '0.02', sparkline: [9.9, 10] }],
    news: [{ newsId: '1', newsType: 'NEWS', title: '市场快讯', summary: '摘要', sourceName: '模拟资讯', publishedAt: '2026-09-13T14:20:00+08:00', relatedSymbols: [] }],
    componentStatus: { indices: 'REALTIME', breadth: 'REALTIME', turnover: 'REALTIME', sectors: 'REALTIME', rankings: 'REALTIME', news: 'REALTIME' },
    lastSuccessfulSyncAt: '2026-09-13T14:32:00+08:00', snapshotVersion: 'e2e-v1',
  }
}
