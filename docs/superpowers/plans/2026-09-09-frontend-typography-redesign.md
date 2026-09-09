# 前端字体层级优化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立全局语义化字体体系，降低过大的页面标题并将所有业务文字提升到可舒适阅读的字号区间。

**Architecture:** 以 `tokens.css` 作为唯一字体尺度来源，现有四个样式文件按语义引用 Token，不引入页面缩放或高优先级补丁。Vitest 检查字体 Token 与最小字号契约，Playwright 检查真实浏览器计算字号、响应式溢出和控制台状态。

**Tech Stack:** Vue 3、TypeScript、CSS Custom Properties、Vitest、Playwright、Vite。

---

### Task 1: 建立字体契约测试

**Files:**
- Create: `frontend/src/styles/typography.test.ts`
- Test: `frontend/src/styles/typography.test.ts`

- [x] **Step 1: 编写会失败的字体 Token 与最小字号测试**

```ts
import { describe, expect, it } from 'vitest'
import baseCss from './base.css?raw'
import businessCss from './business.css?raw'
import pagesCss from './pages.css?raw'
import shellCss from './shell.css?raw'
import tokensCss from './tokens.css?raw'

describe('全局字体层级', () => {
  it('提供完整的语义化字体 Token', () => {
    expect(tokensCss).toContain('--text-caption: 12px')
    expect(tokensCss).toContain('--text-label: 13px')
    expect(tokensCss).toContain('--text-body: 14px')
    expect(tokensCss).toContain('--text-body-lg: 15px')
    expect(tokensCss).toContain('--text-card-title: 18px')
    expect(tokensCss).toContain('--text-section-title: 20px')
    expect(tokensCss).toContain('--text-page-title: clamp(36px, 2.6vw, 42px)')
  })

  it('业务样式不再使用低于 12px 的硬编码字号', () => {
    for (const css of [baseCss, shellCss, pagesCss, businessCss]) {
      expect(css).not.toMatch(/font-size:\s*(?:[6-9]|1[01])px/)
    }
  })
})
```

- [x] **Step 2: 运行目标测试并确认失败原因正确**

Run: `npm run test -- --run src/styles/typography.test.ts`

Expected: FAIL，提示缺少 `--text-caption` 等 Token，并命中 `6px` 至 `11px` 的硬编码字号。

- [x] **Step 3: 提交测试基线**

```bash
git add frontend/src/styles/typography.test.ts
git commit -m "测试：增加前端字体层级契约"
```

### Task 2: 建立全局字体 Token 并优化应用壳

**Files:**
- Modify: `frontend/src/styles/tokens.css`
- Modify: `frontend/src/styles/base.css`
- Modify: `frontend/src/styles/shell.css`
- Test: `frontend/src/styles/typography.test.ts`

- [x] **Step 1: 在 `tokens.css` 新增字体尺度**

```css
--text-caption: 12px;
--text-label: 13px;
--text-body: 14px;
--text-body-lg: 15px;
--text-card-title: 18px;
--text-section-title: 20px;
--text-page-title: clamp(36px, 2.6vw, 42px);
--text-display-title: clamp(40px, 3vw, 48px);
```

- [x] **Step 2: 用语义 Token 替换基础样式和应用壳中的低字号**

将品牌副标题、侧栏导航、市场状态、搜索框、顶部按钮、AI 侧栏标题、正文和操作文字分别映射到 `--text-caption`、`--text-label`、`--text-body` 与 `--text-card-title`。`.eyebrow` 使用 `--text-caption`，全局正文基线使用 `--text-body`。

- [x] **Step 3: 运行字体测试，确认仅业务页面样式仍然失败**

Run: `npm run test -- --run src/styles/typography.test.ts`

Expected: FAIL，剩余命中仅来自 `pages.css` 与 `business.css`。

- [x] **Step 4: 提交全局字体基础**

```bash
git add frontend/src/styles/tokens.css frontend/src/styles/base.css frontend/src/styles/shell.css
git commit -m "样式：建立全局字体尺寸体系"
```

### Task 3: 优化市场总览与通用页面字体

**Files:**
- Modify: `frontend/src/styles/pages.css`
- Modify: `frontend/src/styles/business.css`
- Test: `frontend/src/styles/typography.test.ts`

- [x] **Step 1: 调整市场总览与通用页面标题**

将 `.market-lead h1`、`.page-header h1`、`.stock-hero h1`、`.detail-hero h1` 和 `.ai-workspace-head h1` 统一为 `var(--text-page-title)`；页面描述统一为 `var(--text-body-lg)`。

- [x] **Step 2: 调整卡片、表格和行情信息**

模块标题使用 `--text-section-title`，内容标题使用 `--text-card-title`，正文与表格内容使用 `--text-body`，表头、来源、代码、时间和标签使用 `--text-caption` 或 `--text-label`。特殊行情数字保留独立大字号。

- [x] **Step 3: 调整控件尺寸和间距**

主要按钮、输入框、选择器和分段标签文字使用 `--text-body`，辅助操作使用 `--text-label`；必要时增加控件最小高度和卡片内边距，允许内容自然增高。

- [x] **Step 4: 运行字体契约与全量单元测试**

Run: `npm run test -- --run src/styles/typography.test.ts`

Expected: PASS。

Run: `npm run test -- --run`

Expected: 9 个测试文件全部通过。

- [x] **Step 5: 提交业务页面字体优化**

```bash
git add frontend/src/styles/pages.css frontend/src/styles/business.css
git commit -m "优化：统一业务页面字体层级"
```

### Task 4: 增加浏览器字体断言并完成视觉回归

**Files:**
- Modify: `frontend/scripts/visual-check.mjs`
- Modify: `docs/superpowers/plans/2026-09-09-frontend-typography-redesign.md`
- Test: `frontend/scripts/visual-check.mjs`

- [x] **Step 1: 在 Playwright 脚本中增加真实计算字号检查**

增加 AI 页面标题、页面描述、研究设置、表单标签、选择器、问题模板、正文和来源引用的 `getComputedStyle` 采样。标题超过 `42px`、描述低于 `15px`、辅助文字低于 `12px`、正文或控件低于 `14px` 时退出码为 1。

- [x] **Step 2: 运行类型检查和生产构建**

Run: `npm run typecheck`

Expected: PASS。

Run: `npm run build`

Expected: PASS。

- [x] **Step 3: 运行桌面和移动端视觉回归**

Run: `node scripts/visual-check.mjs`

Expected: 页面无水平溢出，`consoleErrors`、`consoleWarnings`、`browserDiagnostics`、`pageErrors` 与 `typographyViolations` 均为空。

- [x] **Step 4: 检查截图并回填计划**

目检 `market-desktop.png`、`market-mobile.png`、`ai-panel-desktop.png`、`ai-report-desktop.png` 与 `login-desktop.png`，确认文本层级清晰且没有截断。将本计划所有完成项改为 `[x]` 并写入验证结果。

**验证结果（2026-09-09）：**

- Vitest：9 个测试文件、15 个测试用例通过。
- TypeScript：`vue-tsc` 与 Node 配置检查通过。
- Vite：生产构建通过，共转换 2482 个模块。
- Playwright：11 个桌面核心路由与 1 个 390px 移动场景通过。
- 字体采样：常规页面标题 `37.44px`、页面描述 `15px`、模块标题 `20px`、正文与控件 `14px`、标签与引用 `12px` 至 `13px`。
- 质量检查：`typographyViolations`、`consoleErrors`、`consoleWarnings`、`browserDiagnostics` 与 `pageErrors` 均为空。
- 人工目检：AI 空状态与报告、市场桌面与移动端、个股详情和登录页层级清晰，无文字截断；移动行情表使用卡片内部滚动。

- [x] **Step 5: 提交验证脚本和计划记录**

```bash
git add frontend/scripts/visual-check.mjs docs/superpowers/plans/2026-09-09-frontend-typography-redesign.md
git commit -m "测试：完善字体可读性浏览器回归"
```

- [x] **Step 6: 推送 GitHub**

Run: `git push origin main`

Expected: 本地 `main` 与 `origin/main` 指向同一提交，工作区干净。
