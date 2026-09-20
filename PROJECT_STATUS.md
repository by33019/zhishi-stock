# PROJECT_STATUS.md — 知势平台项目状态

> 最后更新：2026-09-20（M2-10 完成：市场状态真实化，关闭已知问题 #7 / #12）
> 任务清单见 `TASKS.md`，路线图见 `docs/superpowers/plans/2026-09-19-mvp-delivery-roadmap.md`。

---

## 1. 一句话状态

**"知势" AI 智能股票分析平台**：设计文档完备、数据库迁移完备、前端高保真原型可跑、后端首个纵向切片（认证 + 市场总览）已跑通并合入 `main`，**且全栈 Compose 端到端验收已通过**（真实 API + 登录 + Cookie 恢复 + 退出 + 路由保护全链路）。工程地基已完备：mvn 修复、数据库两条迁移路径验证、CI 四作业、容器构建稳定性修复、项目文档补全、本地 dev 联调打通。**M1 除「GitHub 分支保护」需用户操作外全部完成**；**M2 全部 10 个任务已完成**（MKT-01~04、STK-01/02/04/07、QTE-01、SEC-01~04/06，以及前端四页接入、全局搜索与市场状态真实化）。市场域已从"首页一张硬编码快照"扩展为可查询的状态 / 广度 / 趋势 / 证券 / 榜单 / 板块六组接口，广度与趋势均由确定性模拟数据按规则真实计算；证券主数据（5149 只）已可搜索与分页筛选；个股详情已可查完整快照与日/周/月 K 线；全市场榜单已可按涨跌幅 / 成交额排序并多条件筛选分页；**板块已可排行、下钻到详情与成分股，且 `sectorId` 在 STK-02 与 QTE-01 上成为真实可用的筛选键**（此前是硬编码空页）；**前端 4 个页面 + 顶栏全局搜索 + 顶栏市场状态已从原型数据切到真实接口**——页面展示的每个数字都能在接口响应里找到来源，没有来源的字段（市盈率、市值、业务描述、板块走势、AI 速览、搜索建议的涨跌幅等）一律显示"尚未实现"而不是保留编造值；**总览快照的 `tradeDate` / `marketStatus` / `dataTime` 已由交易日历推导**，非交易日回退到最近有效收盘并标 `CLOSED`（此前周日会报"今天是交易日、正在交易中"）。

## 2. 仓库与分支

| 项 | 值 |
| --- | --- |
| 远端 | `git@github.com:by33019/zhishi-stock.git`（SSH，连通正常） |
| 本地 `main` | 最新提交见 `CHANGELOG.md`；含后端全量代码、CI、数据库工程、容器构建稳定性修复、项目文档 |
| 远端分支 | **仅 `main`**（`auth-market-vertical-slice` 已完全合入并删除，本地与远端同步） |
| 合并方式 | **零冲突快进合并**，无合并提交，保留 6 条中文提交记录 |
| 工作树 | `D:\Codex\Stock_System`（唯一工作树）；`.worktrees/` 已清理 |
| `gh` CLI | 未安装（无法代开 PR 或配置分支保护） |

> **历史分支名变更**：原分支名 `codex/auth-market-vertical-slice` 的引用文件被外部进程持续删除（`.git/refs/heads/codex/` 目录建成后随即消失，而非嵌套引用 `zz-probe` 稳定存活）。已改用非嵌套名 `auth-market-vertical-slice` 并推送到远端，提交链完整无损失。

## 3. 当前可运行状态（本次实测）

| 能力 | 状态 | 证据 |
| --- | --- | --- |
| `mvn`（Git Bash） | ✅ 已修复 | `mvn -v` → Maven 3.9.10（需 `JAVA_HOME` 用 Windows 路径，如 `D:/idea/JDK17`） |
| 后端全量测试 | ✅ **354 测试通过** | 0 失败 0 错误；另有 1 项 `InfrastructureIntegrationTest`（Testcontainers）在本机因未启动 Docker 守护进程报 `Could not find a valid Docker environment`，CI 可跑 |
| 后端 Flyway 迁移（空库路径） | ✅ 已在真实 MySQL 8.4 验证 | 集成测试断言 `flyway_schema_history` 有 8 条成功迁移 |
| 后端 Flyway 迁移（旧库升级路径） | ✅ **首次验证通过** | baseline v1 → V2–V8 → `now at version v8`，退出码 0 |
| 迁移后完整性校验 | ✅ 通过 | `post_migration_validation.sql` 无异常明细，`foreign_key_count = 0` |
| 前端类型检查 + 测试 + 构建 | ✅ 通过 | **19 文件 / 99 测试**（默认时区与 `TZ=UTC` 下均全绿）；`npm run typecheck` 0 错误；`vite build` 成功 |
| 本地 dev 联调（Vite 代理） | ✅ 已打通 | dev server 下 `GET /api/v1/markets/overview` 返回真实 JSON |
| CI | 🟢 **已在 GitHub 实际运行** | `.github/workflows/ci.yml`（**4 作业**，含旧库升级路径）；前端作业曾因时区依赖持续失败，已修复（`3fd970c`） |
| 全栈 Compose 端到端 | ✅ **已通过** | 6 容器全部启动、无 ERROR；`npm run e2e:real` 通过 |
| 容器镜像构建 | ✅ 稳定可重复 | 修复容器内依赖下载中断后，6 镜像连续构建成功 |
| 数据库迁移在真实库执行 | ✅ 空库 + 旧库升级两条路径均已完成 | 见上 |
| preflight 脚本 | ✅ 已在真实旧库验证（含负向用例） | 9 段查询可执行；注入违规数据后 3 项 blocking 全部检出 |

## 4. 里程碑进度

| 里程碑 | 目标 | 状态 |
| --- | --- | --- |
| M1 | 合流与工程地基 | 🟢 15/16 完成（仅 M1-07 的「GitHub 分支保护」需用户操作） |
| M2 | 市场域纵向补全（游客主流程全真实） | ✅ **10/10 完成**（M2-01 交易日历与市场状态、M2-02 市场广度、M2-03 成交趋势、M2-04 证券主数据与搜索建议、M2-05 个股快照与日/周/月 K 线、M2-06 榜单、M2-07 板块排行/详情/成分股、M2-08 前端四页接入真实接口、M2-09 全局搜索接真实接口、M2-10 市场状态真实化） |
| M3 | 用户态闭环与 AI 研究编排 | ⬜ 未开始（12 个任务） |

## 5. 已完成能力盘点

| 层 | 完成度 | 说明 |
| --- | --- | --- |
| 设计文档 | ✅ 100% | PRD 86KB、Architecture 53KB、RESTful-API 79KB + superpowers specs/plans |
| 数据库迁移 | ✅ 结构 100% / 两条路径均验证 | Flyway V1–V8；旧库样本 149KB（原 24MB） |
| 前端原型 | ✅ 页面 100% / 真实接入 6/11 | 11 路由全部有页面；`/market`、`/login`、`/rankings`、`/sectors`、`/sectors/:id`、`/stocks/:id` 接真实 API；**顶栏全局搜索框已接 STK-01、顶栏市场状态与侧栏数据源已接 MKT-02**（两者都是非路由的全局组件）；`/news` 与 `/watchlist` 仍走 `mockApi`（M3-05 / M3-03），`/ai`、`/history`、`/admin` 尚无数据源 |
| 后端 | 🟡 3/8 域 | 认证闭环 ✅、市场总览（MKT-01~04）🟡、证券主数据与个股行情（STK-01/02/04/07）🟡、榜单（QTE-01）🟡、板块（SEC-01/02/03/04/06）🟡；其余未开工 |
| 测试 | ✅ 453 个 | 后端 354 + 前端 99；无覆盖率门槛 |
| 工程化 | 🟢 85% | CI 工作流 ✅、TASKS/STATUS/CHANGELOG ✅、根与模块 README ✅、容器构建稳定 ✅；分支保护待用户配置 |

## 6. 已知问题（按严重度）

| # | 问题 | 严重度 | 处置 |
| --- | --- | --- | --- |
| 1 | 分支保护未设置（CI 四个作业已实际跑通） | 🟡 | M1-07 收尾（需用户在 GitHub 配置必需检查） |
| 2 | 5 个前端页面尚无真实数据源 | 🟠 | M2-08 已接入 rankings / sectors / sectors:id / stocks:id 四页（真实接入 2/11 → 6/11）；剩余 `/news`、`/watchlist` 仍走 `mockApi`（M3-05 / M3-03），`/ai`、`/history`、`/admin` 是静态原型、连 mock 都没有（M3-10 / M3-11） |
| 3 | 认证默认值偏松（`JWT_SECRET` 默认空、dev `COOKIE_SECURE=false`、演示账号固定密码） | 🟡 | 生产 profile 需单独加固，待排期 |
| 4 | `preflight_existing_schema.sql` 第 8 段永远不会触发（`block_label` 实际是 `varchar(10)`，检查条件为 `> 20`） | 🔵 | 设计上的防御性检查，无实际影响，仅记录 |
| 5 | 独立 `MockMvc` 的日期序列化与线上不一致（`LocalDate` → `[2026,9,11]`） | 🔵 | **已修复**：契约测试显式构造 `ObjectMapper` 关闭 `WRITE_DATES_AS_TIMESTAMPS`。后续新增契约测试需沿用同一 helper，否则日期断言会失真 |
| 6 | PRD 把「分时图」列为 P0（STK-02），但 `TASKS.md` 的 M2 未列此任务，当前是功能缺口 | 🟠 | 需单独排期；M2-05 只交付了快照与日/周/月 K 线 |
| 7 | ~~市场总览在非交易日仍以"今天"为 `tradeDate`、`marketStatus` 报 `TRADING`~~ | ✅ | **M2-10 已关闭**。`SimulatedQuoteProvider` 此前完全不看交易日历（`tradeDate = now.toLocalDate()`，`sessionStatus` 由配置决定），而同一份快照的榜单预览却走 `SimulatedMarketAccess.latestTradeDate()`（已正确回退）——两个字段来自不同交易日且都不会报错。现抽出纯函数 `TradingSessions`，MKT-01 摄入、MKT-02 查询、个股/整批快照三条链路共用同一份口径 |
| 8 | 契约 §10 的 SEC-05 板块走势（`/sectors/{id}/trend`）与 SEC-07 板块资讯（`/sectors/{id}/news`）未交付 | 🟡 | `TASKS.md` 的 M2-07 范围是「排行、详情与成分股」，走势需按成分股聚合时间序列、资讯依赖资讯域（M3-04），两者均需单独排期 |
| 9 | 板块与成分关系不落库（`stock_sector` / `stock_security_sector` 空置） | 🔵 | 与 M2-01~M2-06 一致：模拟 Provider 内存生成，真实数据源接入时统一入库，用例层不变 |
| 10 | **STK-05 批量行情接口未实现**（`POST /quotes/securities/batch-query`），导致 PRD QTE-01 要求的"搜索建议含涨跌幅"做不到 | 🟡 | 契约 §8 明列该接口（"批量查询自选、板块成分股等首屏行情"），但 `TASKS.md` 的 M2 清单里没有它。M2-09 因此不展示搜索建议的涨跌幅（逐条调 STK-04 是 11 次请求，与 PRD「500 毫秒内出结果」冲突）。**建议补一个小任务**：底层 `SimulatedQuoteSnapshotProvider` 已同时提供单只与整批，实现成本很低；**M3-03 自选页首屏行情同样需要它** |
| 11 | 搜索建议不搜板块，也没有热门 / 最近搜索建议 | 🔵 | STK-01 只搜证券（板块走 `/sectors`）；"热搜"无数据来源，编造比留空更糟。已记入 M2-09 的"不在本轮范围" |
| 12 | ~~**顶栏的"交易中 14:32"与侧栏的"数据链路正常 / 延迟 26 秒"是写死的**~~ | ✅ | **M2-10 已关闭**。顶栏改为消费 MKT-02（时段进行中显示状态文案 + 当前北京时间，收盘后只显示状态文案、把 `nextSessionAt` 放进 `title`），侧栏改为显示真实的交易日历数据源时间。失败时显示"状态未知"并可点击重试，不沿用上一次的文案 |
| 13 | MKT-04 的独立趋势接口 `/markets/{marketCode}/turnover-trend`（含 5D / 20D 档位）无页面消费 | 🔵 | 总览页只画当日累计曲线（读 `overview.turnover`）。跨日档位需要前端加区间切换器，属独立增量 |

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

**7. Spring 按具体类型解析 `@Bean` 时别名 Bean 会形成两个候选** —— M2-06 实测踩到：
为让依赖方按端口注入，曾把一个 `SimulatedQuoteSnapshotProvider` 实例拆成「具体类型 Bean + 两个返回接口类型的别名 Bean」，
结果 Spring 解析 `SimulatedQuoteSnapshotProvider` 时报
`NoUniqueBeanDefinitionException: expected single matching bean but found 2`——别名 Bean 的**运行时类型**同样是具体类型。
**对策**：只声明一个具体类型 Bean，依赖方按自己需要的端口声明参数，Spring 按可赋值性解析到同一实例。

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
export JAVA_HOME="D:/idea/JDK17"               # 必须用 Windows 路径，见环境故障 1
mvn.cmd -s backend/settings.xml -f backend/pom.xml test

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
                                        │ 市场状态：TradingCalendarProvider（模拟日历）+ Clock 推导时段
                                        │ 市场广度：摄入时按 LimitRuleProvider 规则对 SecurityQuoteProvider 全市场个股计数
                                        │ 成交趋势：TurnoverTrendProvider 按交易日历生成分钟/日序列（确定性，整数运算）
                                        │ 证券主数据：SecurityMasterProvider 投影行情全集为 SecuritySummary（5149 只，内存搜索）
                                        │ 搜索建议：SecurityQueryService.search 按 CODE→NAME→PINYIN→PINYIN_ABBR 优先级取首个命中
                                        │ 榜单：QuoteSnapshotBatchProvider 整批取数 → StockRankingQueryService 筛选/排序/分页
                                        │ 板块：SectorProvider（39 个板块 + 成分关系）→ 按成分分组整批快照
                                        │       → SectorQuoteCalculator 等权聚合（SEC-02/03/04）与贡献度排名（SEC-06）
                                        │       → 同一份成分集合亦供 STK-02 / QTE-01 的 sectorId 筛选（SectorMembershipIndex）
                                        ▼
                              Redis 8.2 ◀──▶ MySQL 8.4
                                   ▲              ▲
                                   └── stock-job（每 60s）── SimulatedQuoteProvider
                                                            ├─ SimulatedSecurityQuoteProvider（5149 只确定性个股）
                                                            │     └─ 亦被 SimulatedSecurityMasterProvider 复用为证券全集
                                                            │     └─ 亦被 SimulatedQuoteSnapshotProvider 复用（单只 + 整批）
                                                            │     └─ 亦被 SimulatedSectorProvider 复用（成分关系投影自证券主数据）
                                                            ├─ SimulatedLimitRuleProvider（10 条限幅规则）
                                                            └─ SimulatedHashing（SplitMix64 收尾混合的唯一实现）
认证：login → JWT access(内存) + refresh(httpOnly cookie, Redis 轮换)
      401 → apiClient 单飞刷新 → 失败清会话 → 登录引导
统一壳：ApiResponse{success,code,message,data,traceId,timestamp}
前端接入（M2-08）：/market、/login、/rankings、/sectors、/sectors/:id、/stocks/:id → 真实接口
                    四页共用 composables/useRemoteData（三态 + 过期响应守卫）与 services/{ranking,sector,security}Api
前端接入（M2-09）：顶栏 GlobalSearch → STK-01（300ms 防抖 + 键盘选择 + 命中高亮 + 状态徽标）
前端接入（M2-10）：顶栏市场状态 + 侧栏数据源 → MKT-02（composables/useMarketStatus：60s 刷新 + 页面不可见暂停）
```

**不可动摇的架构约束**：前端不直连任何数据源；AI 由 Spring Boot 编排第三方 LLM（不提前拆 FastAPI）；MySQL 无外键，业务写入靠应用层事务 + 乐观锁 + Outbox。

## 9. 待用户处理

1. **配置 GitHub 分支保护**，把 CI 作业设为必需检查（Settings → Branches → Branch protection rules → Require status checks）。
   建议至少勾选 `backend`、`frontend`、`sql`、`sql-legacy-upgrade`。本机无 `gh` CLI，无法代设。

> `.workbuddy-ai/` 已加入 `.gitignore`（第 40 行），无需再处理。

## 10. 下一步

**M1 已完成**（仅剩 M1-07 的分支保护配置，需用户操作）。**M2 全部 9 个任务已完成 —— 市场域纵向补全收尾。**

**M2 的完成含义**：游客主流程（市场总览 → 榜单 / 板块 → 板块详情 → 个股详情 + 顶栏全局搜索）的
**每一个数字都来自真实接口**。后端 13 个非认证 GET 接口中，**8 个已被前端消费**：
`/markets/overview`、`/stock-rankings`、`/sector-rankings`、`/sectors/{id}`、`/sectors/{id}/constituents`、
`/securities/search`、`/securities/{id}/quote`、`/securities/{id}/klines`。

**剩余 4 个已实现但前端尚未消费**（都各有原因，见下表）——它们不是遗漏，是待接入：

| 接口 | 归属 | 前端未消费的原因 |
| --- | --- | --- |
| `GET /markets/{marketCode}/status` | MKT-02 | **M2-10 已消费**（顶栏市场状态 + 侧栏数据源） |
| `GET /markets/{marketCode}/breadth` | MKT-03 | 总览页读的是 `/markets/overview` 里内嵌的 `breadth` 字段，无需再请求 |
| `GET /markets/{marketCode}/turnover-trend` | MKT-04 | 同上，总览页用 `overview.turnover`；**独立趋势接口（含 5D / 20D 档位）尚无页面消费** |
| `GET /sectors` | SEC-01 | 板块页用 `/sector-rankings` 取同一快照下的全部板块行情；`/sectors` 是"管理型选择组件"的入口 |
| `GET /securities` | STK-02 | 同上，无页面消费（全局搜索走 STK-01） |

**下一步：进入 M3 用户态闭环与 AI 研究编排**，建议顺序：

1. **M3-01 / M3-02 自选分组与自选项 CRUD**（V5 表已就绪）→ **M3-03 前端 watchlist 接入**
2. **STK-05 批量行情接口**（见已知问题 #10）—— 自选页首屏与搜索建议涨跌幅都依赖它，建议与 M3-02 一并做
3. **M3-04 资讯 Provider → M3-05 前端 news 接入** → **M3-06 AI Provider → M3-07 编排 + SSE → M3-08 持久化 → M3-10 AI 工作台**

**M3 也是把 M2-08 本轮降级掉的字段逐个填回来的阶段**：市盈率、市值、业务描述、所属板块（STK-08/09）、
关联资讯（STK-10）、板块走势（SEC-05）、搜索建议的涨跌幅（STK-05）。

> 仍待用户操作：GitHub 分支保护配置（见第 9 节）；`.worktrees/` 残留 2 个被进程占用的 `element-plus` 文件（128K），关闭编辑器后可手动删除。
