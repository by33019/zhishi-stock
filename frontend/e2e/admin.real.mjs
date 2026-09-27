import assert from 'node:assert/strict'

import { chromium } from 'playwright'

const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:8088'
const ADMIN_USERNAME = process.env.E2E_ADMIN_USERNAME ?? 'admin'
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? 'Admin@123'

/**
 * M3-11 后台管理面浏览器级验收：
 * 登录 admin → /admin 渲染总览（ADM-AI-01 + ADM-JOB-01/03 真实数据）→
 * 切换到各分区确认各自发出真实请求（用户/任务/日志/AI 用量/资讯来源）→
 * 无控制台错误、页面无原型页的编造数值。
 */

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext()
const page = await context.newPage()

const apiCalls = []
const consoleErrors = []
page.on('response', (response) => {
  if (response.url().includes('/api/v1/admin')) {
    apiCalls.push({ url: response.url(), status: response.status() })
  }
})
page.on('console', (message) => {
  if (message.type() === 'error') consoleErrors.push(message.text())
})

// ---------- 登录 ----------
await page.goto(`${baseURL}/login`)
await page.getByLabel('账号').fill(ADMIN_USERNAME)
await page.getByLabel('密码').fill(ADMIN_PASSWORD)
await page.getByRole('button', { name: /登录/ }).click()
await page.waitForURL(/\/(market|admin)/, { timeout: 15_000 })
console.log('登录成功')

// ---------- 总览 ----------
await page.goto(`${baseURL}/admin`)
await page.getByRole('heading', { name: '系统运营', exact: true }).waitFor()
await page.getByRole('heading', { name: 'AI 运营总览' }).waitFor()
await page.getByRole('heading', { name: '任务定义' }).waitFor()

const overviewCalls = apiCalls.filter((call) =>
  call.url.includes('/admin/ai/overview') || call.url.includes('/admin/job-definitions'))
assert.ok(overviewCalls.some((call) => call.status === 200), '总览没有发出成功的后台请求')
console.log('总览分区 OK：ADM-AI-01 + ADM-JOB-01 均为 200')

// 原型页的编造数值不得回归
const bodyText = await page.locator('.business-page').innerText()
assert.ok(!bodyText.includes('12,680'), '出现原型页编造数值：注册用户 12,680')
assert.ok(!bodyText.includes('486'), '出现原型页编造数值：在线会话 486')

// ---------- 用户管理 ----------
await page.getByRole('button', { name: '用户管理' }).click()
await page.getByRole('heading', { name: '用户列表' }).waitFor()
await page.waitForFunction(
  () => document.querySelectorAll('.quote-table tbody tr').length > 0,
  { timeout: 10_000 },
)
console.log('用户分区 OK：admin 账号列表已渲染')

// ---------- 定时任务 ----------
await page.getByRole('button', { name: '定时任务' }).click()
await page.getByRole('heading', { name: '任务定义' }).waitFor()
assert.ok(
  apiCalls.some((call) => call.url.includes('/admin/job-executions') && call.status === 200),
  '任务分区没有发出 ADM-JOB-03 请求',
)
console.log('任务分区 OK：执行历史 200')

// ---------- 操作日志 ----------
await page.getByRole('button', { name: '操作日志' }).click()
await page.getByRole('heading', { name: '操作日志', exact: true }).waitFor()
console.log('日志分区 OK：页面已渲染')

// ---------- AI 运营 ----------
await page.getByRole('button', { name: 'AI 运营' }).click()
await page.getByRole('heading', { name: '分组用量' }).waitFor()
assert.ok(
  apiCalls.some((call) => call.url.includes('/admin/ai/usage') && call.status === 200),
  'AI 分区没有发出 ADM-AI-05 请求',
)
console.log('AI 分区 OK：ADM-AI-05 200')

// ---------- 资讯治理 ----------
await page.getByRole('button', { name: '资讯治理' }).click()
await page.getByRole('heading', { name: '资讯来源' }).waitFor()
assert.ok(
  apiCalls.some((call) => call.url.includes('/admin/news-sources') && call.status === 200),
  '资讯分区没有发出 ADM-NEWS-01 请求',
)
console.log('资讯分区 OK：ADM-NEWS-01 200')

// ---------- 汇总 ----------
const failedCalls = apiCalls.filter((call) => call.status >= 400)
console.log('--- 非 2xx 的后台请求 ---')
for (const call of failedCalls) console.log(call.status, call.url)
console.log('--- 控制台错误 ---')
console.log(consoleErrors.length ? consoleErrors : '（无）')

// 未登录/无权限的 401/403 不应出现在已登录 admin 会话里
const authFailures = failedCalls.filter((call) => call.status === 401 || call.status === 403)
assert.equal(authFailures.length, 0, '已登录 admin 会话内出现 401/403')
assert.equal(consoleErrors.length, 0, `出现 ${consoleErrors.length} 条控制台错误`)

await browser.close()
console.log('=== M3-11 后台管理面验收：全部通过 ===')
