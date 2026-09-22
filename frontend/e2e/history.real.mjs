// 真实浏览器验收：登录后打开 /history，确认页面用真实接口渲染。
// 手动运行：node frontend/e2e/history.real.mjs
import { chromium } from 'playwright'

const BASE = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:8088'
const ACCOUNT = process.env.E2E_ACCOUNT ?? 'demo'
const PASSWORD = process.env.E2E_PASSWORD ?? 'Stock@123'

const browser = await chromium.launch()
const page = await browser.newPage()
const failures = []
const requests = []

page.on('request', (request) => {
  const url = request.url()
  if (url.includes('/api/v1/ai/sessions')) requests.push(url.replace(BASE, ''))
})
page.on('pageerror', (error) => failures.push(`页面 JS 异常：${error.message}`))
// 只在**登录之后**统计控制台错误。登录前 restore() 会向 token/refresh 发一次请求并拿到
// 401，那是 M1 定下的游客态路径（"未登录 → 401 → 跳登录"），不是缺陷；
// 把它算成失败会让这份脚本永远红着，久了就没人看它了。
let phase = 'guest'
page.on('console', (message) => {
  if (message.type() === 'error' && phase === 'app') {
    failures.push(`控制台错误：${message.text()}`)
  }
})

function check(label, ok, detail = '') {
  console.log(`${ok ? '通过' : '失败'}  ${label}${detail ? `  ${detail}` : ''}`)
  if (!ok) failures.push(`${label} ${detail}`)
}

// ---- 登录（页面上没有直达的登录态注入，走真实表单）----
await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' })
await page.getByPlaceholder(/账号|用户名|手机号/).fill(ACCOUNT)
await page.getByPlaceholder(/密码/).fill(PASSWORD)
await page.getByRole('button', { name: /登录/ }).click()
await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 15000 })
phase = 'app'
check('登录后离开登录页', true, page.url().replace(BASE, ''))

// ---- 直接进 /history（守卫此前会把游客弹到登录页）----
requests.length = 0
await page.goto(`${BASE}/history`, { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)

check('停留在 /history 而不是被弹回登录页', !page.url().includes('/login'), page.url().replace(BASE, ''))
check(
  '首屏只请求了会话列表（没有逐条拉详情）',
  requests.length === 1 && requests[0].startsWith('/api/v1/ai/sessions?'),
  `实际 ${requests.length} 个：${JSON.stringify(requests)}`,
)

const body = await page.locator('body').innerText()
check('页面渲染出真实会话标题', body.includes('请说明这只股票近期的量价特征'), '')
check('筛选栏来自真实场景而不是原型常量', body.includes('多标的对比') && body.includes('风险梳理'), '')
check('原型里的编造计数已消失（不再出现 24 / 12 / 3 这类数字）', !/全部研究\s*24/.test(body), '')
check('原型里的假报告编号已消失', !body.includes('R-0908-01'), '')

// ---- 点开一条记录：应当发出详情与消息两个请求 ----
requests.length = 0
await page.locator('.report-info h2 button').first().click()
await page.waitForTimeout(1500)
const detailCalls = requests.filter((u) => /\/ai\/sessions\/\d+$/.test(u)).length
const messageCalls = requests.filter((u) => u.includes('/messages')).length
check('点开记录后请求了详情与消息各一次', detailCalls === 1 && messageCalls === 1,
  `详情 ${detailCalls} 次 / 消息 ${messageCalls} 次`)

const afterClick = await page.locator('body').innerText()
check('详情区渲染出分析目标', afterClick.includes('分析目标'), '')
check('渲染出提问与结论正文', afterClick.includes('提问') && afterClick.includes('分析结论'), '')
check('报告受限状态被明说（不是只给一个质量码）', afterClick.includes('受限'), '')

// 关联标的的跳转目标必须是可被接口解析的对外标识
const href = await page.locator('.target-list a').first().getAttribute('href').catch(() => null)
check('关联标的链接指向可解析的对外标识', Boolean(href && href.startsWith('/stocks/sim-')), href ?? '(无链接)')

check('页面无 JS 异常与控制台错误', failures.length === 0, failures.join(' | ').slice(0, 300))

await browser.close()
console.log(failures.length === 0 ? '\n全部通过' : `\n有 ${failures.length} 项失败`)
process.exit(failures.length === 0 ? 0 : 1)
