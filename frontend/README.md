# 知势前端

AI 智能股票分析平台的 Vue 3 前端。11 个路由页面均已实现；其中 `/market`（市场总览）与 `/login`（登录）已接入真实后端 API，其余页面仍使用 `src/services/mockApi.ts` 提供的演示数据，接口结构对齐 `docs/RESTful-API.md`。

## 技术栈

- Vue 3.5 · TypeScript 6 · Vite 8
- Vue Router 5 · Pinia 4
- ECharts 6 · Lucide Vue · Manrope / 中文衬线字体
- Vitest 5 + Vue Test Utils · Playwright

## 环境变量

```bash
cp .env.example .env.local     # .env.local 已被 .gitignore 忽略
```

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `VITE_API_BASE_URL` | `/api/v1` | 后端接口前缀。容器部署下由 nginx 反代，无需修改 |

> ⚠️ 只有 `VITE_` 前缀的变量会注入客户端代码，**不要**在此填写任何密钥。

## 本地运行

```bash
npm install
npm run dev
```

默认访问 `http://localhost:5173/market`。

> ⚠️ **已知限制（见 `TASKS.md` M1-16）**：`npm run dev` 目前无法取到真实接口数据——
> 开发服务器未配置代理，后端也未开启 CORS，`/api/v1/**` 请求会被 Vite 的 SPA fallback 返回 `index.html`。
> 需要查看真实数据时请使用容器路径：在仓库根目录执行 `docker-compose up -d --build`，
> 然后访问 `http://localhost:8088/market`。

## 质量检查

```bash
npm run typecheck                          # vue-tsc + tsc
npx vitest --configLoader runner --run     # 单元测试（13 文件 / 35 测试）
npm run build                              # 生产构建
```

> `--configLoader runner` 参数是必需的：项目的 Vite 配置依赖它才能正确加载。

端到端验收（需先启动容器栈）：

```bash
E2E_BASE_URL=http://127.0.0.1:8088 npm run e2e:real
```

`e2e/auth-market.real.mjs` 覆盖完整纵向链路：市场 API 取数 → 未登录访问受保护路由被拦截 →
登录 → refresh cookie 属性校验（`httpOnly` / `SameSite=Strict`）→ 刷新页面后会话恢复 →
退出登录 → 再次被路由保护拦截。

另有 `npm run e2e:ui`（`e2e/auth-market.mjs`）用于纯前端 UI 验收。

## 核心路由

| 路由 | 页面 | 访问级别 | 数据来源 |
| --- | --- | --- | --- |
| `/market` | 市场总览 | 游客 | **真实 API** |
| `/rankings` | 行情榜单 | 游客 | Mock |
| `/sectors` | 板块分析 | 游客 | Mock |
| `/sectors/:id` | 板块详情 | 游客 | Mock |
| `/stocks/:id` | 个股详情 | 游客 | Mock |
| `/news` | 资讯中心 | 游客 | Mock |
| `/watchlist` | 我的自选 | 登录用户 | Mock |
| `/ai` | AI 研究工作台 | 登录用户 | Mock |
| `/history` | 分析历史 | 登录用户 | Mock |
| `/login` | 登录 | 游客 | **真实 API** |
| `/admin` | 系统运营 | 管理员 | Mock |

`/` 与未匹配路径均重定向到 `/market`。

## 视觉约定

- A 股行情遵循**红涨绿跌**。
- 主要背景采用暖纸色，导航使用深墨绿，AI 能力使用克制的金色提示。
- 标题使用中文衬线字体，数字和操作界面使用 Manrope。
- 行情必须展示**数据截止时间**。
- AI 结论必须同时呈现**证据、数据截止时间、风险与免责声明**。

## 设计交付

Figma 文件：[知势 AI 智能股票分析平台 · UI V1.0](https://www.figma.com/design/b7SDW8OkkKgyfkRyfb1vE0)

浏览器验收截图位于 `output/playwright/`。
