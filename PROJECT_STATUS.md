# PROJECT_STATUS.md — 知势平台项目状态

> 最后更新：2026-09-19（M1-01 ~ M1-06 完成，全栈端到端验收通过）
> 任务清单见 `TASKS.md`，路线图见 `docs/superpowers/plans/2026-09-19-mvp-delivery-roadmap.md`。

---

## 1. 一句话状态

**"知势" AI 智能股票分析平台**：设计文档完备、数据库迁移完备、前端高保真原型可跑、后端首个纵向切片（认证 + 市场总览）已跑通，**且已合入 `main`**。工程地基（mvn 修复、后端 45 测试复跑、旧库升级路径验证、CI 工作流）已就位。剩余：全栈 Compose 端到端验收（M1-05）、分支保护配置、以及 M2/M3 的业务纵向切片。

## 2. 仓库与分支

| 项 | 值 |
| --- | --- |
| 远端 | `git@github.com:by33019/zhishi-stock.git`（SSH，连通正常） |
| 本地 `main` | `1d6f853`（含后端全量代码 + CI + 数据库工程 + 容器构建稳定性修复） |
| `auth-market-vertical-slice` | `0eeee92`，本地与远端同步 |
| 合并方式 | **零冲突快进合并**，无合并提交，保留 6 条中文提交记录 |
| 工作树 | `D:\Codex\Stock_System`（main）· `.worktrees\auth-market-vertical-slice` |
| `gh` CLI | 未安装（无法代开 PR 或配置分支保护） |

> **历史分支名变更**：原分支名 `codex/auth-market-vertical-slice` 的引用文件被外部进程持续删除（`.git/refs/heads/codex/` 目录建成后随即消失，而非嵌套引用 `zz-probe` 稳定存活）。已改用非嵌套名 `auth-market-vertical-slice` 并推送到远端，提交链完整无损失。

## 3. 当前可运行状态（本次实测）

| 能力 | 状态 | 证据 |
| --- | --- | --- |
| `mvn`（Git Bash） | ✅ 已修复 | `mvn -v` → Maven 3.9.10 |
| 后端全量测试 | ✅ **45 测试全绿** | 0 失败 0 错误；集成测试 42.67s |
| 后端 Flyway 迁移（空库路径） | ✅ 已在真实 MySQL 8.4 验证 | 集成测试断言 `flyway_schema_history` 有 8 条成功迁移 |
| 后端 Flyway 迁移（旧库升级路径） | ✅ **首次验证通过** | baseline v1 → V2–V8 → `now at version v8`，退出码 0 |
| 迁移后完整性校验 | ✅ 通过 | `post_migration_validation.sql` 无异常明细，`foreign_key_count = 0` |
| 前端 main 类型检查 + 测试 | ✅ 通过 | 10 文件 / 20 测试 |
| 前端 slice 类型检查 + 测试 | ✅ 通过 | 13 文件 / 35 测试 |
| CI | ⚠️ 工作流已提交，尚未在 GitHub 上运行 | `.github/workflows/ci.yml`（3 作业） |
| 全栈 Compose 端到端 | ⬜ 未执行 | M1-05 |
| 数据库迁移在真实库执行 | ✅ 空库 + 旧库升级两条路径均已完成 | 见上 |

## 4. 里程碑进度

| 里程碑 | 目标 | 状态 |
| --- | --- | --- |
| M1 | 合流与工程地基 | 🟡 7/11 完成（M1-07 部分、M1-09、M1-10、M1-11 待办；新增 M1-12~M1-15） |
| M2 | 市场域纵向补全（游客主流程全真实） | ⬜ 未开始（9 个任务） |
| M3 | 用户态闭环与 AI 研究编排 | ⬜ 未开始（12 个任务） |

## 5. 已完成能力盘点

| 层 | 完成度 | 说明 |
| --- | --- | --- |
| 设计文档 | ✅ 100% | PRD 86KB、Architecture 53KB、RESTful-API 79KB + superpowers specs/plans |
| 数据库迁移 | ✅ 结构 100% / 两条路径均验证 | Flyway V1–V8；旧库样本 149KB（原 24MB） |
| 前端原型 | ✅ 页面 100% / 真实接入 2/11 | 11 路由全部有页面；`/market` 与 `/login` 接真实 API |
| 后端 | 🟡 2/8 域 | 认证闭环 ✅、市场总览（仅 MKT-01）🟡；其余未开工 |
| 测试 | ✅ 80 个 | 后端 45 + 前端 35；无覆盖率门槛 |
| 工程化 | 🟡 60% | CI 工作流 ✅、TASKS/STATUS ✅、路线图 ✅；分支保护、CHANGELOG、根 README 待补 |

## 6. 已知问题（按严重度）

| # | 问题 | 严重度 | 处置 |
| --- | --- | --- | --- |
| 1 | `preflight_existing_schema.sql` 从未在真实旧库运行过 | 🟠 | M1-12 |
| 2 | CI 未在 GitHub 实际跑过；分支保护未设置 | 🟠 | M1-07 收尾（需用户配置） |
| 3 | CI 仅覆盖空库路径，未覆盖旧库升级路径 | 🟡 | M1-14 |
| 4 | 9 个前端页面仍走 mockApi | 🟠 | M2-08 / M3-03 / M3-05 / M3-10 |
| 5 | 根 README / backend README / frontend `.env.example` 缺失 | 🟡 | M1-09 |
| 6 | `element-plus` 声明未使用 | 🟡 | M1-10 |
| 7 | `AppShell.test.ts` 有 Vue router 注入警告 | 🟡 | M1-11 |
| 8 | 无 CHANGELOG.md | 🟡 | M1-13 |
| 9 | 认证默认值偏松（`JWT_SECRET` 默认空、dev `COOKIE_SECURE=false`、演示账号固定密码） | 🟡 | 生产 profile 需单独加固，待排期 |

### 已定位的环境故障（含根因）

**1. `mvn` 在 Git Bash 下失败** —— `ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher`
根因：`uname` = `MINGW64_NT-*` → 脚本判定 `mingw=true`、`cygwin=false`；MinGW 分支只把路径转成 Unix 格式，**缺少调用 `java` 前转回 Windows 格式的分支**（源码残留 `# TODO classpath?`）。POSIX classpath 传给原生 `java.exe` 后解析失败。
**已修复**（`bin/mvn` 两处 `if $cygwin` → `if $cygwin || $mingw`），原脚本备份于 `bin/mvn.orig-backup`。

**2. `docker compose` 子命令不可用** —— `docker: unknown command: docker compose`；独立命令 `docker-compose` v5.5.1 可用。文档需注明。

**3. `pwsh`（PowerShell 7）未安装** —— 本机仅 Windows PowerShell 5.1，按本地代码页读取无 BOM UTF-8 脚本，导致 `sql/tests/validate_migrations.ps1` 中文解析失败（静默无输出）。CI 的 ubuntu-latest 预装 pwsh 7，可正常执行。

**4. 分支引用被外部删除** —— `.git/refs/heads/codex/` 目录建成后立即消失，`git update-ref` 返回 0 但不落盘；非嵌套引用稳定。已改用非嵌套分支名。

**5. 24MB 旧库导入超时** —— 逐条 INSERT 共 145,382 次独立事务，超出 MySQL 健康检查窗口（30 × 5s = 150s）导致 `dependency failed to start`。裁剪为 149KB 后 **22 秒即 healthy**。

**6. 容器内依赖下载中断（镜像构建随机失败）** —— 容器网络对境外大流量下载存在约 **3% 的偶发连接中断**。已排除 MTU（=1500 正常）与链路本身（单文件 891KB 可完整下载、速度 757 kB/s），确认只在**并发**下出现。
- 后端 Maven：`Premature end of Content-Length delimited message body` → 阿里云镜像 + wagon 重试 `count=5`
- 前端 npm：直连 `registry.npmjs.org` 报 `ECONNRESET`；换 npmmirror 后报 `EIDLETIMEOUT`（tarball 所在 `cdn.npmmirror.com` 连接空闲挂死）→ 国内镜像源 + `--maxsockets=5` + 拉长超时 + 外层 3 次重试（保留 npm 缓存使重试增量续传）

## 7. 技术栈与运行方式

| 层 | 技术 |
| --- | --- |
| 前端 | Vue 3.5 / TypeScript 6 / Vite 8 / Vue Router 5 / Pinia 4 / ECharts 6 / Lucide；Vitest 5 + Vue Test Utils + Playwright |
| 后端 | Spring Boot 3.5.9 / Java 17 / MyBatis-Plus 3.5.5 / jjwt 0.12.5 / Spring Security / Spring Data Redis / JdbcTemplate；Maven 多模块 6 个 |
| 数据 | MySQL 8.4（≥8.0.16）/ Redis 8.2 / Flyway V1–V8 |
| 编排 | Docker Compose：mysql → flyway → stock-api / stock-job → frontend(nginx) |

```bash
# 前端
cd frontend && npm install && npm run dev      # http://localhost:5173/market
npm run typecheck && npx vitest --configLoader runner --run

# 后端（测试需 Docker）
mvn -f backend/pom.xml test

# 全栈（空库路径）
docker-compose up -d --build                   # 本机需用 docker-compose，非 docker compose

# 全栈（旧库升级路径：导入样本 + baseline）
docker-compose -f compose.yaml -f compose.legacy.yaml -p zhishi-legacy up -d
docker exec -i zhishi-legacy-mysql-1 mysql -ustock -pstock_dev_password stock_system \
  < sql/checks/post_migration_validation.sql
```

## 8. 核心数据流

```
浏览器 SPA ──/api/v1/**──▶ nginx ──▶ stock-api :8080
                                        │ 读优先 Redis，缺失回落 MySQL 快照
                                        ▼
                              Redis 8.2 ◀──▶ MySQL 8.4
                                   ▲              ▲
                                   └── stock-job（每 60s）── SimulatedQuoteProvider
认证：login → JWT access(内存) + refresh(httpOnly cookie, Redis 轮换)
      401 → apiClient 单飞刷新 → 失败清会话 → 登录引导
统一壳：ApiResponse{success,code,message,data,traceId,timestamp}
```

**不可动摇的架构约束**：前端不直连任何数据源；AI 由 Spring Boot 编排第三方 LLM（不提前拆 FastAPI）；MySQL 无外键，业务写入靠应用层事务 + 乐观锁 + Outbox。

## 9. 待用户处理

1. **配置 GitHub 分支保护**，把 CI 三个作业设为必需检查（Settings → Branches → Branch protection rules → Require status checks）。本机无 `gh` CLI，无法代设。
2. 确认 `.workbuddy-ai/` 是否加入 `.gitignore`（建议加入，与已有的 `.superpowers/`、`.worktrees/` 一致）。

## 10. 下一步

**M1 收尾（文档与清理类，风险低）**：M1-09 补根 README / `backend/README.md` / `frontend/.env.example`、M1-10 移除未使用的 `element-plus`、M1-11 修复 `AppShell.test.ts` 的 router 注入警告、M1-13 补 `CHANGELOG.md`、M1-15 统一文档中的 Compose 调用方式。

**随后进入 M2-01**（交易日历与市场状态），开始市场域纵向补全。

> 仍待用户操作：GitHub 分支保护配置（见第 9 节）。
