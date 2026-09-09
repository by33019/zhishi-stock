# 知势前端

AI 智能股票分析平台的 Vue 3 高保真前端原型。当前通过 Mock API 提供完整演示数据，接口结构与 `docs/RESTful-API.md` 保持一致，后续可将 `src/services/mockApi.ts` 替换为真实 Axios、SSE 与 WebSocket 适配器。

## 技术栈

- Vue 3、TypeScript、Vite
- Vue Router、Pinia
- ECharts 6、Lucide Vue
- Vitest、Vue Test Utils、Playwright

## 本地运行

```powershell
npm install
npm run dev
```

默认访问地址为 `http://localhost:5173/market`。

## 质量检查

```powershell
npm run test -- --run
npm run typecheck
npm run build
node scripts/visual-check.mjs
```

运行视觉验收前需先启动开发服务器。验收脚本覆盖桌面市场页、AI 报告、登录页和 `390px` 移动市场页，并检查水平溢出、控制台错误、警告与 ECharts 诊断。

## 核心路由

| 路由 | 页面 | 访问级别 |
| --- | --- | --- |
| `/market` | 市场总览 | 游客 |
| `/rankings` | 行情榜单 | 游客 |
| `/sectors` | 板块分析 | 游客 |
| `/stocks/:id` | 个股研究 | 游客 |
| `/news` | 资讯中心 | 游客 |
| `/watchlist` | 我的自选 | 登录用户 |
| `/ai` | AI 研究工作台 | 登录用户 |
| `/history` | 分析历史 | 登录用户 |
| `/login` | 登录 | 游客 |
| `/admin` | 系统运营 | 管理员 |

## 视觉约定

- A 股行情遵循红涨绿跌。
- 主要背景采用暖纸色，导航使用深墨绿，AI 能力使用克制的金色提示。
- 标题使用中文衬线字体，数字和操作界面使用 Manrope。
- AI 结论必须同时呈现证据、数据截止时间、风险与免责声明。

## 设计交付

Figma 文件：[知势 AI 智能股票分析平台 · UI V1.0](https://www.figma.com/design/b7SDW8OkkKgyfkRyfb1vE0)

浏览器验收截图位于 `output/playwright/`。
