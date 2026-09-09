# 登录页一体化重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有深浅对半分栏登录页重构为统一暖色研究画布，在保留品牌叙事的同时突出登录任务并完善响应式与无障碍体验。

**Architecture:** 继续使用单一 `LoginPage.vue` 和现有 `BrandMark.vue`，只重组页面语义结构及页面本地状态，不引入新的组件层或状态管理。登录页视觉规则集中替换 `business.css` 中现有登录样式，Playwright 脚本新增可计算的统一背景、控件尺寸和移动布局断言。

**Tech Stack:** Vue 3、TypeScript、Lucide Vue、CSS、Vitest、Vue Test Utils、Playwright、Vite。

---

## 文件结构

- 修改 `frontend/src/pages/LoginPage.vue`：统一画布页面结构、密码可见状态和无障碍名称。
- 修改 `frontend/src/pages/LoginPage.test.ts`：验证统一结构、关键内容、市场入口和密码切换行为。
- 修改 `frontend/src/styles/business.css`：替换旧登录页分栏样式，新增统一背景、卡片、动效和响应式规则。
- 修改 `frontend/scripts/visual-check.mjs`：增加登录页桌面与移动端布局、字号和控件尺寸验收。

### Task 1: 重构登录页语义结构与交互

**Files:**
- Modify: `frontend/src/pages/LoginPage.test.ts`
- Modify: `frontend/src/pages/LoginPage.vue`

- [ ] **Step 1: 写入统一画布和密码切换失败测试**

将 `frontend/src/pages/LoginPage.test.ts` 替换为：

```ts
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import LoginPage from './LoginPage.vue'

function mountLoginPage() {
  return mount(LoginPage, {
    global: {
      stubs: {
        RouterLink: {
          props: ['to'],
          template: '<a :href="to"><slot /></a>',
        },
      },
    },
  })
}

describe('登录页', () => {
  it('在统一研究画布中组织品牌叙事与登录任务', () => {
    const wrapper = mountLoginPage()

    expect(wrapper.find('.login-canvas').exists()).toBe(true)
    expect(wrapper.find('.login-form-wrap').exists()).toBe(false)
    expect(wrapper.get('.login-atmosphere').attributes('aria-hidden')).toBe('true')
    expect(wrapper.findAll('h1')).toHaveLength(1)
    expect(wrapper.text()).toContain('让每个判断')
    expect(wrapper.text()).toContain('登录研究工作台')
    expect(wrapper.text()).toContain('AI 仅提供研究辅助')
    expect(wrapper.get('.login-back').attributes('href')).toBe('/market')
    expect(wrapper.find('input[autocomplete="username"]').exists()).toBe(true)
    expect(wrapper.find('input[autocomplete="current-password"]').exists()).toBe(true)
  })

  it('切换密码可见状态并同步可访问名称', async () => {
    const wrapper = mountLoginPage()
    const passwordInput = wrapper.get('input[autocomplete="current-password"]')
    const visibilityButton = wrapper.get('[data-testid="password-visibility"]')

    expect(passwordInput.attributes('type')).toBe('password')
    expect(visibilityButton.attributes('aria-label')).toBe('显示密码')

    await visibilityButton.trigger('click')

    expect(passwordInput.attributes('type')).toBe('text')
    expect(visibilityButton.attributes('aria-label')).toBe('隐藏密码')
  })
})
```

- [ ] **Step 2: 运行测试并确认旧页面无法满足新结构**

Run: `cd frontend && npm test -- --run src/pages/LoginPage.test.ts`

Expected: FAIL，错误信息包含无法找到 `.login-canvas` 或 `[data-testid="password-visibility"]`。

- [ ] **Step 3: 实现统一画布模板和密码切换语义**

将 `frontend/src/pages/LoginPage.vue` 替换为：

```vue
<script setup lang="ts">
import { ArrowRight, Check, Eye, EyeOff, LockKeyhole, ShieldCheck, UserRound } from '@lucide/vue'
import { ref } from 'vue'

import BrandMark from '@/components/BrandMark.vue'

const showPassword = ref(false)
</script>

<template>
  <main class="login-page login-canvas">
    <div class="login-atmosphere" aria-hidden="true">
      <svg class="login-market-line" viewBox="0 0 1440 620" preserveAspectRatio="none">
        <path d="M-60 462 C 160 430, 238 502, 420 414 S 720 272, 900 326 S 1160 452, 1500 236" />
        <circle cx="420" cy="414" r="5" />
        <circle cx="900" cy="326" r="5" />
      </svg>
      <span class="login-orbit login-orbit--large" />
      <span class="login-orbit login-orbit--small" />
    </div>

    <header class="login-topbar">
      <BrandMark />
      <RouterLink class="login-back" to="/market">
        先浏览市场
        <ArrowRight :size="15" />
      </RouterLink>
    </header>

    <section class="login-stage">
      <article class="login-story">
        <div class="login-story__content">
          <span class="eyebrow">AI STOCK RESEARCH</span>
          <h1>让每个判断，<em>都有证据。</em></h1>
          <p>从行情、板块到事件线索，知势帮助你更快理解市场，而不是替你做决定。</p>
        </div>
        <ul class="login-capabilities" aria-label="平台能力">
          <li><Check :size="14" />持续跟踪研究标的</li>
          <li><Check :size="14" />生成带引用的 AI 分析</li>
          <li><Check :size="14" />沉淀个人研究历史</li>
        </ul>
      </article>

      <form class="login-form login-card" @submit.prevent>
        <header class="login-form__header">
          <span class="eyebrow">WELCOME BACK</span>
          <h2>登录研究工作台</h2>
          <p>还没有账号？<a href="#">免费注册</a></p>
        </header>

        <label>
          手机号或用户名
          <div class="login-input">
            <UserRound :size="17" />
            <input type="text" placeholder="请输入手机号或用户名" autocomplete="username" />
          </div>
        </label>

        <label>
          登录密码
          <div class="login-input">
            <LockKeyhole :size="17" />
            <input :type="showPassword ? 'text' : 'password'" placeholder="请输入密码" autocomplete="current-password" />
            <button
              data-testid="password-visibility"
              type="button"
              :aria-label="showPassword ? '隐藏密码' : '显示密码'"
              @click="showPassword = !showPassword"
            >
              <EyeOff v-if="showPassword" :size="17" />
              <Eye v-else :size="17" />
            </button>
          </div>
        </label>

        <div class="form-meta">
          <label><input type="checkbox" /> 保持登录</label>
          <a href="#">忘记密码？</a>
        </div>

        <button class="login-submit" type="submit">
          登录
          <ArrowRight :size="17" />
        </button>
        <p class="login-agreement">登录即表示你同意<a href="#">《用户协议》</a>和<a href="#">《隐私政策》</a></p>
      </form>
    </section>

    <footer class="login-trust">
      <span><ShieldCheck :size="16" /> 数据传输加密</span>
      <span>AI 仅提供研究辅助，不构成投资建议</span>
    </footer>
  </main>
</template>
```

- [ ] **Step 4: 运行组件测试确认结构和交互通过**

Run: `cd frontend && npm test -- --run src/pages/LoginPage.test.ts`

Expected: `1` 个测试文件、`2` 条测试全部 PASS。

- [ ] **Step 5: 提交结构与测试**

```bash
git add frontend/src/pages/LoginPage.vue frontend/src/pages/LoginPage.test.ts
git commit -m "重构：统一登录页内容结构"
```

### Task 2: 实现暖色研究画布视觉与响应式

**Files:**
- Modify: `frontend/scripts/visual-check.mjs`
- Modify: `frontend/src/styles/business.css`

- [ ] **Step 1: 为桌面登录页增加可计算的视觉断言**

在 `frontend/scripts/visual-check.mjs` 的桌面登录页截图前加入：

```js
await inspectTypography(desktopPage, '/login', [
  { label: '登录品牌标题', selector: '.login-story h1', min: 40, max: 48 },
  { label: '登录品牌描述', selector: '.login-story__content > p', min: 15 },
  { label: '登录表单标题', selector: '.login-form h2', min: 28, max: 34 },
  { label: '登录表单标签', selector: '.login-form > label', min: 13 },
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
```

- [ ] **Step 2: 运行视觉检查确认旧样式不能满足新断言**

Run: `cd frontend && node scripts/visual-check.mjs`

Expected: FAIL，错误信息包含“登录品牌区仍存在独立背景”或“登录卡片宽度不符合设计”。

- [ ] **Step 3: 替换登录页桌面样式**

将 `frontend/src/styles/business.css` 中从 `.login-page` 到 `.login-form > .login-agreement` 的旧样式替换为：

```css
.login-page { position: relative; isolation: isolate; min-height: 100svh; display: grid; grid-template-rows: auto 1fr auto; overflow: hidden; padding: clamp(24px, 3vw, 42px) clamp(28px, 5vw, 76px) 24px; background: radial-gradient(circle at 78% 18%, rgba(189, 139, 56, 0.16), transparent 27rem), radial-gradient(circle at 12% 82%, rgba(20, 57, 52, 0.09), transparent 30rem), linear-gradient(135deg, #f6f1e7 0%, #eee6d8 100%); color: var(--ink); }
.login-page::before { content: ''; position: absolute; z-index: -2; inset: 0; opacity: 0.34; background-image: linear-gradient(rgba(16, 40, 36, 0.035) 1px, transparent 1px), linear-gradient(90deg, rgba(16, 40, 36, 0.035) 1px, transparent 1px); background-size: 72px 72px; mask-image: linear-gradient(to bottom, transparent, #000 20%, #000 78%, transparent); }
.login-atmosphere { position: absolute; z-index: -1; inset: 0; overflow: hidden; pointer-events: none; }
.login-market-line { position: absolute; left: 0; bottom: 6%; width: 100%; height: min(55vh, 620px); color: rgba(20, 73, 61, 0.17); }
.login-market-line path { fill: none; stroke: currentColor; stroke-width: 2; stroke-dasharray: 1800; animation: login-line-draw 900ms ease-out both; }
.login-market-line circle { fill: var(--paper-raised); stroke: rgba(189, 139, 56, 0.6); stroke-width: 2; }
.login-orbit { position: absolute; border: 1px solid rgba(189, 139, 56, 0.18); border-radius: 50%; }
.login-orbit--large { right: -170px; top: -170px; width: 520px; height: 520px; box-shadow: 0 0 0 70px rgba(189, 139, 56, 0.025), 0 0 0 140px rgba(189, 139, 56, 0.018); }
.login-orbit--small { left: 7%; bottom: -180px; width: 360px; height: 360px; border-color: rgba(20, 73, 61, 0.1); }
.login-topbar,
.login-stage,
.login-trust { width: min(1180px, 100%); margin-inline: auto; }
.login-topbar { display: flex; align-items: center; justify-content: space-between; }
.login-topbar .brand { width: max-content; padding: 0; border: 0; background: transparent; color: var(--ink); }
.login-topbar .brand__mark { border-color: rgba(16, 40, 36, 0.28); }
.login-topbar .brand small { color: var(--ink-faint); }
.login-back { display: inline-flex; align-items: center; gap: 7px; min-height: 40px; padding: 0 14px; border: 1px solid rgba(16, 40, 36, 0.16); border-radius: 999px; background: rgba(255, 253, 247, 0.5); color: var(--ink-soft); font-size: var(--text-label); font-weight: 750; backdrop-filter: blur(8px); transition: border-color 160ms ease, color 160ms ease, transform 160ms ease; }
.login-back:hover { border-color: rgba(16, 40, 36, 0.34); color: var(--ink); transform: translateY(-1px); }
.login-stage { display: grid; grid-template-columns: minmax(0, 1fr) minmax(400px, 430px); align-items: center; gap: clamp(64px, 8vw, 104px); padding: clamp(48px, 8vh, 86px) 0; }
.login-story { min-width: 0; background: transparent; color: var(--ink); animation: login-rise 420ms ease-out both; }
.login-story__content h1 { max-width: 610px; margin: 15px 0 20px; font-family: var(--font-serif); font-size: var(--text-display-title); font-weight: 580; line-height: 1.2; letter-spacing: -0.04em; }
.login-story__content h1 em { color: #a87527; font-style: normal; }
.login-story__content > p { max-width: 540px; margin: 0; color: var(--ink-soft); font-size: var(--text-body-lg); line-height: 1.8; }
.login-capabilities { display: flex; flex-wrap: wrap; gap: 9px; margin: 30px 0 0; padding: 0; list-style: none; }
.login-capabilities li { display: inline-flex; align-items: center; gap: 6px; min-height: 36px; padding: 0 11px; border: 1px solid rgba(16, 40, 36, 0.1); border-radius: 999px; background: rgba(255, 253, 247, 0.48); color: var(--ink-soft); font-size: var(--text-label); font-weight: 650; backdrop-filter: blur(6px); }
.login-capabilities li svg { color: var(--gold); }
.login-card { position: relative; width: 100%; padding: 38px; border: 1px solid rgba(188, 178, 162, 0.7); border-radius: 24px; background: rgba(255, 253, 247, 0.84); box-shadow: 0 28px 70px rgba(36, 49, 43, 0.14); backdrop-filter: blur(18px); animation: login-rise 440ms 100ms ease-out both; }
.login-card::before { content: ''; position: absolute; left: 38px; top: 0; width: 72px; height: 3px; border-radius: 0 0 3px 3px; background: var(--gold); }
.login-form__header h2 { margin: 10px 0 7px; font-family: var(--font-serif); font-size: 31px; font-weight: 620; }
.login-form__header p { margin: 0; color: var(--ink-faint); font-size: var(--text-body); }
.login-form__header a,
.login-agreement a { color: #9a6b22; font-weight: 750; }
.login-form > label { display: block; margin-top: 23px; color: var(--ink-soft); font-size: var(--text-label); font-weight: 750; }
.login-input { height: 50px; display: flex; align-items: center; gap: 9px; margin-top: 8px; padding: 0 13px; border: 1px solid var(--line); border-radius: 11px; background: rgba(255, 253, 247, 0.78); color: var(--ink-faint); transition: border-color 160ms ease, box-shadow 160ms ease, background-color 160ms ease; }
.login-input:hover { border-color: var(--line-strong); }
.login-input:focus-within { border-color: var(--nav-raised); background: var(--white); box-shadow: 0 0 0 3px rgba(189, 139, 56, 0.16); }
.login-form input { min-width: 0; flex: 1; border: 0; outline: 0; background: transparent; color: var(--ink); font-family: inherit; font-size: var(--text-body); }
.login-form input::placeholder { color: #929c98; }
.login-form input:-webkit-autofill { -webkit-text-fill-color: var(--ink); box-shadow: 0 0 0 1000px var(--white) inset; }
.login-input button { width: 32px; height: 32px; display: grid; place-items: center; padding: 0; border: 0; border-radius: 7px; background: transparent; color: var(--ink-faint); cursor: pointer; }
.login-input button:hover { background: var(--paper-muted); color: var(--ink); }
.form-meta { display: flex; align-items: center; justify-content: space-between; margin: 16px 0 24px; color: var(--ink-faint); font-size: var(--text-caption); }
.form-meta label { display: flex; align-items: center; gap: 7px; cursor: pointer; }
.form-meta input { width: 15px; height: 15px; accent-color: var(--nav); }
.form-meta a { color: var(--ink-soft); font-weight: 750; }
.login-submit { width: 100%; height: 48px; display: flex; align-items: center; justify-content: center; gap: 8px; border: 0; border-radius: 11px; background: var(--nav); color: var(--white); font-size: var(--text-body); font-weight: 780; cursor: pointer; box-shadow: 0 10px 24px rgba(11, 41, 38, 0.16); transition: background-color 160ms ease, box-shadow 160ms ease, transform 160ms ease; }
.login-submit:hover { background: var(--nav-raised); box-shadow: 0 14px 28px rgba(11, 41, 38, 0.2); transform: translateY(-1px); }
.login-submit:active { transform: translateY(0); }
.login-back:focus-visible,
.login-input button:focus-visible,
.form-meta a:focus-visible,
.login-submit:focus-visible,
.login-agreement a:focus-visible { outline: 2px solid var(--gold); outline-offset: 3px; }
.login-agreement { margin: 15px 0 0; color: var(--ink-faint); font-size: var(--text-caption); line-height: 1.6; text-align: center; }
.login-trust { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding-top: 18px; border-top: 1px solid rgba(16, 40, 36, 0.1); color: var(--ink-faint); font-size: var(--text-caption); }
.login-trust span { display: inline-flex; align-items: center; gap: 7px; }
.login-trust span:first-child { color: var(--ink-soft); font-weight: 700; }

@keyframes login-rise {
  from { opacity: 0; transform: translateY(14px); }
  to { opacity: 1; transform: translateY(0); }
}

@keyframes login-line-draw {
  from { stroke-dashoffset: 1800; opacity: 0; }
  to { stroke-dashoffset: 0; opacity: 1; }
}
```

- [ ] **Step 4: 替换旧登录响应式规则**

删除 `@media (max-width: 820px)` 中旧的 `.login-page`、`.login-story`、`.login-story__content` 和 `.login-form-wrap` 规则，并在 `business.css` 末尾加入：

```css
@media (max-width: 980px) {
  .login-page { padding-inline: 32px; }
  .login-stage { grid-template-columns: minmax(0, 1fr) minmax(360px, 410px); gap: 36px; }
  .login-story__content h1 { font-size: 40px; }
  .login-capabilities { display: grid; }
}

@media (max-width: 760px) {
  .login-page { min-height: 100svh; overflow-y: auto; padding: 24px; }
  .login-stage { grid-template-columns: 1fr; gap: 30px; width: min(580px, 100%); padding: 42px 0 34px; }
  .login-story__content h1 { margin-block: 12px 15px; font-size: clamp(34px, 10vw, 40px); }
  .login-story__content > p { line-height: 1.7; }
  .login-capabilities { display: flex; margin-top: 22px; }
  .login-capabilities li { min-height: 34px; }
  .login-card { padding: 32px; border-radius: 20px; box-shadow: 0 18px 46px rgba(36, 49, 43, 0.12); }
  .login-trust { width: min(580px, 100%); align-items: flex-start; flex-direction: column; gap: 7px; }
  .login-orbit--large { right: -280px; top: -250px; }
  .login-orbit--small { display: none; }
  .login-market-line { bottom: 19%; opacity: 0.7; }
}

@media (max-width: 480px) {
  .login-page { padding: 18px; }
  .login-topbar .brand small { display: none; }
  .login-back { min-height: 38px; padding-inline: 11px; }
  .login-stage { padding-top: 34px; }
  .login-capabilities { display: grid; grid-template-columns: 1fr 1fr; }
  .login-capabilities li:last-child { grid-column: 1 / -1; }
  .login-card { padding: 27px 22px; border-radius: 18px; }
  .login-card::before { left: 22px; }
  .login-form__header h2 { font-size: 28px; }
}

@media (prefers-reduced-motion: reduce) {
  .login-story,
  .login-card,
  .login-market-line path { animation: none; }
  .login-back,
  .login-submit { transition: none; }
}
```

- [ ] **Step 5: 为移动端登录页增加浏览器验收**

在 `frontend/scripts/visual-check.mjs` 的移动端市场页截图之后、关闭 `mobile` context 之前加入：

```js
await openPage(mobilePage, '/login', '.login-card')
const loginMobileMetrics = await mobilePage.evaluate(() => {
  const stage = document.querySelector('.login-stage')
  const card = document.querySelector('.login-card')
  const submit = document.querySelector('.login-submit')
  if (!stage || !card || !submit) throw new Error('移动端登录结构缺失')

  return {
    stageColumns: window.getComputedStyle(stage).gridTemplateColumns.split(' ').length,
    cardWidth: card.getBoundingClientRect().width,
    submitHeight: submit.getBoundingClientRect().height,
  }
})

if (loginMobileMetrics.stageColumns !== 1) {
  throw new Error(`移动端登录页未切换为单列：${loginMobileMetrics.stageColumns} 列`)
}
if (loginMobileMetrics.cardWidth > 354) {
  throw new Error(`移动端登录卡片超出安全宽度：${loginMobileMetrics.cardWidth}px`)
}
if (loginMobileMetrics.submitHeight < 44) {
  throw new Error(`移动端登录按钮触控高度不足：${loginMobileMetrics.submitHeight}px`)
}
await mobilePage.screenshot({ path: resolve(outputDir, 'login-mobile.png'), fullPage: true })
```

- [ ] **Step 6: 运行组件测试和浏览器视觉检查**

Run: `cd frontend && npm test -- --run src/pages/LoginPage.test.ts`

Expected: `2` 条登录页测试全部 PASS。

Run: `cd frontend && node scripts/visual-check.mjs`

Expected: 命令退出码为 `0`；报告中的 `typographyViolations`、`consoleErrors`、`consoleWarnings`、`browserDiagnostics` 和 `pageErrors` 均为空数组；生成新的 `login-desktop.png` 和 `login-mobile.png`。

- [ ] **Step 7: 人工复核截图**

打开 `frontend/output/playwright/login-desktop.png`，确认整页为统一暖米白画布、品牌区无独立深色背景、登录卡片宽度与层级符合设计。

打开 `frontend/output/playwright/login-mobile.png`，确认品牌主张、登录卡片和信任说明按单列排列，输入框与按钮没有溢出或遮挡。

- [ ] **Step 8: 提交视觉样式与浏览器回归**

```bash
git add frontend/src/styles/business.css frontend/scripts/visual-check.mjs
git commit -m "优化：重构登录页一体化视觉"
```

### Task 3: 完整质量验证与发布检查

**Files:**
- Verify: `frontend/src/pages/LoginPage.vue`
- Verify: `frontend/src/pages/LoginPage.test.ts`
- Verify: `frontend/src/styles/business.css`
- Verify: `frontend/scripts/visual-check.mjs`

- [ ] **Step 1: 运行全部单元测试**

Run: `cd frontend && npm test -- --run`

Expected: 所有测试文件和测试用例全部 PASS，无 Vue 警告。

- [ ] **Step 2: 运行 TypeScript 类型检查**

Run: `cd frontend && npm run typecheck`

Expected: 命令退出码为 `0`，无类型错误。

- [ ] **Step 3: 运行生产构建**

Run: `cd frontend && npm run build`

Expected: Vite 构建成功，生成 `dist`，无构建错误。

- [ ] **Step 4: 运行最终全站浏览器检查**

Run: `cd frontend && node scripts/visual-check.mjs`

Expected: 桌面端全部业务路由、桌面登录页、移动市场页和移动登录页全部完成；无水平溢出、控制台错误、警告、运行时异常和字体违规。

- [ ] **Step 5: 检查提交和工作区状态**

Run: `git status --short --branch`

Expected: `main` 相对远程只包含本次本地提交，工作区没有未暂存文件、临时截图或构建产物。

- [ ] **Step 6: 推送中文提交**

Run: `git push origin main`

Expected: 本次“统一登录页内容结构”和“重构登录页一体化视觉”提交成功推送到 `origin/main`。
