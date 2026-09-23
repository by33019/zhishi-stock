// 真实浏览器验收：M3-10 AI 工作台（AI-01~08 + HIS-06/07 + USER-07）+ /history 写操作。
// 与单测不同，这里走完整链路：浏览器 → nginx → stock-api → MySQL/Redis → stock-ai-worker，
// 最后直接查库证明页面上的每个字都来自真实数据库，而不是前端虚拟数据。
// 手动运行：node frontend/e2e/ai-workspace.real.mjs（需先 docker compose up -d --build）
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'

import { chromium } from 'playwright'

const execFileAsync = promisify(execFile)

const BASE = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:8088'
const ACCOUNT = process.env.E2E_ACCOUNT ?? 'demo'
const PASSWORD = process.env.E2E_PASSWORD ?? 'Stock@123'
const QUESTION = '浏览器级验收：SSE 实时性'
// 改名后的标题必须仍包含列表关键字，否则改名触发的列表刷新会把这行筛掉、
// 详情区随之关闭（HistoryPage 的既定行为：选中行消失时收起详情）。
const RENAMED = '浏览器级验收：SSE 实时性（已改名）'

const browser = await chromium.launch()
const page = await browser.newPage()
const failures = []
const apiCalls = []

page.on('response', (response) => {
  const url = response.url()
  if (url.includes('/api/v1/')) apiCalls.push({ url: url.replace(BASE, ''), status: response.status() })
})
page.on('pageerror', (error) => failures.push(`页面 JS 异常：${error.message}`))
let phase = 'guest'
page.on('console', (message) => {
  if (message.type() !== 'error' || phase !== 'app') return
  // `done` 到达后页面会主动 close() 流句柄：Chrome 把"被中止的分块响应"记成
  // ERR_INCOMPLETE_CHUNKED_ENCODING 资源错误。这是主动断流的预期噪声，
  // 不是缺陷——流是否健康由上面的"临时文本逐段增长 + 报告就位 + 库中 COMPLETED"
  // 三项共同证明，不需要靠控制台沉默来证明。
  if (message.text().includes('ERR_INCOMPLETE_CHUNKED_ENCODING')) return
  failures.push(`控制台错误：${message.text()}`)
})

function check(label, ok, detail = '') {
  console.log(`${ok ? '通过' : '失败'}  ${label}${detail ? `  ${detail}` : ''}`)
  if (!ok) failures.push(`${label} ${detail}`)
}

/** 直接查库：页面上的东西必须能在 MySQL 里找到来源。 */
async function sql(query) {
  // 本机环境里 Node 的 spawnSync 一律 EBUSY（连 cmd.exe 都起不来），
  // 异步 execFile 正常——这里必须用异步形式。
  const { stdout } = await execFileAsync('docker', [
    'exec', 'stock_system-mysql-1', 'sh', '-c',
    `mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" --default-character-set=utf8mb4 "$MYSQL_DATABASE" -N -e "${query}"`,
  ])
  return stdout.trim()
}

// ---- 登录 ----
await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' })
await page.getByPlaceholder(/账号|用户名|手机号/).fill(ACCOUNT)
await page.getByPlaceholder(/密码/).fill(PASSWORD)
await page.getByRole('button', { name: /登录/ }).click()
await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 15000 })
phase = 'app'
check('登录后离开登录页', true, page.url().replace(BASE, ''))

// ---- /ai 首屏：场景与配额都来自真实接口 ----
await page.goto(`${BASE}/ai`, { waitUntil: 'networkidle' })
await page.waitForTimeout(800)

check('AI-01 被请求（场景清单）', apiCalls.some((c) => c.url === '/api/v1/ai/scenes' && c.status === 200))
check('USER-07 被请求（配额）', apiCalls.some((c) => c.url === '/api/v1/users/me/ai-quota' && c.status === 200))
const quotaText = await page.locator('.quota-note').innerText()
check('配额说明来自 USER-07', /今日剩余 \d+ \/ \d+ 次/.test(quotaText), quotaText)
const sceneOptions = await page.locator('select option').allTextContents()
check('场景下拉来自 AI-01（至少 5 个场景）', sceneOptions.length >= 5, JSON.stringify(sceneOptions))

// ---- 标的检索（STK-01）与上下文预览（AI-02）----
await page.locator('.inline-search input').fill('600519')
await page.locator('.inline-search input').press('Enter')
await page.locator('.target-candidates button').first().waitFor({ timeout: 10000 })
check('STK-01 被请求（标的检索）', apiCalls.some((c) => c.url.includes('/api/v1/securities/search') && c.status === 200))
await page.locator('.target-candidates button').first().click()
await page.locator('.selected-target').waitFor()

await page.locator('.research-setup .secondary-button').click()
await page.waitForTimeout(800)
check('AI-02 被请求（上下文预览）', apiCalls.some((c) => c.url.includes('/api/v1/ai/context-previews') && c.status === 200))
const previewText = await page.locator('.research-setup').innerText()
check('预览展示真实数据截止与缺口', previewText.includes('数据截止'), '')

// ---- 提交（AI-03）→ SSE（AI-05）流式生成 ----
await page.locator('.question-composer textarea').fill(QUESTION)
const taskCountBefore = Number(await sql('SELECT COUNT(*) FROM ai_task'))
await page.locator('.question-composer button').click()

await page.locator('.report-generating').waitFor({ timeout: 15000 })
check('AI-03 返回 202', apiCalls.some((c) => c.url === '/api/v1/ai/tasks' && c.status === 202))

// SSE 流请求必须真的发出（经 nginx 的 /api/v1/ai/ 专用转发）
await page.waitForTimeout(1000)
check(
  'AI-05 SSE 流已建立（浏览器发出了 stream 请求）',
  apiCalls.some((c) => /\/api\/v1\/ai\/tasks\/\d+\/stream$/.test(c.url)),
  JSON.stringify(apiCalls.filter((c) => c.url.includes('/stream'))),
)

// 临时文本逐段增长：流式的核心证据——轮询给不出"越变越长"的中间态
let previousLength = 0
let grew = false
const streamDeadline = Date.now() + 120_000
for (;;) {
  const node = page.locator('.streaming-text')
  const length = (await node.count()) ? (await node.innerText()).length : 0
  if (length > previousLength && previousLength > 0) grew = true
  previousLength = Math.max(previousLength, length)
  if (await page.locator('.ai-report').count()) break
  if (Date.now() > streamDeadline) break
  await page.waitForTimeout(300)
}
check('生成中的临时文本逐段增长（SSE chunk 实时到达）', grew, `最终长度 ${previousLength}`)

// ---- 报告就位（HIS-06）与引用（HIS-07）----
await page.locator('.ai-report').waitFor({ timeout: 300_000 })
const reportText = await page.locator('.ai-report').innerText()
for (const section of ['核心结论', '行情与量价依据', '风险与不确定性', '数据截止']) {
  check(`报告包含「${section}」`, reportText.includes(section))
}
check('HIS-06 被请求（报告详情）', apiCalls.some((c) => /\/api\/v1\/ai\/reports\/\d+$/.test(c.url) && c.status === 200))
check('HIS-07 被请求（报告引用）', apiCalls.some((c) => c.url.includes('/evidence') && c.status === 200))

// 查库：任务、消息、报告都是真实落库的行
const taskCountAfter = Number(await sql('SELECT COUNT(*) FROM ai_task'))
check('ai_task 新增一行（真实落库）', taskCountAfter === taskCountBefore + 1, `${taskCountBefore} → ${taskCountAfter}`)
const latestTask = await sql('SELECT id, session_id, status FROM ai_task ORDER BY id DESC LIMIT 1')
const [taskId, sessionId, taskStatus] = latestTask.split('\t')
check('库中最新任务状态为 COMPLETED', taskStatus === 'COMPLETED', latestTask)
const cutoff = await sql(`SELECT market_data_cutoff_at FROM ai_report WHERE task_id = ${taskId}`)
check('报告的行情截止时间来自真实批次（非"现在"）', /\d{4}-\d{2}-\d{2} 15:00:00/.test(cutoff), cutoff)
// 页面上的免责声明必须是库里的那一条，而不是前端模板里的常量
const dbDisclaimer = await sql(`SELECT disclaimer FROM ai_report WHERE task_id = ${taskId}`)
check('页面免责声明与库中一致', reportText.includes(dbDisclaimer), dbDisclaimer.slice(0, 40))
const messageCount = Number(await sql(`SELECT COUNT(*) FROM ai_message WHERE task_id = ${taskId}`))
check('ai_message 有真实问答记录', messageCount >= 2, String(messageCount))

// ---- 追问（AI-08）：同一会话产生第二个任务 ----
await page.locator('.follow-up-composer textarea').fill('把风险部分展开成可核对的检查清单')
await page.locator('.follow-up-composer button').click()
await page.waitForTimeout(1000)
check(
  'AI-08 返回 202（追问受理）',
  apiCalls.some((c) => /\/api\/v1\/ai\/sessions\/\d+\/follow-up-tasks$/.test(c.url) && c.status === 202),
)
await page.locator('.ai-report').waitFor({ timeout: 300_000 })
const followUpCount = Number(await sql(`SELECT COUNT(*) FROM ai_task WHERE session_id = ${sessionId}`))
check('追问在同一 session 下落库第二个任务', followUpCount === 2, `session ${sessionId} 共 ${followUpCount} 个任务`)

// ---- /history 写操作：重命名（HIS-03）/ 收藏 / 两步删除（HIS-04）----
await page.goto(`${BASE}/history`, { waitUntil: 'networkidle' })
await page.waitForTimeout(800)
await page.locator('.history-main input').fill(QUESTION)
await page.locator('.history-main input').press('Enter')
await page.waitForTimeout(800)
await page.locator('.report-list article h2 button').first().click()
await page.locator('.history-detail').waitFor()

await page.getByRole('button', { name: '重命名' }).click()
await page.locator('.history-rename input').fill(RENAMED)
await page.locator('.history-rename button[type="submit"]').click()
await page.waitForTimeout(800)
check('HIS-03 重命名成功（PATCH 200）', apiCalls.some((c) => /\/api\/v1\/ai\/sessions\/\d+$/.test(c.url) && c.status === 200))
const dbTitle = await sql(`SELECT title FROM ai_session WHERE id = ${sessionId}`)
check('库中会话标题已更新', dbTitle === RENAMED, dbTitle)

await page.getByRole('button', { name: '收藏' }).click()
await page.waitForTimeout(800)
const dbFavorite = await sql(`SELECT is_favorite FROM ai_session WHERE id = ${sessionId}`)
check('收藏落库（is_favorite = 1）', dbFavorite === '1', dbFavorite)

await page.getByRole('button', { name: '删除' }).click()
await page.locator('.history-confirm').waitFor()
check('删除是两步确认（先出确认区）', true)
await page.getByRole('button', { name: '确定删除' }).click()
await page.waitForTimeout(800)
const dbDeleted = await sql(`SELECT deleted_at IS NOT NULL FROM ai_session WHERE id = ${sessionId}`)
check('软删落库（deleted_at 非空）', dbDeleted === '1', dbDeleted)
const listText = await page.locator('.history-main').innerText()
check('删除后列表不再出现该会话', !listText.includes(RENAMED), '')

// ---- 汇总 ----
check('页面无 JS 异常与控制台错误', failures.length === 0, failures.join(' | ').slice(0, 300))

await browser.close()

if (failures.length) {
  console.error(`\n${failures.length} 项失败：`)
  for (const failure of failures) console.error(' -', failure)
  process.exit(1)
}
console.log('\nAI 工作台浏览器级验收全部通过')
