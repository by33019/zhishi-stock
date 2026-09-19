import assert from 'node:assert/strict'

import { chromium } from 'playwright'

const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:8088'
const username = process.env.E2E_USERNAME ?? 'demo'
const password = process.env.E2E_PASSWORD ?? 'Stock@123'

const browser = await chromium.launch({ headless: true })
try {
  const context = await browser.newContext()
  const page = await context.newPage()

  const [marketResponse] = await Promise.all([
    page.waitForResponse((response) =>
      response.url().includes('/api/v1/markets/overview') && response.request().method() === 'GET'),
    page.goto(`${baseURL}/market`),
  ])
  assert.equal(marketResponse.status(), 200)
  await page.getByRole('heading', { name: '市场广度', exact: true }).waitFor()

  await page.goto(`${baseURL}/watchlist`)
  await page.waitForURL(/\/login\?redirect=/)
  await page.getByLabel('手机号或用户名').fill(username)
  await page.getByLabel('登录密码').fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.waitForURL((url) => url.pathname === '/watchlist')

  const refreshCookie = (await context.cookies()).find((cookie) => cookie.name === 'refresh_token')
  assert.equal(refreshCookie?.httpOnly, true)
  assert.equal(refreshCookie?.sameSite, 'Strict')

  await page.reload()
  await page.getByTestId('logout-button').waitFor()
  await page.getByTestId('logout-button').click()
  await page.waitForURL('**/market')
  assert.equal((await context.cookies()).some((cookie) => cookie.name === 'refresh_token'), false)

  await page.goto(`${baseURL}/watchlist`)
  await page.waitForURL(/\/login\?redirect=/)
  console.log('Playwright 真实纵向验收通过：市场 API、登录、Cookie 恢复、退出与路由保护')
} finally {
  await browser.close()
}
