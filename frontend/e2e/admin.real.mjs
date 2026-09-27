import assert from 'node:assert/strict'

import { chromium } from 'playwright'

const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:8088'
const ADMIN_USERNAME = process.env.E2E_ADMIN_USERNAME ?? 'admin'
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? 'Admin@123'

/**
 * M3-11 后台管理面浏览器级验收：
 * 登录 admin → /admin 各分区各自发出真实请求并渲染（总览/用户/任务/日志/AI 运营/资讯治理）→
 * 无 401/403、无控制台错误、页面无原型页的编造数值。
 *
 * 时机约定：分区标题是静态渲染的，**数据是异步的**——因此每一步都用
 * `waitForResponse` 等接口返回，而不是等标题出现（后者永远先于数据）。
 */

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext()
const page = await context.newPage()

const apiCalls = []
const consoleErrors = []
let authenticated = false
page.on('response', (response) => {
  if (response.url().includes('/api/v1/')) {
    apiCalls.push({ url: response.url(), status: response.status() })
  }
})
// 只收集**登录之后**的控制台错误：登录页上 auth.restore 的刷新 401 是预期游客态
// （浏览器会把任何 4xx 响应记为一条控制台错误），与 news.real.mjs 的处置一致。
page.on('console', (message) => {
  if (message.type() === 'error' && authenticated) consoleErrors.push(message.text())
})

/** 点击 Tab 并等待对应接口返回；返回响应状态码。 */
async function openSection(tabName, urlPattern) {
  const responsePromise = page.waitForResponse(
    (response) => urlPattern.test(response.url()) && response.request().method() === 'GET',
    { timeout: 15_000 },
  )
  await page.getByRole('button', { name: tabName }).click()
  const response = await responsePromise
  return response.status()
}

// ---------- 登录 ----------
await page.goto(`${baseURL}/login`)
await page.getByLabel('手机号或用户名').fill(ADMIN_USERNAME)
await page.getByLabel('登录密码').fill(ADMIN_PASSWORD)
const loginResponse = page.waitForResponse((response) => response.url().includes('/auth/login'))
await page.getByRole('button', { name: /登录/ }).click()
assert.equal((await loginResponse).status(), 200, '登录接口失败')
await page.waitForURL(/\/(market|admin)/, { timeout: 15_000 })
console.log('登录成功')
authenticated = true

// ---------- /admin 总览（默认分区，随页面加载自动请求）----------
await page.goto(`${baseURL}/admin`)
await page.waitForURL(/\/admin/)
const overviewResponse = await page.waitForResponse(
  (response) => /\/api\/v1\/admin\/ai\/overview/.test(response.url()),
  { timeout: 15_000 },
)
assert.equal(overviewResponse.status(), 200, 'ADM-AI-01 总览不是 200')
assert.ok(apiCalls.some((call) => call.url.includes('/admin/job-definitions') && call.status === 200),
  'ADM-JOB-01 任务定义不是 200')
// 数据真实渲染（数字来自 ADM-AI-01，静态标题之外必须有内容）
await page.getByText('任务总量').waitFor({ timeout: 10_000 })
console.log('总览分区 OK：ADM-AI-01 + ADM-JOB-01 均 200 且已渲染')

// 原型页的编造数值不得回归
const bodyText = await page.locator('.business-page').innerText()
assert.ok(!bodyText.includes('12,680'), '出现原型页编造数值：注册用户 12,680')
assert.ok(!bodyText.includes('486'), '出现原型页编造数值：在线会话 486')

// ---------- 用户管理 ----------
const usersStatus = await openSection('用户管理', /\/api\/v1\/admin\/users\?/)
assert.equal(usersStatus, 200, 'ADM-USR-01 不是 200')
await page.getByRole('heading', { name: '用户列表' }).waitFor()
console.log('用户分区 OK：ADM-USR-01 200')

// ---------- 定时任务 ----------
const executionsStatus = await openSection('定时任务', /\/api\/v1\/admin\/job-executions/)
assert.equal(executionsStatus, 200, 'ADM-JOB-03 不是 200')
console.log('任务分区 OK：ADM-JOB-03 200')

// ---------- 操作日志 ----------
const logsStatus = await openSection('操作日志', /\/api\/v1\/admin\/operation-logs/)
assert.equal(logsStatus, 200, 'LOG-01 不是 200')
console.log('日志分区 OK：LOG-01 200')

// ---------- AI 运营 ----------
const usageStatus = await openSection('AI 运营', /\/api\/v1\/admin\/ai\/usage/)
assert.equal(usageStatus, 200, 'ADM-AI-05 不是 200')
console.log('AI 分区 OK：ADM-AI-05 200')

// ---------- 资讯治理 ----------
const newsStatus = await openSection('资讯治理', /\/api\/v1\/admin\/news-sources/)
assert.equal(newsStatus, 200, 'ADM-NEWS-01 不是 200')
console.log('资讯分区 OK：ADM-NEWS-01 200')

// ---------- 写路径 1：新建来源（ADM-NEWS-03，201）----------
// 表单用连续三个 prompt（编码/名称/授权区间），用对话框队列按序应答。
const promptAnswers = ['sim-e2e-source', 'E2E 验收来源', '2026-01-01', '2027-12-31']
page.on('dialog', (dialog) => dialog.accept(promptAnswers.shift() ?? ''))
const createSourceResponse = page.waitForResponse(
  (response) =>
    response.url().includes('/admin/news-sources') &&
    response.request().method() === 'POST',
  { timeout: 15_000 },
)
await page.getByRole('button', { name: '新建来源' }).click()
assert.equal((await createSourceResponse).status(), 201, 'ADM-NEWS-03 不是 201')
await page.getByText('sim-e2e-source').waitFor({ timeout: 10_000 })
console.log('写路径 OK：新建来源 201 且出现在列表')

// ---------- 写路径 2：人工触发任务（ADM-JOB-02，202）----------
await page.getByRole('button', { name: '定时任务' }).click()
const triggerResponse = page.waitForResponse(
  (response) =>
    /\/admin\/job-definitions\/[a-z-]+\/executions/.test(response.url()) &&
    response.request().method() === 'POST',
  { timeout: 15_000 },
)
await page.getByRole('button', { name: '人工触发' }).first().click()
assert.equal((await triggerResponse).status(), 202, 'ADM-JOB-02 不是 202')
console.log('写路径 OK：人工触发 202（异步执行）')

// ---------- 汇总 ----------
const failedCalls = apiCalls.filter((call) => call.status >= 400)
console.log('--- 非 2xx 的 /api 请求 ---')
for (const call of failedCalls) console.log(call.status, call.url)
console.log('--- 控制台错误 ---')
console.log(consoleErrors.length ? consoleErrors : '（无）')

// 登录前的 refresh 401 属预期游客态；其余不应有 401/403
const authFailures = failedCalls.filter(
  (call) => (call.status === 401 || call.status === 403) && !call.url.includes('/auth/token/refresh'),
)
assert.equal(authFailures.length, 0, '已登录 admin 会话内出现 401/403')
assert.equal(consoleErrors.length, 0, `出现 ${consoleErrors.length} 条控制台错误`)

await browser.close()
console.log('=== M3-11 后台管理面验收：全部通过 ===')
