import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

import { chromium } from 'playwright'

const baseUrl = 'http://127.0.0.1:4173'
const outputDir = resolve('output/playwright')
const browser = await chromium.launch()
const report = {
  pages: [],
  layoutMetrics: [],
  typographySamples: [],
  typographyViolations: [],
  consoleErrors: [],
  consoleWarnings: [],
  browserDiagnostics: [],
  pageErrors: [],
}

await mkdir(outputDir, { recursive: true })

async function auditReadableText(page, path) {
  const violations = await page.locator('body *').evaluateAll((elements) => elements.flatMap((element) => {
    const directText = Array.from(element.childNodes)
      .filter((node) => node.nodeType === Node.TEXT_NODE)
      .map((node) => node.textContent?.trim() ?? '')
      .join(' ')
      .trim()
    const style = window.getComputedStyle(element)
    const rect = element.getBoundingClientRect()
    const fontSize = Number.parseFloat(style.fontSize)

    if (!directText || !rect.width || !rect.height || style.visibility === 'hidden' || style.display === 'none' || fontSize >= 12) {
      return []
    }

    return [{
      selector: element.className ? `${element.tagName.toLowerCase()}.${String(element.className).trim().replaceAll(' ', '.')}` : element.tagName.toLowerCase(),
      text: directText.slice(0, 60),
      fontSize,
    }]
  }))

  report.typographyViolations.push(...violations.map((item) => ({ path, rule: '可见业务文字不得低于 12px', ...item })))
}

async function inspectTypography(page, path, rules) {
  for (const rule of rules) {
    const target = page.locator(rule.selector).first()
    if (await target.count() === 0) {
      report.typographyViolations.push({ path, selector: rule.selector, rule: `${rule.label}未找到` })
      continue
    }

    const fontSize = await target.evaluate((element) => Number.parseFloat(window.getComputedStyle(element).fontSize))
    report.typographySamples.push({ path, label: rule.label, selector: rule.selector, fontSize })
    if ((rule.min !== undefined && fontSize < rule.min) || (rule.max !== undefined && fontSize > rule.max)) {
      report.typographyViolations.push({ path, label: rule.label, selector: rule.selector, fontSize, min: rule.min, max: rule.max })
    }
  }
}

async function openPage(page, path, readySelector) {
  await page.goto(`${baseUrl}${path}`, { waitUntil: 'networkidle' })
  await page.locator(readySelector).waitFor({ state: 'visible' })
  const layout = await page.evaluate(() => ({
    viewportWidth: window.innerWidth,
    scrollWidth: document.documentElement.scrollWidth,
    title: document.title,
  }))
  if (layout.scrollWidth > layout.viewportWidth + 1) {
    throw new Error(`${path} 存在水平溢出：${layout.scrollWidth}px > ${layout.viewportWidth}px`)
  }
  report.pages.push({ path, ...layout })
  await auditReadableText(page, path)
}

const desktop = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' })
const desktopPage = await desktop.newPage()
desktopPage.on('console', (message) => {
  if (message.type() === 'error') report.consoleErrors.push({ url: desktopPage.url(), text: message.text() })
  if (message.type() === 'warning') report.consoleWarnings.push({ url: desktopPage.url(), text: message.text() })
  if (message.text().includes('[ECharts]')) report.browserDiagnostics.push({ url: desktopPage.url(), text: message.text() })
})
desktopPage.on('pageerror', (error) => report.pageErrors.push({ url: desktopPage.url(), text: error.message }))

await openPage(desktopPage, '/market', '.market-page')
await inspectTypography(desktopPage, '/market', [
  { label: '市场页标题', selector: '.market-lead h1', max: 42 },
  { label: '市场页描述', selector: '.market-lead > div:first-child > p', min: 15 },
  { label: '市场模块标题', selector: '.section-heading h2', min: 20 },
  { label: '市场表格正文', selector: '.quote-table td', min: 14 },
])
await desktopPage.screenshot({ path: resolve(outputDir, 'market-desktop.png'), fullPage: true })
await desktopPage.locator('[data-testid="ai-panel-toggle"]').click()
await desktopPage.locator('[data-testid="ai-side-panel"]').waitFor({ state: 'visible' })
await desktopPage.screenshot({ path: resolve(outputDir, 'ai-panel-desktop.png'), fullPage: true })
await desktopPage.locator('[aria-label="关闭 AI 研究侧栏"]').click()
await desktopPage.locator('[data-testid="ai-side-panel"]').waitFor({ state: 'detached' })

await openPage(desktopPage, '/ai', '.ai-workspace-page')
await inspectTypography(desktopPage, '/ai', [
  { label: 'AI 页面标题', selector: '.ai-workspace-head h1', max: 42 },
  { label: 'AI 页面描述', selector: '.ai-workspace-head p', min: 15 },
  { label: '研究设置标题', selector: '.research-setup > header', min: 14 },
  { label: '研究设置标签', selector: '.research-setup > label', min: 13 },
  { label: '目标标的选择器', selector: '.stock-selector', min: 14 },
  { label: '问题模板标签', selector: '.setup-label', min: 13 },
  { label: '问题模板正文', selector: '.template-button', min: 14 },
  { label: '问题输入框', selector: '.question-composer textarea', min: 14 },
  { label: '分析按钮', selector: '.question-composer button', min: 14 },
  { label: '来源引用标题', selector: '.evidence-drawer > header', min: 14 },
  { label: '来源引用空状态', selector: '.evidence-empty', min: 14 },
])
await desktopPage.screenshot({ path: resolve(outputDir, 'ai-workspace-desktop.png'), fullPage: true })
await desktopPage.locator('[data-testid="prompt-template"]').click()
await desktopPage.locator('[data-testid="generate-report"]').click()
await desktopPage.locator('.ai-report').waitFor({ state: 'visible' })
await desktopPage.getByText('风险与不确定性', { exact: true }).waitFor({ state: 'visible' })
await inspectTypography(desktopPage, '/ai', [
  { label: 'AI 报告章节标题', selector: '.ai-report > section h2', min: 20 },
  { label: 'AI 报告正文', selector: '.ai-report > section p', min: 14 },
  { label: '来源引用卡片正文', selector: '.evidence-cards p', min: 13 },
])
await desktopPage.screenshot({ path: resolve(outputDir, 'ai-report-desktop.png'), fullPage: true })

const shellRoutes = [
  ['/rankings', '.business-page'],
  ['/sectors', '.business-page'],
  ['/sectors/BK0475', '.business-page'],
  ['/stocks/600000', '.stock-page'],
  ['/news', '.business-page'],
  ['/watchlist', '.business-page'],
  ['/history', '.business-page'],
  ['/admin', '.business-page'],
]

for (const [path, readySelector] of shellRoutes) {
  await openPage(desktopPage, path, readySelector)
  await inspectTypography(desktopPage, path, [{ label: '常规页面标题', selector: 'h1', max: 42 }])
  if (path === '/stocks/600000') {
    await desktopPage.screenshot({ path: resolve(outputDir, 'stock-desktop.png'), fullPage: true })
  }
}

await openPage(desktopPage, '/login', '.login-page')
if (await desktopPage.locator('input[type="password"]').count() !== 1) {
  throw new Error('登录页未呈现唯一密码输入框')
}
await inspectTypography(desktopPage, '/login', [
  { label: '登录品牌标题', selector: '.login-story h1', min: 40, max: 48 },
  { label: '登录品牌描述', selector: '.login-story__content > p', min: 15 },
  { label: '登录表单标题', selector: '.login-form h2', min: 28, max: 34 },
  { label: '登录表单标签', selector: '.login-field > label', min: 13 },
  { label: '登录输入内容', selector: '.login-form input[type="text"]', min: 14 },
])

const loginDesktopMetrics = await desktopPage.evaluate(() => {
  const story = document.querySelector('.login-story')
  const card = document.querySelector('.login-card')
  const input = document.querySelector('.login-input')
  if (!story || !card || !input) throw new Error('登录页统一画布结构缺失')

  const cardRect = card.getBoundingClientRect()
  const storyBackground = window.getComputedStyle(story).backgroundColor
  const inputHeight = input.getBoundingClientRect().height

  return { cardWidth: cardRect.width, storyBackground, inputHeight }
})

if (loginDesktopMetrics.storyBackground !== 'rgba(0, 0, 0, 0)') {
  throw new Error(`登录品牌区仍存在独立背景：${loginDesktopMetrics.storyBackground}`)
}
if (loginDesktopMetrics.cardWidth < 400 || loginDesktopMetrics.cardWidth > 450) {
  throw new Error(`登录卡片宽度不符合设计：${loginDesktopMetrics.cardWidth}px`)
}
if (loginDesktopMetrics.inputHeight < 48) {
  throw new Error(`登录输入框高度不足：${loginDesktopMetrics.inputHeight}px`)
}
await desktopPage.screenshot({ path: resolve(outputDir, 'login-desktop.png'), fullPage: true })
await desktop.close()

const mobile = await browser.newContext({ viewport: { width: 390, height: 844 }, locale: 'zh-CN', isMobile: true })
const mobilePage = await mobile.newPage()
mobilePage.on('console', (message) => {
  if (message.type() === 'error') report.consoleErrors.push({ url: mobilePage.url(), text: message.text() })
  if (message.type() === 'warning') report.consoleWarnings.push({ url: mobilePage.url(), text: message.text() })
  if (message.text().includes('[ECharts]')) report.browserDiagnostics.push({ url: mobilePage.url(), text: message.text() })
})
mobilePage.on('pageerror', (error) => report.pageErrors.push({ url: mobilePage.url(), text: error.message }))

await openPage(mobilePage, '/market', '.market-page')
await mobilePage.locator('[aria-label="打开导航"]').click()
await mobilePage.locator('.sidebar.is-open').waitFor({ state: 'visible' })
await mobilePage.locator('.nav-scrim').click({ position: { x: 370, y: 100 } })
await mobilePage.locator('.sidebar.is-open').waitFor({ state: 'detached' }).catch(async () => {
  if (await mobilePage.locator('.sidebar.is-open').count()) throw new Error('移动导航未能关闭')
})
await mobilePage.screenshot({ path: resolve(outputDir, 'market-mobile.png'), fullPage: true })

await openPage(mobilePage, '/login', '.login-card')
const loginMobileMetrics = await mobilePage.evaluate(() => {
  const stage = document.querySelector('.login-stage')
  const card = document.querySelector('.login-card')
  const submit = document.querySelector('.login-submit')
  if (!stage || !card || !submit) throw new Error('移动端登录结构缺失')

  const submitRect = submit.getBoundingClientRect()
  const viewportHeight = window.innerHeight

  return {
    stageColumns: window.getComputedStyle(stage).gridTemplateColumns.split(' ').length,
    cardWidth: card.getBoundingClientRect().width,
    submitHeight: submit.getBoundingClientRect().height,
    submitTop: submitRect.top,
    submitBottom: submitRect.bottom,
    viewportHeight,
    documentHeight: document.documentElement.scrollHeight,
    submitScrollDistance: Math.max(0, submitRect.bottom - viewportHeight),
    cardBoxShadow: window.getComputedStyle(card).boxShadow,
  }
})
report.layoutMetrics.push({ path: '/login', viewport: '390x844', ...loginMobileMetrics })

if (loginMobileMetrics.stageColumns !== 1) {
  throw new Error(`移动端登录页未切换为单列：${loginMobileMetrics.stageColumns} 列`)
}
if (loginMobileMetrics.cardWidth > 354) {
  throw new Error(`移动端登录卡片超出安全宽度：${loginMobileMetrics.cardWidth}px`)
}
if (loginMobileMetrics.submitHeight < 44) {
  throw new Error(`移动端登录按钮触控高度不足：${loginMobileMetrics.submitHeight}px`)
}
if (loginMobileMetrics.submitScrollDistance > 120) {
  throw new Error(`移动端完整显示登录按钮所需滚动距离过长：${loginMobileMetrics.submitScrollDistance}px > 120px`)
}
if (loginMobileMetrics.cardBoxShadow !== 'none') {
  throw new Error(`移动端登录卡片不应保留明显悬浮阴影：${loginMobileMetrics.cardBoxShadow}`)
}
await mobilePage.screenshot({ path: resolve(outputDir, 'login-mobile.png'), fullPage: true })

await mobilePage.setViewportSize({ width: 767, height: 900 })
const loginBreakpointMetrics = await mobilePage.evaluate(() => {
  const stage = document.querySelector('.login-stage')
  const card = document.querySelector('.login-card')
  if (!stage || !card) throw new Error('临界宽度下登录布局缺失')

  return {
    stageColumns: window.getComputedStyle(stage).gridTemplateColumns.split(' ').length,
    cardBoxShadow: window.getComputedStyle(card).boxShadow,
  }
})
report.layoutMetrics.push({ path: '/login', viewport: '767x900', ...loginBreakpointMetrics })

if (loginBreakpointMetrics.stageColumns !== 1) {
  throw new Error(`767px 临界宽度下登录页未切换为单列：${loginBreakpointMetrics.stageColumns} 列`)
}
if (loginBreakpointMetrics.cardBoxShadow !== 'none') {
  throw new Error(`767px 临界宽度下登录卡片不应保留明显悬浮阴影：${loginBreakpointMetrics.cardBoxShadow}`)
}
await mobile.close()

await browser.close()

if (report.consoleErrors.length || report.consoleWarnings.length || report.browserDiagnostics.length || report.pageErrors.length || report.typographyViolations.length) {
  throw new Error(`浏览器错误：${JSON.stringify(report, null, 2)}`)
}

await writeFile(resolve(outputDir, 'visual-smoke-report.json'), `${JSON.stringify(report, null, 2)}\n`)
console.log(JSON.stringify(report, null, 2))
