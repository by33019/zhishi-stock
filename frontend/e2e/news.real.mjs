import assert from 'node:assert/strict'

import { chromium } from 'playwright'

const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:8088'

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext()
const page = await context.newPage()

const calls = []
const consoleErrors = []
page.on('response', (response) => {
  if (response.url().includes('/api/v1/news')) {
    calls.push({ url: response.url(), status: response.status() })
  }
})
page.on('console', (message) => {
  if (message.type() === 'error') consoleErrors.push(message.text())
})

await page.goto(`${baseURL}/news`)
await page.getByRole('heading', { name: '资讯中心', exact: true }).waitFor()

console.log('=== 首屏请求 ===')
for (const call of calls) console.log(call.status, call.url)

const listCall = calls.find((call) => /\/api\/v1\/news\?/.test(call.url))
assert.ok(listCall, '首屏没有发出 NEWS-01 请求')
assert.equal(listCall.status, 200)

const listBody = await (await context.request.get(listCall.url)).json()
console.log('--- NEWS-01 响应 ---')
console.log('total:', listBody.data.total, 'page:', listBody.data.page,
  'totalPages:', listBody.data.totalPages, 'hasNext:', listBody.data.hasNext)
console.log('dataStatus:', listBody.data.dataStatus,
  'lastSuccessfulSyncAt:', listBody.data.lastSuccessfulSyncAt)
console.log('items 条数:', listBody.data.items.length)

const tabs = await page.locator('[data-testid="news-type-tab"]').allTextContents()
console.log('--- 类型标签（来自 NEWS-04）---')
console.log(JSON.stringify(tabs))

const optionsCall = calls.find((call) => call.url.includes('/news/options'))
assert.ok(optionsCall, '首屏没有发出 NEWS-04 请求')
const optionsBody = await (await context.request.get(optionsCall.url)).json()
console.log('newsTypes:', JSON.stringify(optionsBody.data.newsTypes))
console.log('sourceTypes:', JSON.stringify(optionsBody.data.sourceTypes))
console.log('availableTimeRange:', JSON.stringify(optionsBody.data.availableTimeRange))
console.log('filterRules:', optionsBody.data.filterRules)

const titles = await page.locator('.news-feed__content h2').allTextContents()
console.log('--- 渲染出的标题 ---')
console.log(JSON.stringify(titles))
assert.equal(titles.length, listBody.data.items.length, '页面条数与响应条数不一致')

const originals = await page.locator('[data-testid="news-original"]').evaluateAll((nodes) =>
  nodes.map((node) => ({
    href: node.getAttribute('href'),
    target: node.getAttribute('target'),
    rel: node.getAttribute('rel'),
  })))
console.log('--- 原文链接 ---')
console.log(JSON.stringify(originals))
for (const link of originals) {
  assert.notEqual(link.href, '#', '原文链接出现占位符')
  assert.ok(link.href.startsWith('http'), '原文链接不是绝对地址')
}

const relations = await page.locator('[data-testid="news-relation"]').evaluateAll((nodes) =>
  nodes.map((node) => ({
    text: node.textContent.trim(),
    href: node.querySelector('a')?.getAttribute('href') ?? null,
  })))
console.log('--- 关联标签 ---')
console.log(JSON.stringify(relations))

console.log('--- 侧栏 ---')
console.log((await page.locator('.news-sidebar').innerText()).replace(/\s+/g, ' '))

const headerTime = await page.locator('.page-header__time').count()
console.log('数据截止时间节点数:', headerTime)
if (headerTime > 0) console.log('数据截止:', await page.locator('.page-header__time').innerText())

console.log('空态文案节点数:', await page.locator('[data-testid="news-empty"]').count())

// 切换类型：必须带 newsTypes，且**不**重发 options
const beforeSwitch = calls.length
const [switchResponse] = await Promise.all([
  page.waitForResponse((response) =>
    response.url().includes('newsTypes=') && response.request().method() === 'GET'),
  page.locator('[data-testid="news-type-tab"]').nth(2).click(),
])
const switchBody = await switchResponse.json()
console.log('--- 切换类型 ---')
console.log('请求:', switchResponse.url())
console.log('筛选后 total:', switchBody.data.total, 'items:', switchBody.data.items.length)
console.log('筛选后 newsType 取值:', JSON.stringify(switchBody.data.items.map((item) => item.newsType)))
console.log('筛选后标题:', JSON.stringify(await page.locator('.news-feed__content h2').allTextContents()))
console.log('切换后新增请求数:', calls.length - beforeSwitch)
console.log('options 累计请求次数:', calls.filter((call) => call.url.includes('/news/options')).length)

// 关键字搜索
const [searchResponse] = await Promise.all([
  page.waitForResponse((response) =>
    response.url().includes('keyword=') && response.request().method() === 'GET'),
  (async () => {
    await page.locator('[data-testid="news-keyword"]').fill('银行')
    await page.locator('[data-testid="news-search"]').click()
  })(),
])
const searchBody = await searchResponse.json()
console.log('--- 关键字搜索 ---')
console.log('请求:', decodeURIComponent(searchResponse.url()))
console.log('结果 total:', searchBody.data.total, 'items:', searchBody.data.items.length)
console.log('结果标题:', JSON.stringify(searchBody.data.items.map((item) => item.title)))

// 翻页：必须保留筛选条件
const page2Link = await page.locator('[data-testid="news-next"]').isDisabled()
console.log('--- 翻页 ---')
console.log('下一页按钮 disabled:', page2Link)
if (!page2Link) {
  const [page2Response] = await Promise.all([
    page.waitForResponse((response) =>
      response.url().includes('page=2') && response.request().method() === 'GET'),
    page.locator('[data-testid="news-next"]').click(),
  ])
  console.log('第 2 页请求:', decodeURIComponent(page2Response.url()))
  const page2Body = await page2Response.json()
  console.log('第 2 页 items:', page2Body.data.items.length, 'page:', page2Body.data.page)
}

console.log('--- 控制台错误 ---')
console.log(JSON.stringify(consoleErrors))

console.log('=== e2e 核对完成 ===')

// Playwright 的 browser.close() 在本机会偶发挂住（e2e 实测：日志已写全，进程却不退出），
// 加超时兜底再强制退出，避免手动跑的人以为脚本失败。
await Promise.race([
  browser.close(),
  new Promise((resolve) => setTimeout(resolve, 5000)),
])
process.exit(0)
