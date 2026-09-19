# TASKS.md — 知势平台任务清单

> 唯一任务真相源。路线图见 `docs/superpowers/plans/2026-09-19-mvp-delivery-roadmap.md`。
> 状态：`[ ]` 待开始 · `[~]` 进行中 · `[x]` 已完成 · `[-]` 已取消
> 优先级：P0 必须 · P1 应该 · P2 可以
> 约定：每个任务严格执行 TDD（先红后绿），完成后更新本文件与 `PROJECT_STATUS.md`。

**最后更新：2026-09-19**

---

## M1：合流与工程地基

- [x] **M1-01** P0 修复 Git Bash 下 mvn 启动失败 — 已完成
  - 根因：`uname` 为 `MINGW64_NT-*` 时脚本判定 `mingw=true`、`cygwin=false`，MinGW 分支只把路径转 Unix 格式，**缺少调用 `java` 前转回 Windows 格式的分支**（源码残留 `# TODO classpath?`）
  - 修复：`bin/mvn` 两处 `if $cygwin` 改为 `if $cygwin || $mingw`；原脚本备份为 `bin/mvn.orig-backup`
  - 验证：`mvn -v` 正常输出 Maven 3.9.10
- [x] **M1-02** P0 提交 worktree 的 2 处未提交改动 — 提交 `6b04c8e`
- [x] **M1-03** P0 本地复跑后端全量测试 — **45 测试全绿**（0 失败 0 错误）
  - 集成测试 42.67s，Testcontainers 真实 MySQL 8.4 + Redis
  - 断言 `flyway_schema_history` 有 **8 条成功迁移** → V1–V8 空库路径已在真实库验证
- [x] **M1-04** P0 数据库基线落地（旧库升级路径）— 已完成，见下方详情
- [x] **M1-05** P0 全栈 Compose 端到端验收 — 已完成，见下方详情
- [x] **M1-06** P0 合流 slice → main 并推送 — 零冲突快进合并，main = `0eeee92`
- [~] **M1-07** P0 建立 CI — 工作流已提交（`.github/workflows/ci.yml`，3 作业）；**分支保护需用户在 GitHub 设置**
- [x] **M1-08** P1 建立 TASKS.md / PROJECT_STATUS.md — 已完成（CHANGELOG.md 见 M1-13）
- [ ] **M1-09** P1 文档同步与补全 — 部分完成（sql/README 已补精简样本说明与 V8 职责）；根 README、backend/README、frontend/.env.example 待补
- [ ] **M1-10** P2 移除未使用的 element-plus — 依赖：无
- [ ] **M1-11** P2 修复 AppShell 测试的 router 注入警告 — 依赖：无

### M1 执行中新发现的任务

- [ ] **M1-12** P1 校验 `preflight_existing_schema.sql` 在旧库升级前的实际行为
  - 该脚本**从未在真实旧库上运行过**（M1-04 直接执行了迁移，未先跑 preflight）
  - 做法：起一个仅导入旧库样本、未执行 Flyway 的 MySQL，运行 preflight，确认能正确报出 blocking 项
- [ ] **M1-13** P2 补建 CHANGELOG.md — 依赖：无
- [ ] **M1-14** P1 在 CI 中增加「旧库升级路径」作业
  - 当前 CI 只覆盖空库全量路径（Testcontainers 集成测试）
  - 建议：起 MySQL → 导入 `sql/stock_db.sql` → Flyway baseline+migrate → 跑 `post_migration_validation.sql`
- [ ] **M1-15** P2 统一本机与文档的 Compose 调用方式
  - 本机 `docker compose` 子命令**不可用**，只有独立命令 `docker-compose`（v5.5.1）
  - 文档需注明两种调用方式

## M2：市场域纵向补全（游客主流程全真实）

- [ ] **M2-01** P0 交易日历与市场状态（MKT-02） — 依赖：M1-06 ✅
- [ ] **M2-02** P0 市场广度（MKT-03） — 依赖：M2-01
- [ ] **M2-03** P0 成交趋势（MKT-04） — 依赖：M2-01
- [ ] **M2-04** P0 证券主数据与搜索建议 — 依赖：M2-01
- [ ] **M2-05** P0 个股快照与日/周/月 K 线 — 依赖：M2-04
- [ ] **M2-06** P0 榜单（涨跌幅/成交额/换手）+ 分页筛选 — 依赖：M2-04
- [ ] **M2-07** P1 板块排行、详情与成分股 — 依赖：M2-04
- [ ] **M2-08** P0 前端接入 rankings / sectors / sectors:id / stocks:id — 依赖：M2-05、M2-06、M2-07
- [ ] **M2-09** P1 全局搜索接真实接口 — 依赖：M2-04

## M3：用户态闭环与 AI 研究编排

- [ ] **M3-01** P0 自选分组 CRUD（后端，V5 表） — 依赖：M2-04
- [ ] **M3-02** P0 自选项 CRUD + 排序 + 行情概览 — 依赖：M3-01
- [ ] **M3-03** P0 前端 watchlist 接真实 API — 依赖：M3-02
- [ ] **M3-04** P0 资讯 Provider 抽象 + 模拟源 + 去重 + 标的关联 — 依赖：M2-04
- [ ] **M3-05** P1 前端 news 接真实 API — 依赖：M3-04
- [ ] **M3-06** P0 AI Provider 抽象 + 确定性模拟实现 — 依赖：M3-04
- [ ] **M3-07** P0 AI 任务编排 + SSE 流式契约（V6 表） — 依赖：M3-06
- [ ] **M3-08** P0 AI 报告/证据/反馈持久化 — 依赖：M3-07
- [ ] **M3-09** P1 AI 配额与用量统计 — 依赖：M3-07
- [ ] **M3-10** P0 前端 ai 工作台 + history 接真实 API/SSE — 依赖：M3-08
- [ ] **M3-11** P1 后台 admin 最小集 — 依赖：M3-09
- [ ] **M3-12** P1 热点榜单 Excel 导出 — 依赖：M2-06

---

## M1-04 交付详情

| 项 | 内容 |
| --- | --- |
| 样本裁剪 | `sql/stock_db.sql` 24,243,860 → 149,480 字节（**0.6%**） |
| 采样规则 | `stock_rt_info` 300/145,382 · `stock_market_index_info` 200/2,822 · `stock_block_rt_info` 60/980；其余 8 张表**完整保留** |
| 裁剪工具 | `sql/tools/slim_legacy_dump.py`（幂等、仅标准库） |
| 完整性证明 | 剔除 INSERT 行后与 `30ed63f` 中的原文件**逐字一致** → DDL / SET / 注释零改动 |
| 编排 | `compose.legacy.yaml`（mysql initdb 挂载 dump + Flyway baseline 参数） |
| 迁移结果 | `Successfully baselined schema with version: 1` → 应用 V2–V8 → `now at version v8`，退出码 0 |
| 校验结果 | `post_migration_validation.sql` 无异常明细，`foreign_key_count = 0`；41 张表（40 平台表 + flyway 历史表） |
| 顺带修复 | 契约漂移：期望表清单补入 V8 的 `market_overview_snapshot`（39 → 40） |

**附带收益**：原 24MB 文件导入会超出 MySQL 健康检查 150 秒窗口导致启动失败；149KB 版本 22 秒即 healthy。

---

## M1-05 交付详情

| 项 | 内容 |
| --- | --- |
| 编排 | `compose.yaml` + `compose.legacy.yaml`，项目名 `zhishi-legacy`（旧库升级路径） |
| 容器 | mysql（healthy）· redis（healthy）· flyway（Exited 0）· stock-api（healthy）· stock-job（Up）· frontend（`0.0.0.0:8088→80`） |
| 镜像构建 | 6 个镜像全部构建成功；stock-api / stock-job / frontend 无 ERROR 日志 |
| API 冒烟 | `GET /api/v1/markets/overview` → **200**，`success:true`，`marketStatus:TRADING`，数据截止时间 `2026-09-19T17:59`，4 指数 + 市场广度（涨 2876/跌 1924/涨停 82）+ 成交额 9826 亿 |
| 端到端验收 | `npm run e2e:real` → **通过**：「市场 API、登录、Cookie 恢复、退出与路由保护」 |
| 验收链路证据 | nginx 日志逐跳确认：overview **200** → watchlist 未登录 `token/refresh` **401** 跳登录 → login **200** → 刷新后 `token/refresh` **200** + `users/me` **200** → logout **200** → 退出后 `token/refresh` **401** |
| 阻塞原因 | 容器内依赖下载中断（后端 Maven / 前端 npm 各命中一种失败形态），已修复 |

### 容器内依赖下载中断（根因与修复）

**根因**：容器网络对境外大流量下载存在约 **3% 的偶发连接中断**（实测并发 12 请求 30 个构件失败 1 个）。单文件下载与 MTU 均正常（MTU=1500，单文件 891KB 可完整下载），问题只在**并发**下出现。一次后端构建需拉取数百个构件，累积失败率接近必然。

| 组件 | 失败形态 | 修复 |
| --- | --- | --- |
| Maven（后端） | `Premature end of Content-Length delimited message body` | 阿里云镜像（`backend/settings.xml`）+ wagon 重试 `count=5` |
| npm（前端） | 直连 `registry.npmjs.org` → `ECONNRESET`；走 npmmirror → `EIDLETIMEOUT`（tarball CDN 连接挂死） | 国内镜像源 + `--maxsockets=5` + 拉长超时 + 外层 3 次重试（不清理 npm 缓存，重试可增量续传） |

> 提交：`1d6f853`。两处修复均对 CI 友好（镜像源为公开源，GitHub Actions 可访问）。

---

## 阻塞项

| 阻塞 | 影响任务 | 需要 |
| --- | --- | --- |
| GitHub 分支保护未设置 | M1-07 的「必需检查」语义 | 用户在仓库 Settings → Branches 配置（本机无 `gh` CLI，无法代设） |
| 本机无 PowerShell 7 | 本地运行 `validate_migrations.ps1` | 仅影响本地；CI 的 ubuntu-latest 预装 pwsh 7，可正常执行 |
| 真实行情/资讯/LLM Provider 未就位 | M2-*、M3-04~M3-10 | 不阻塞开发，统一以模拟 Provider 落地 |

---

## 已完成

- [x] 阶段 0 只读审计（2026-09-19）
- [x] 阶段 1 交付路线图（2026-09-19）
- [x] M1-01 / M1-02 / M1-03 / M1-04 / M1-05 / M1-06（2026-09-19）
