import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

import { chromium } from 'playwright'

const baseUrl = 'http://127.0.0.1:4173'
const outputDir = resolve('output/playwright')
const browser = await chromium.launch()
const report = {
  pages: [],
  consoleErrors: [],
  consoleWarnings: [],
  browserDiagnostics: [],
  pageErrors: [],
}

await mkdir(outputDir, { recursive: true })

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
await desktopPage.locator('[data-testid="ai-panel-toggle"]').click()
await desktopPage.locator('[data-testid="ai-side-panel"]').waitFor({ state: 'visible' })
await desktopPage.screenshot({ path: resolve(outputDir, 'ai-panel-desktop.png'), fullPage: true })
await desktopPage.locator('[aria-label="关闭 AI 研究侧栏"]').click()
await desktopPage.locator('[data-testid="ai-side-panel"]').waitFor({ state: 'detached' })

await openPage(desktopPage, '/ai', '.ai-workspace-page')
await desktopPage.locator('[data-testid="prompt-template"]').click()
await desktopPage.locator('[data-testid="generate-report"]').click()
await desktopPage.locator('.ai-report').waitFor({ state: 'visible' })
await desktopPage.getByText('风险与不确定性', { exact: true }).waitFor({ state: 'visible' })
await desktopPage.screenshot({ path: resolve(outputDir, 'ai-report-desktop.png'), fullPage: true })

await openPage(desktopPage, '/login', '.login-page')
if (await desktopPage.locator('input[type="password"]').count() !== 1) {
  throw new Error('登录页未呈现唯一密码输入框')
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
await mobile.close()

await browser.close()

if (report.consoleErrors.length || report.consoleWarnings.length || report.browserDiagnostics.length || report.pageErrors.length) {
  throw new Error(`浏览器错误：${JSON.stringify(report, null, 2)}`)
}

await writeFile(resolve(outputDir, 'visual-smoke-report.json'), `${JSON.stringify(report, null, 2)}\n`)
console.log(JSON.stringify(report, null, 2))
