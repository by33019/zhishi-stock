# 知势平台 MVP 交付路线图 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把当前"文档完备、前端原型可跑、后端首个纵向切片悬在分支"的中间态，收敛为**可重复构建、可自动回归、主干即真实**的工程基线（M1），随后按纵向切片逐个把游客主流程与用户态流程变成真实链路（M2/M3）。

**Architecture:** 沿用既有分层，不引入新架构。后端继续 Spring Boot 3.5 + 6 模块 Maven 多模块；外部依赖（行情源、资讯源、LLM）一律通过 `QuoteProvider` 式可插拔接口 + 确定性模拟实现落地，真实适配器后置；前端继续"服务层替换、页面结构不动"的接入方式。

**Tech Stack:** Vue 3 / TypeScript / Vite / Vitest / Playwright / Spring Boot 3.5 / Java 17 / MyBatis-Plus / MySQL 8.4 / Redis 8.2 / Flyway / Docker Compose / GitHub Actions。

**决策依据（用户 2026-09-19 确认）:**
1. slice 分支完成后合入 main —— 同意。
2. 工作区与是否直改 main —— 由我决定；允许直改 main。
3. 沿用 `docs/superpowers/{specs,plans}` 的 spec → plan → 实现流程。
4. Docker Desktop 可启动。
5. 先修 mvn 脚本；允许用 `-Dmaven.repo.local=backend/.m2-cache` 复用已有本地仓库。
6. 允许运行构建与测试（写 `target/`、`dist/`、`.cache/`）。
7. 数据库用 Docker 提供；**V1–V8 从未在任何真实库执行过**；`stock_db.sql` 需导入为基线。
8. 真实行情源、资讯源、LLM Provider **均未就位**，继续以模拟 Provider 开发。
9. 优先级 = **(a) 先合流 + 工程化**。
10. 完成标准可接受"分阶段可发布"。
11. 严格执行 TDD（先红后绿）。
12. 建立 CI；允许推送远端。
13. 无额外后端规范，遵循 AGENTS.md。
14. `element-plus` 去留由我决定 → **移除**（全仓库零引用，且与自建设计系统冲突）。

---

## 关键前置核查结论（已实测）

| 结论 | 证据 |
| --- | --- |
| slice 分支可**零冲突快进**合入 main | `git merge-base --is-ancestor main codex/auth-market-vertical-slice` → 成立；main=30868dc，slice=010ff60 |
| GitHub 远端连通正常 | `git ls-remote origin` 成功；`origin/main` = 9beb263 |
| 本地 main 领先远端 1 个提交 | `30868dc 工程：忽略本地工作树目录` 未推送 |
| slice 分支**仅存在于本地** | `git branch -a` 无 `remotes/origin/codex/auth-market-vertical-slice` |
| 后端 45 个测试上次全绿 | surefire 报告，2026-09-14 16:59 |
| 前端 main 20 测试 / slice 35 测试全绿 | 本次实测 `vitest --run` |
| `mvn`（bash 脚本）失败根因已定位 | 见 M1-01 |
| Docker daemon 当前未运行 | `docker info` 连接 npipe 失败 |
| `gh` CLI 未安装 | CI 仍可建（只需 `.github/workflows/`，不依赖 gh） |

### M1-01 根因（已实测确认）

`uname` 返回 `MINGW64_NT-10.0-26100` → 脚本判定 `mingw=true`、`cygwin=false`。脚本的 MinGW 分支**只把路径转成 Unix 格式**（`MAVEN_HOME=$(cd ...; pwd)`），且**没有**在调用 `java` 前转回 Windows 格式的分支（源码中残留 `# TODO classpath?`）。于是 `-classpath /d/maven/.../plexus-classworlds-2.9.0.jar` 被直接传给原生 Windows `java.exe`，解析失败 → `ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher`。

验证：手工以 Windows 风格 classpath 调用同一 jar，`ClassNotFoundException` 消失（推进到 `multiModuleProjectDirectory` 提示）。**根因成立。**

---

## 里程碑

### M1：合流与工程地基（用户指定优先）

**出口标准**：`origin/main` 包含后端全量代码；一条命令可复现全栈；CI 在推送与 PR 上自动跑绿；`TASKS.md` / `PROJECT_STATUS.md` 成为唯一任务真相源。

| ID | 任务 | 优先级 | 依赖 | 涉及文件 | 验收标准 | 测试方式 | 预估 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| M1-01 | 修复 Git Bash 下 mvn 启动失败 | P0 | — | `/d/maven/apache-maven-3.9.10-bin/apache-maven-3.9.10/bin/mvn`（本机，非仓库） | bash 中 `mvn -v` 输出 Maven 3.9.10 | `mvn -v` | 0.2d |
| M1-02 | 提交 worktree 的 2 处未提交改动 | P0 | — | `compose.yaml`、`frontend/e2e/auth-market.real.mjs` | worktree `git status` 干净 | `git status` | 0.1d |
| M1-03 | 本地复跑后端全量测试（含 Testcontainers） | P0 | M1-01、Docker 启动 | `backend/**` | 45 个测试全绿 | `mvn -f backend/pom.xml test` | 0.3d |
| M1-04 | 数据库基线决策并落地 | P0 | M1-03 | `sql/**`、`compose.yaml`、`.env` | 见下方"待你拍板的决策 1" | `docker-compose up flyway` + `checks/post_migration_validation.sql` | 0.5d |
| M1-05 | 全栈 Compose 端到端验收 | P0 | M1-04 | `compose.yaml`、`frontend/e2e/auth-market.real.mjs` | `http://localhost:8088/market` 显示真实 API 数据；真实 e2e 通过 | `docker-compose up -d --build` + `npm run e2e:real` | 0.5d |
| M1-06 | 合流 slice → main 并推送 | P0 | M1-03、M1-05 | 全仓库 | `origin/main` 含后端；工作树删除 | `git push` + 远端核对 | 0.2d |
| M1-07 | 建立 CI（GitHub Actions） | P0 | M1-06 | `.github/workflows/ci.yml` | push/PR 触发；后端、前端、SQL 三个作业绿 | 观察 Actions 运行结果 | 0.5d |
| M1-08 | 建立 TASKS.md / PROJECT_STATUS.md / CHANGELOG.md | P1 | — | 根目录 3 个文件 | 与路线图一致，可逐任务勾选 | 人工核对 | 0.3d |
| M1-09 | 文档同步与补全 | P1 | M1-06 | `docs/RESTful-API.md`、`README.md`、`backend/README.md`、`frontend/.env.example` | main 上文档与代码一致；新成员按 README 能从零起服务 | 按 README 走一遍 | 0.5d |
| M1-10 | 移除未使用的 element-plus | P2 | — | `frontend/package.json`、`package-lock.json` | 构建与测试仍绿 | `npm run build`、`vitest --run` | 0.2d |
| M1-11 | 修复 AppShell 测试的 router 注入警告 | P2 | — | `frontend/src/layouts/AppShell.test.ts` | 测试输出无 `[Vue warn]` | `vitest --run` | 0.2d |

### M2：市场域纵向补全（让游客主流程全真实）

**出口标准**：`/market`、`/rankings`、`/sectors`、`/sectors/:id`、`/stocks/:id` 五个游客页面全部走真实 API，无 `mockApi` 调用；每个接口有契约测试。

| ID | 任务 | 优先级 | 依赖 | 涉及文件 | 验收标准 | 测试方式 | 预估 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| M2-01 | 交易日历与市场状态（MKT-02） | P0 | M1-06 | `stock-market/**`、`MarketController`、V3 表 | 正确返回开市/竞价/午休/收盘；非交易日可判 | 单测 + 契约测试 | 1.0d |
| M2-02 | 市场广度（MKT-03） | P0 | M2-01 | `stock-market/**` | 同一快照口径；涨跌停按规则计数，不混批次 | 单测 + 契约测试 | 0.5d |
| M2-03 | 成交趋势（MKT-04） | P0 | M2-01 | `stock-market/**` | TODAY/5D/20D 三档，含 `unit` 与 `dataCutoffAt` | 单测 + 契约测试 | 1.0d |
| M2-04 | 证券主数据与搜索建议 | P0 | M2-01 | `stock-market/**`、V3 表 | 六位代码 + 交易所唯一；搜索 P95 < 500ms | 单测 + 契约测试 | 1.0d |
| M2-05 | 个股快照与日/周/月 K 线 | P0 | M2-04 | `stock-market/**` | 周/月由日 K 按交易日历聚合；量=股、额=元 | 单测 + 契约测试 | 1.5d |
| M2-06 | 榜单（涨跌幅/成交额/换手）+ 分页筛选 | P0 | M2-04 | `stock-market/**` | 与 `RESTful-API.md` 第 9 章逐字段一致 | 单测 + 契约测试 | 1.0d |
| M2-07 | 板块排行、详情与成分股 | P1 | M2-04 | `stock-market/**` | 与第 10 章一致；含领涨股 | 单测 + 契约测试 | 1.5d |
| M2-08 | 前端接入 rankings / sectors / sectors:id / stocks:id | P0 | M2-05、M2-06、M2-07 | `frontend/src/pages/*`、`services/marketApi.ts` | 4 个页面零 Mock 调用；具备加载/错误/重试/降级态 | vitest + e2e | 1.5d |
| M2-09 | 全局搜索接真实接口 | P1 | M2-04 | `components/GlobalSearch.vue` | 建议项可跳转真实个股页 | vitest | 0.5d |

### M3：用户态闭环与 AI 研究编排

**出口标准**：自选、资讯、AI 研究、历史四条链路前后端打通；外部依赖全部以模拟 Provider 落地，真实适配器只需替换实现类。

| ID | 任务 | 优先级 | 依赖 | 验收标准 | 测试方式 | 预估 |
| --- | --- | --- | --- | --- | --- | --- |
| M3-01 | 自选分组 CRUD（后端，V5 表） | P0 | M2-04 | 每用户 ≤10 组；默认组不可删；用户级隔离 | 单测 + 契约测试 | 1.0d |
| M3-02 | 自选项 CRUD + 排序 + 行情概览 | P0 | M3-01 | 每组 ≤100；同组同证券唯一；概览含截止时间与状态 | 单测 + 契约测试 | 1.0d |
| M3-03 | 前端 watchlist 接真实 API | P0 | M3-02 | 页面零 Mock；越权返回 403/404 且前端正确提示 | vitest + e2e | 1.0d |
| M3-04 | 资讯 Provider 抽象 + 模拟源 + 去重 + 标的关联（V4 表） | P0 | M2-04 | 来源 ID 或内容指纹幂等；低置信关联不进 AI 证据 | 单测 + 契约测试 | 1.5d |
| M3-05 | 前端 news 接真实 API | P1 | M3-04 | 页面零 Mock；展示来源校验状态与关联标的 | vitest | 0.8d |
| M3-06 | AI Provider 抽象 + 确定性模拟实现 | P0 | M3-04 | 接口与真实 LLM 同构，替换实现类即可切换 | 单测 | 1.0d |
| M3-07 | AI 任务编排 + SSE 流式契约（V6 表） | P0 | M3-06 | 事件契约与 `RESTful-API.md` 13.4 一致；支持取消与 60s 超时 | 单测 + 契约测试 | 2.0d |
| M3-08 | AI 报告/证据/反馈持久化 | P0 | M3-07 | 固定章节；证据编号报告内唯一；引用失效保留元数据不静默隐藏 | 单测 + 契约测试 | 1.5d |
| M3-09 | AI 配额与用量统计 | P1 | M3-07 | 超限返回业务错误码；不存密钥与无关个人信息 | 单测 | 0.8d |
| M3-10 | 前端 ai 工作台 + history 接真实 API/SSE | P0 | M3-08 | 流式渲染；取消/重试/证据与风险强制展示 | vitest + e2e | 2.0d |
| M3-11 | 后台 admin 最小集（用户/健康/任务/用量） | P1 | M3-09 | RBAC 生效；敏感字段脱敏；写操作入审计 | 单测 + 契约测试 | 2.0d |
| M3-12 | 热点榜单 Excel 导出 | P1 | M2-06 | ≤5000 行；含口径与免责声明 | 单测 | 1.0d |

---

## 建议执行顺序

```
M1-01 ─┬─ M1-03 ─┬─ M1-04 ─ M1-05 ─ M1-06 ─ M1-07 ─ M1-09
M1-02 ─┘         │
                 └─ M1-08 ─ M1-10 ─ M1-11
M1-06 ─→ M2-01 ─┬─ M2-02
                ├─ M2-03
                └─ M2-04 ─┬─ M2-05 ─┬─ M2-08 ─ M2-09
                          ├─ M2-06 ─┘
                          └─ M2-07 ─┘
M2-04 ─→ M3-01 ─ M3-02 ─ M3-03
M2-04 ─→ M3-04 ─┬─ M3-05
                └─ M3-06 ─ M3-07 ─┬─ M3-08 ─ M3-10
                                  └─ M3-09 ─ M3-11
M2-06 ─→ M3-12
```

**排序理由**
1. **M1 先行**：当前最大的成本不是缺功能，而是"改完无法验证"（Docker 未起、mvn 坏、无 CI、任务无载体）。先把验证能力恢复，后面每个任务才有反馈回路。
2. **M1-06 早合流**：已确认可零冲突快进，拖延只会扩大分叉面。
3. **M2 早于 M3**：市场域复用已有的 `QuoteProvider → Ingestion → Redis/MySQL → Query` 链路，**不依赖任何未就位的外部依赖**，是投入产出比最高的一段；完成后"游客主流程"即全真实。
4. **M3-04 早于 M3-06**：AI 的证据链依赖资讯关联，先有资讯再有 AI，避免 AI 报告无据可引。
5. **M3-03 早于 M3-04**：自选是纯内部逻辑（无外部依赖），风险最低，可作为"用户态 + 权限隔离"的验证样板。

---

## 暂不做什么，为什么

| 不做 | 原因 |
| --- | --- |
| 真实行情源适配器 | 授权源未就位（用户确认）。只保留 `QuoteProvider` 接口，模拟实现继续供数 |
| 真实 LLM Provider 接入 | Provider 未选定（用户确认）。只做同构接口 + 确定性模拟实现 |
| Python + FastAPI 拆分 AI 服务 | PRD 明确延后至 V1.2；提前拆分会增加运维成本而无收益 |
| WebSocket 实时行情推送 | 依赖真实行情源；MVP 阶段前端已有轮询/SSE 可用，架构设计保留不落地 |
| 移动端专项适配 | PRD 10.2 明确不在 MVP 范围 |
| 分钟级表分区 / 冷热分离 | `sql/README.md` 明确属运维容量变更，须独立 Flyway 版本发布，不在应用启动时执行 |
| 交易 / 回测 / 财务估值 / 多租户 / 商业化 | PRD 10.2 明确非目标 |
| 升级任何依赖 | 项目约束：未经确认不升级依赖 |
| 改动既有表结构 | 优先复用 V1–V8 已建表；如确需变更，必须新开 Flyway 版本并先取得确认 |
| 大规模重构 | 项目约束：未经确认不做大规模重构、不删文件 |

---

## 待你拍板的决策

### 决策 1：数据库基线走哪条路（影响 M1-04）

`sql/README.md` 定义了互斥的两条路，而你的回答（"stock_db.sql 需导入为基线"）指向路径 B：

| 方案 | 做法 | 优点 | 缺点 |
| --- | --- | --- | --- |
| **A. 空库全量 Flyway**（README 推荐用于空库初始化） | 建空 schema → Flyway V1→V8 | 环境可重复、可 CI、无需 24MB 数据导入 | 与"已有库升级"路径不同，升级路径未被验证 |
| **B. 导入 stock_db.sql + baseline**（你选择的） | 建库 → 导入 24MB SQL → `baselineOnMigrate=true, baselineVersion=1` → V2→V8 | 贴近真实升级场景；旧表有数据 | 导入耗时；V2 加固脚本要处理真实脏数据（重复邮箱等 blocking 项）；本地环境不易重复 |

**我的建议：两条都做，但分工不同。**
- **CI 与日常开发用 A**（可重复、快，作为回归基线）。
- **M1-04 额外用 B 做一次"升级演练"**：验证 `preflight_existing_schema.sql` 能否正确报出 blocking 项、V2 能否在真实数据上通过、`post_migration_validation.sql` 是否干净。演练结果写入 `PROJECT_STATUS.md`。

这样既满足你"导入 stock_db.sql"的要求，又不牺牲可重复性。**请确认是否采用这个折中方案。**

### 决策 2：合流方式

已确认可快进。建议：先推送 `codex/auth-market-vertical-slice` 到 origin，再快进 main 并推送，保留原有 3 条中文提交记录，不制造无意义的合并提交。**若你希望保留 PR 审查记录**，我改为推送分支后由你在 GitHub 开 PR（本机无 `gh` CLI，我无法代开）。

### 决策 3：CI 触发与门禁

建议 CI 三个作业：`backend`（`mvn verify`，Testcontainers 在 GitHub 上可直接跑）、`frontend`（`typecheck` + `vitest --run` + `build`）、`sql`（`pwsh -File sql/tests/validate_migrations.ps1`）。先设为**非阻塞**（跑绿即通知，不阻断合并），稳定两周后再提升为必需检查。**如需直接设为必需检查请告知。**

---

## 阶段 2 执行约定（每个任务循环）

1. 复述任务与验收标准。
2. 列出改动计划、涉及文件、测试命令。
3. **等你确认后**再改代码。
4. 严格执行 TDD：先写失败测试（红）→ 实现（绿）→ 重构。
5. 修改后运行测试或给出验证步骤。
6. 更新 `TASKS.md` 与 `PROJECT_STATUS.md`。
7. 给出 Git commit 建议（`类型：中文描述`）。
