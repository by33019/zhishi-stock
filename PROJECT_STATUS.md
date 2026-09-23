# PROJECT_STATUS.md — 知势平台项目状态

> 最后更新：2026-09-23（M3-12 完成：行情榜单 Excel 导出，新增第 10 个模块 `stock-export`；EXP-01~04 端到端通过；顺带修复"未匹配路径谎报 500"）
> 任务清单见 `TASKS.md`，路线图见 `docs/superpowers/plans/2026-09-19-mvp-delivery-roadmap.md`。

---

## 1. 一句话状态

**"知势" AI 智能股票分析平台**：设计文档完备、数据库迁移完备、前端高保真原型可跑、后端首个纵向切片（认证 + 市场总览）已跑通并合入 `main`，**且全栈 Compose 端到端验收已通过**（真实 API + 登录 + Cookie 恢复 + 退出 + 路由保护全链路）。工程地基已完备：mvn 修复、数据库两条迁移路径验证、CI 四作业、容器构建稳定性修复、项目文档补全、本地 dev 联调打通。**M1 除「GitHub 分支保护」需用户操作外全部完成**；**M2 全部 11 个任务已完成**（MKT-01~04、STK-01/02/04/07、QTE-01、SEC-01~04/06，以及前端四页接入、全局搜索、市场状态真实化与总览板块预览真实化）。市场域已从"首页一张硬编码快照"扩展为可查询的状态 / 广度 / 趋势 / 证券 / 榜单 / 板块六组接口，广度与趋势均由确定性模拟数据按规则真实计算；证券主数据（5149 只）已可搜索与分页筛选；个股详情已可查完整快照与日/周/月 K 线；全市场榜单已可按涨跌幅 / 成交额排序并多条件筛选分页；**板块已可排行、下钻到详情与成分股，且 `sectorId` 在 STK-02 与 QTE-01 上成为真实可用的筛选键**（此前是硬编码空页）；**前端 4 个页面 + 顶栏全局搜索 + 顶栏市场状态已从原型数据切到真实接口**——页面展示的每个数字都能在接口响应里找到来源，没有来源的字段（市盈率、市值、业务描述、板块走势、AI 速览、搜索建议的涨跌幅等）一律显示"尚未实现"而不是保留编造值；**总览快照的 `tradeDate` / `marketStatus` / `dataTime` 已由交易日历推导**，非交易日回退到最近有效收盘并标 `CLOSED`（此前周日会报"今天是交易日、正在交易中"）；**总览的榜单预览与板块预览都已投影自各自的榜单取数路径**，页面上的跳转主键（`securityId` / `sectorId`）全部可被对应详情接口解析（此前首页三张板块卡片点进去全部 404）。**M3 已开工**：第一个任务 **M3-01（自选分组 CRUD，WAT-01~WAT-05）已完成**——V5 的 `user_watchlist_group` / `user_watchlist_item` 两张表从 M1 起"结构就绪、无代码引用"，现在接上了应用层；接口带**真正生效**的 `Idempotency-Key` 幂等（24h 窗口，可复用组件）与 `If-Match` 乐观锁（版本不匹配 409），分组名合法性、默认分组唯一性、软删后同名可重建、搬移时合并目标组重复项这些不变量既有应用层实现、也有真实 MySQL 8.4 实测。**第二个任务 M3-02（自选项 CRUD + 排序 + 行情概览，WAT-06~WAT-12）也已完成**——自选项的增删改查、原子重排、以及 `GET /watchlists/overview` 聚合（分组 + 自选项 + 市场状态 + 快照版本 + `limitations`）全部落地；同组同证券是**成功幂等**而不是 409，移动时撞车走**合并**（删源行、目标行 `version` 不变），概览**降级不失败**（悬空证券 / 缺行情只写进 `limitations`，不编造 `snapshotVersion`）；契约与前端用的字符串 `securityId`（`sim-600519`）与库里 bigint 代理键之间的桥接抽成了独立端口 `SecurityIdentityProvider`，构词规则只有一处定义、全市场 5149 只往返有测试守着；M3-01 刻意留下的 `includeItems=true` 显式 400（已知问题 #15）本轮补成真实数据。**至此自选中心后端（WAT-01~WAT-12）12 个接口全部可用，前端接入是下一步 M3-03。** **第三个任务 M3-03（前端 `/watchlist` 接真实 API）也已完成**——自选页从"分组写死 + 复用 `mockApi` 的榜单行"换成真实接口：首屏**只发一次** `GET /watchlists/overview` 取全（分组 + 自选行情 + 市场状态 + 数据状态，四者来自同一批快照，不存在"两个时刻混在一屏"），分组切换在完整响应内选择而不重新请求，增删移排全部接上并带 `Idempotency-Key` / `If-Match`，导语的涨跌只数由真实行情算出且**停牌单独计数**；卡片上两个没有数据源的字段（分时 sparkline、`latestNewsCount`）**直接不渲染**——画出来就是编造。`mockApi` 至此只剩 `getNews` 一个函数（M3-05 整体删除）。本轮**做了真实端到端联调**（本机 Docker 起 MySQL 8.4 + Redis + 后端，空库 Flyway V1→V8，用前端完全相同的请求形状走完 WAT-01/02/03/04/07/09/10/11），并因此查出两个单测发现不了的缺陷：**空自选时页面在编造一次不存在的快照**（后端此时返回 `dataStatus=UNAVAILABLE`、不发起整批取数），以及**停牌股被算成"平盘"**（真实数据里停牌股有快照且 `changeRate="0.0000"`）。两个都已修复并补上测试。 **第四个任务 M3-04（资讯 Provider 抽象 + 模拟源 + 去重 + 标的关联）也已完成**——资讯域独立成仓库第 7 个模块 `stock-news`（domain 36 / application 5 / infrastructure 9），`NewsIngestionService` 走「取数 → 登记来源 → 授权闸门 → **来源 ID 幂等** → **内容指纹去重** → 落库 → 仅主记录解析关联」，`NewsQueryService` 用一个 6 步可见性过滤链同时支撑六个契约接口（NEWS-01~04 / STK-10 / SEC-07）；**这是仓库里第一个把模拟 Provider 的产出写进 MySQL 的里程碑**（去重需要跨批次记忆、`content_fingerprint` 是唯一索引的一部分、关联有生命周期，三条都不是内存无状态计算能回答的）；定时采集由 `stock-job` 的 `ScheduledNewsCollector` 每 2 分钟驱动，失败时在采集事务回滚之后用 `REQUIRES_NEW` 独立事务留痕再向上抛；**e2e 实测**：真实定时任务两轮采集，第二轮**零新增行**（来源 ID 幂等生效）、停用来源的 `last_success_at` 始终 `NULL`、跨来源重复稿被指纹判为 `DUPLICATE` 并折叠到主记录、**只有 CANDIDATE 关联的稿件在列表与板块视图中都不可见**（“低置信不进默认视图”只有真库能验）；同时关闭了 M3-02 / M3-03 留下的 `latestNewsCount` 与 `newsSince` 两处欠账（后者此前是显式 400 占位）。**至此 M3 已完成 4/12，自选闭环与资讯域两条链路都可端到端跑通。** **第五个任务 M3-05（前端 `/news` 接真实 API）也已完成**——资讯页从 `mockApi` 的 3 条写死稿件换成 NEWS-01（列表）+ NEWS-04（受控筛选项）：**筛选与分页全部走服务端**（`newsTypes` / `keyword` / `page` / `size`），**类型标签由服务端给的 `newsTypes` 动态生成**（前端只保留「枚举值 → 中文名」的显示映射，未知取值回退为原值），关键字改为**显式提交**（与 M3-03 的选股面板同一套约定），原文链接按 `originalAccessStatus` 渲染，关联标的渲染成可跳转标签（`MARKET` 与空 `targetId` 不跳转）；**侧栏「今日事件密度 286 条」与「高频主题 42/36/29/18」这两块编造数据被降级为「尚未实现」**——它们连模拟源都没有。`services/mockApi.ts` 与其测试**整体删除**，仓库里从此不再有 mock 通路。本轮同样**做了真实端到端联调**（Playwright 打开真实 `/news` 页），并因此查出：**`originalAccessStatus` 在模拟源里一律是 `UNKNOWN`，按初稿「只认 `AVAILABLE`」的规则会让「查看原文」8 条全部消失**（已改为 `UNKNOWN` 也给链接并标注状态未知），以及 `GET /news/options` 的 `availableTimeRange` 里多出一个契约外的 `empty` 字段（`NewsTimeRange.isEmpty()` 被 Jackson 当 getter，记入已知问题 #21）。**至此 M3 已完成 5/12，前端 11 个路由里 8 个接了真实接口。** **第六个任务 M3-06（AI Provider 抽象 + 确定性模拟实现）也已完成**——AI 域独立成仓库第 8 个模块 `stock-ai`（31 个 main 类型 + 4 个测试类，**不引入 MyBatis**，本轮不落库），交付 AI-01 `GET /ai/scenes`（5 个场景的静态规则表：类型白名单、目标数量区间、`defaultRange` 档位、`questionMaxLength=500`）与 AI-02 `POST /ai/context-previews`（一次取数固化 `AiContextSnapshot` + `AiEvidenceCandidate`，缺行情 / 资讯 / 板块只记 `limitations` 而不抛异常）；**AI 的上下文与前端页面的数字走同一条取数路径**——行情复用 `QuoteBatch`、板块复用 `SectorQuoteCalculator`、资讯复用 `NewsQueryService.visible()` 再叠 `allow_ai_analysis`，所以「AI 看到的」与「页面上显示的」不会互相矛盾；**「模型不生成可信 URL」被做成类型保证**（`LlmEvidence` 类型上不含 URL 字段）而不是事后正则校验——校验器总会有漏掉某种 URL 形态的窗口，类型不会；`LlmProviderPort` 端口已就位，真实 LLM 接入时替换实现即可。**e2e 实测**（Docker 全栈 + `frontend/e2e/ai.real.mjs`）：未登录两个接口均 401、AI-01 不泄露内部 Prompt、AI-02 的行情截止时间 `2026-09-18T15:00:00+08:00` 来自**真实行情批次而不是「现在」**、7 种非法请求全部 400 且业务码正确；另用**受控实验**验证来源授权语义——临时关闭 `SIM_MEDIA_A` 的 `allow_ai_analysis` 后，`/news` 列表仍能看到该稿件，而 AI 上下文的 `newsCount` 由 1 变 0、`dataCategories` 由 `[QUOTE, NEWS]` 变 `[QUOTE]`，恢复后复原。本轮还顺带修掉 2 个真实缺陷（上下文哈希对数据时间不敏感、区间不合法被报成 `AI_TARGET_INVALID`），并加固了 3 处「空结果也能通过」的假绿断言。**至此 M3 已完成 6/12。**

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
| 后端全量测试 | ✅ **1,025 测试通过** | common 1 / market 159 / news 103 / system 89 / **ai 218** / **export 72** / integration 127 / backend 226 / job 12 / **ai-worker 18**，0 失败 0 错误。本机 Docker 已启动，`InfrastructureIntegrationTest`（Testcontainers 真实 MySQL 8.4 + Redis）与 `AiWorkerIntegrationTest`（完整上下文 + 真库 + 真 Redis）**本轮实测通过**，不再是"CI 覆盖" |
| 后端 Flyway 迁移（空库路径） | ✅ 已在真实 MySQL 8.4 验证 | 集成测试断言 `flyway_schema_history` 有 8 条成功迁移 |
| 后端 Flyway 迁移（旧库升级路径） | ✅ **首次验证通过** | baseline v1 → V2–V8 → `now at version v8`，退出码 0 |
| 迁移后完整性校验 | ✅ 通过 | `post_migration_validation.sql` 无异常明细，`foreign_key_count = 0` |
| 前端类型检查 + 测试 + 构建 | ✅ 通过 | **23 文件 / 193 测试**（默认时区与 `TZ=UTC` 下均全绿）；`npm run typecheck` 0 错误；`vite build` 成功 |
| 本地 dev 联调（Vite 代理） | ✅ 已打通 | dev server 下 `GET /api/v1/markets/overview` 返回真实 JSON |
| 自选闭环端到端 | ✅ **M3-03 实测通过** | 本机 Docker 起 MySQL 8.4 + Redis + `stock-backend`（dev profile），空库 Flyway V1→V8，按前端**完全相同的请求形状**走完 WAT-01/02/03/04/07/09/10/11 并逐字段核对；由此查出 2 个单测发现不了的缺陷（空自选编造快照、停牌算成平盘），均已修复 |
| 资讯域端到端 | ✅ **M3-04 实测通过** | 本机 Docker 起 MySQL 8.4 + Redis 8.2 + `stock-api` + `stock-job`，空库 Flyway V1→V8；**库里数据全部由真实定时任务采集而来（非夹具）**：5 来源 / 9 稿件（8 ORIGINAL + 1 DUPLICATE）/ 8 关联（6 CONFIRMED + 2 CANDIDATE）。第二轮采集（+120s）**零新增行** ⇒ 来源 ID 幂等生效；停用来源 `SIM_MEDIA_C` 的 `last_success_at` 始终 `NULL`；重复稿折叠到主记录且库里关联数为 0；只有 CANDIDATE 关联的稿件在 NEWS-02 与 SEC-07 里都不可见；六个接口 + WAT-11 的 `newsSince` 逐字段核对通过，6 种非法输入全部 400、4 种不存在资源全部 404 |
| 资讯页端到端 | ✅ **M3-05 实测通过** | Docker 全栈（`mysql` / `redis` / `flyway` / `stock-api` / `stock-job` / `frontend`） + Playwright 无头浏览器打开 `http://127.0.0.1:8088/news`，脚本 `frontend/e2e/news.real.mjs`。实测：首屏**恰好 2 个请求**（`/news/options` + `/news?page=1&size=20`）；类型标签 5 个全部来自 NEWS-04；切换类型**只新增 1 个请求**且带 `newsTypes`，options 累计仍 1 次；关键字搜索**保留当前筛选条件**（`newsTypes=ANNOUNCEMENT&keyword=银行`）；关联标签里 `CN` 无链接、`002343` → `/stocks/sim-002343`；侧栏渲染「尚未实现」、页面上没有 `286` / `42`；**原文链接 8 条全部可点** |
| AI 域端到端 | ✅ **M3-06 实测通过** | Docker 全栈（`stock-api` Healthy） + `frontend/e2e/ai.real.mjs`。实测：未登录时 AI-01 / AI-02 均 401；AI-01 返回 5 个场景且**不泄露内部 Prompt**；AI-02 的 `QUOTE` 截止时间 = `2026-09-18T15:00:00+08:00`（**真实行情批次，不是「现在」**）；`COMPARE` 的多标的与单标的行情截止时间一致；7 种非法请求全部 400 且业务码正确。另做**受控实验**验证 `allow_ai_analysis`：临时关闭 `SIM_MEDIA_A` 的授权后，`/news` 列表 `total` 仍为 1，而 AI-02 的 `newsCount` 由 1 变 0、`dataCategories` 由 `[QUOTE, NEWS]` 变 `[QUOTE]`，恢复后复原 |
| AI 任务端到端 | ✅ **M3-07 实测通过** | Docker 全栈 **7 容器**（`mysql` / `redis` / `flyway` / `stock-api` / `stock-job` / **`stock-ai-worker`** / `frontend`） + `frontend/e2e/ai-task.real.mjs`。10 项全通过：未登录 6 个端点全部 401；AI-03 返回 202；同 `Idempotency-Key` 重发回放**同一个 `taskId`** 且不重复消耗额度；状态推进 `QUEUED → PREPARING → RUNNING → COMPLETED`；SSE 收齐 `snapshot`/`status`/`chunk`/`report`/`done`（17 个 chunk，`section` 全部属于六章节）；`Last-Event-ID=2` 只补发 `id>2` 的 20 条；查库五张表都有真实行（`task=1 target=1 snapshot=1 message=2 report=1`）；报告的 `market_data_cutoff_at` 来自真实行情批次（`2026-09-21 15:00:00`）；取消已完成任务状态不变；并发上限触发 429 |
| 导出端到端 | ✅ **M3-12 实测通过** | Docker 全栈（`mysql` @ **3308** / `redis` / `flyway` / `stock-api` / `stock-job` / `stock-ai-worker` / `frontend`），**经 nginx `:8088` 与浏览器完全同路径**：登录 → EXP-01 返回 **202** → 同 `Idempotency-Key` 重发**回放同一个 `exportId`** → EXP-02 `COMPLETED`（成交额榜 + 沪市筛选，**2,574 行**）→ EXP-03 下载 **200,634 字节** xlsx。解包核对说明区（数据截止时间 15:00:00+08:00 / 榜单类型 / 交易所 / ST 与停牌口径 / 行数上限）、13 列表头、`pane ySplit=13` 冻结、`autoFilter A13:M2587` 恰好覆盖数据行；查别人的作业 id → 404 `EXPORT_NOT_FOUND`（与"不存在"同一句话） |
| 未知路径行为 | ✅ **M3-12 修复并实测** | 修复前：未匹配路径落进兜底的 `Exception` handler，报 **500「服务暂时不可用」**（谎报）。修复后（已登录）→ **404 `NOT_FOUND`**。未登录时仍是 401（Security 先拦），符合 §23.1"404 = 资源不存在**或出于安全隐藏**" |
| MySQL 宿主机端口 | ✅ 已改 **3308** | 本机 3306 被原生 MySQL 占用。`MYSQL_PORT` **只影响宿主机映射**（容器间走服务名 `mysql:3306`），`flyway` / `stock-api` / `stock-job` / `stock-ai-worker` 零改动；实测 `host.docker.internal:3308` 可连、41 张表、其它容器自愈无 500 |
| CI | 🟢 **已在 GitHub 实际运行** | `.github/workflows/ci.yml`（**4 作业**，含旧库升级路径）；前端作业曾因时区依赖持续失败，已修复（`3fd970c`） |
| 全栈 Compose 端到端 | ✅ **已通过** | 7 容器全部启动、无 ERROR；`npm run e2e:real` 通过 |
| 容器镜像构建 | ✅ 稳定可重复 | 修复容器内依赖下载中断后，6 镜像连续构建成功 |
| 数据库迁移在真实库执行 | ✅ 空库 + 旧库升级两条路径均已完成 | 见上 |
| preflight 脚本 | ✅ 已在真实旧库验证（含负向用例） | 9 段查询可执行；注入违规数据后 3 项 blocking 全部检出 |

## 4. 里程碑进度

| 里程碑 | 目标 | 状态 |
| --- | --- | --- |
| M1 | 合流与工程地基 | 🟢 15/16 完成（仅 M1-07 的「GitHub 分支保护」需用户操作） |
| M2 | 市场域纵向补全（游客主流程全真实） | ✅ **11/11 完成**（M2-01 交易日历与市场状态、M2-02 市场广度、M2-03 成交趋势、M2-04 证券主数据与搜索建议、M2-05 个股快照与日/周/月 K 线、M2-06 榜单、M2-07 板块排行/详情/成分股、M2-08 前端四页接入真实接口、M2-09 全局搜索接真实接口、M2-10 市场状态真实化、M2-11 总览板块预览真实化） |
| M3 | 用户态闭环与 AI 研究编排 | 🟡 **10/12 完成**（M3-01 自选分组 CRUD、M3-02 自选项 CRUD + 排序 + 行情概览、M3-03 前端 `/watchlist` 接真实 API、M3-04 资讯域（第 7 个模块 `stock-news`）+ 六个接口 + 定时采集落库、M3-05 前端 `/news` 接真实资讯接口、M3-06 AI Provider 抽象（第 8 个模块 `stock-ai`）+ AI-01/AI-02 两个接口、**M3-07 AI 任务编排 + SSE 流式契约（第 9 个模块 `stock-ai-worker`）+ AI-03~AI-08 六个端点**、**M3-08 报告/证据/反馈持久化收口（HIS-01~HIS-09 共 9 个端点全部完成）**、**M3-09 AI 配额与用量统计（`ai_usage` 写入侧 + USER-07）**、**M3-12 行情榜单 Excel 导出（第 10 个模块 `stock-export`，EXP-01~04）**；未完成的 M3-10 已接入 `/ai` 与 `/history`（未接 SSE 与取消/重试/追问）、M3-11（后台 admin 最小集）未开工） |

## 5. 已完成能力盘点

| 层 | 完成度 | 说明 |
| --- | --- | --- |
| 设计文档 | ✅ 100% | PRD 86KB、Architecture 53KB、RESTful-API 79KB + superpowers specs/plans |
| 数据库迁移 | ✅ 结构 100% / 两条路径均验证 | Flyway V1–V8；旧库样本 149KB（原 24MB） |
| 前端原型 | ✅ 页面 100% / 真实接入 10/11 | 11 路由全部有页面；`/market`、`/login`、`/rankings`、`/sectors`、`/sectors/:id`、`/stocks/:id`、`/watchlist`、`/news` 接真实 API；**顶栏全局搜索框已接 STK-01、顶栏市场状态与侧栏数据源已接 MKT-02**（两者都是非路由的全局组件）；`/ai`（AI-01~04 + HIS-06/07，走轮询）与 `/history`（HIS-01~05，含改名/收藏/两步删除）已接真实接口；**榜单页的 Excel 导出已由 M3-12 接上**（创建 → 轮询 → 下载，非路由组件）；`/admin` 仍是本地演示数据的静态页面（M3-11）；**`services/mockApi.ts` 已随 M3-05 删除**，仓库里不再有 mock 通路 |
| 后端 | 🟡 9/10 域 | 认证闭环 ✅、市场总览（MKT-01~04）🟡、证券主数据与个股行情（STK-01/02/04/07）🟡、榜单（QTE-01）🟡、板块（SEC-01/02/03/04/06/**07**）🟡、**自选中心（WAT-01~WAT-12）✅ 后端完整**、**资讯域（NEWS-01~04 + STK-10 + SEC-07）✅ 后端完整且落库**、**AI 域（AI-01~AI-08 + HIS-01~HIS-09 + USER-07）✅ 全链路可用**——任务编排、SSE 中继、报告与来源证据落库、调用用量台账都已端到端验证；**V6 的 9 张表全部已有真实数据**（`ai_feedback` 由 M3-08、`ai_usage` 由 M3-09 补上）；**导出域（EXP-01~04）✅ 后端完整**（作业存 Redis，**未建表**） |
| 测试 | ✅ 1218 个 | 后端 1025（10 个模块）+ 前端 193；无覆盖率门槛 |
| 工程化 | 🟢 85% | CI 工作流 ✅、TASKS/STATUS/CHANGELOG ✅、根与模块 README ✅、容器构建稳定 ✅；分支保护待用户配置 |

## 6. 已知问题（按严重度）

| # | 问题 | 严重度 | 处置 |
| --- | --- | --- | --- |
| 1 | 分支保护未设置（CI 四个作业已实际跑通） | 🟡 | M1-07 收尾（需用户在 GitHub 配置必需检查） |
| 2 | 前端页面真实数据源 | 🔵 | M2-08 接入 rankings / sectors / sectors:id / stocks:id 四页；`/news` 由 M3-05、`/watchlist` 由 M3-03、`/ai` 与 `/history` 由 M3-10 接入，**`services/mockApi.ts` 已删除**；**仅剩 `/admin` 仍是本地演示数据的静态页面（M3-11）** |
| 3 | 认证默认值偏松（`JWT_SECRET` 默认空、dev `COOKIE_SECURE=false`、演示账号固定密码） | 🟡 | 生产 profile 需单独加固，待排期 |
| 4 | `preflight_existing_schema.sql` 第 8 段永远不会触发（`block_label` 实际是 `varchar(10)`，检查条件为 `> 20`） | 🔵 | 设计上的防御性检查，无实际影响，仅记录 |
| 5 | 独立 `MockMvc` 的日期序列化与线上不一致（`LocalDate` → `[2026,9,11]`） | 🔵 | **已修复**：契约测试显式构造 `ObjectMapper` 关闭 `WRITE_DATES_AS_TIMESTAMPS`。后续新增契约测试需沿用同一 helper，否则日期断言会失真 |
| 6 | PRD 把「分时图」列为 P0（STK-02），但 `TASKS.md` 的 M2 未列此任务，当前是功能缺口 | 🟠 | 需单独排期；M2-05 只交付了快照与日/周/月 K 线 |
| 7 | ~~市场总览在非交易日仍以"今天"为 `tradeDate`、`marketStatus` 报 `TRADING`~~ | ✅ | **M2-10 已关闭**。`SimulatedQuoteProvider` 此前完全不看交易日历（`tradeDate = now.toLocalDate()`，`sessionStatus` 由配置决定），而同一份快照的榜单预览却走 `SimulatedMarketAccess.latestTradeDate()`（已正确回退）——两个字段来自不同交易日且都不会报错。现抽出纯函数 `TradingSessions`，MKT-01 摄入、MKT-02 查询、个股/整批快照三条链路共用同一份口径 |
| 8 | 契约 §10 的 SEC-05 板块走势（`/sectors/{id}/trend`）未交付（**SEC-07 板块资讯已由 M3-04 交付**） | 🟡 | `TASKS.md` 的 M2-07 范围是「排行、详情与成分股」，走势需按成分股聚合出时间序列，需单独排期 |
| 9 | 板块与成分关系不落库（`stock_sector` / `stock_security_sector` 空置） | 🔵 | 与 M2-01~M2-06 一致：模拟 Provider 内存生成，真实数据源接入时统一入库，用例层不变 |
| 10 | **STK-05 批量行情接口未实现**（`POST /quotes/securities/batch-query`），导致 PRD QTE-01 要求的"搜索建议含涨跌幅"做不到 | 🟡 | 契约 §8 明列该接口（"批量查询自选、板块成分股等首屏行情"），但 `TASKS.md` 的 M2 清单里没有它。M2-09 因此不展示搜索建议的涨跌幅（逐条调 STK-04 是 11 次请求，与 PRD「500 毫秒内出结果」冲突）。**建议补一个小任务**：底层 `SimulatedQuoteSnapshotProvider` 已同时提供单只与整批，实现成本很低；**M3-03 自选页已不再依赖它**（首屏行情由 WAT-11 的整批快照提供） |
| 11 | 搜索建议不搜板块，也没有热门 / 最近搜索建议 | 🔵 | STK-01 只搜证券（板块走 `/sectors`）；"热搜"无数据来源，编造比留空更糟。已记入 M2-09 的"不在本轮范围" |
| 12 | ~~**顶栏的"交易中 14:32"与侧栏的"数据链路正常 / 延迟 26 秒"是写死的**~~ | ✅ | **M2-10 已关闭**。顶栏改为消费 MKT-02（时段进行中显示状态文案 + 当前北京时间，收盘后只显示状态文案、把 `nextSessionAt` 放进 `title`），侧栏改为显示真实的交易日历数据源时间。失败时显示"状态未知"并可点击重试，不沿用上一次的文案 |
| 13 | MKT-04 的独立趋势接口 `/markets/{marketCode}/turnover-trend`（含 5D / 20D 档位）无页面消费 | 🔵 | 总览页只画当日累计曲线（读 `overview.turnover`）。跨日档位需要前端加区间切换器，属独立增量 |
| 14 | ~~总览快照的板块预览是三个写死的常量，`sectorId` 用 `bk-ai` / `bk-chip` / `bk-broker`，首页三张板块卡片点进去全部 404~~ | ✅ | **M2-11 已关闭**。`SimulatedQuoteProvider.sectors()` 改为投影自 `SectorProvider` + `SectorQuoteCalculator`，与 SEC-02 板块排行（默认口径 `GAINERS`）走同一条取数路径；为此把 `QuoteBatch` 从 `market.application` 下沉到 `market.domain`，使摄入侧适配器能复用同一份"按成分关系取数"语义 |
| 15 | ~~WAT-01 的 `includeItems=true` 返回 400（"还没实现"，而不是返回 `items: []` 编造"这个分组里没有股票"）~~ | ✅ | **M3-02 已关闭**。`WatchlistItemService.entriesOf` 提供真实自选项，`WatchlistGroupController` 在 `includeItems=true` 时逐组填充 `items`，并用 `@JsonInclude(NON_NULL)` 保证 `false` 时响应与 M3-01 **逐字节一致** |
| 16 | 幂等键"同一键并发提交"不是严格互斥（当前是读—执行—写，窗口内两个同键请求可能都执行） | 🔵 | 契约只要求"重复提交返回第一次结果"，真正互斥需要额外 Redis 锁。低概率且后果可控，暂不处理 |
| 17 | **全站没有任何限流**（契约 §22.1 要求自选写操作 60/min 等） | 🟡 | 限流基础设施整体不存在，单为自选做一套会是"一处实现、八处复制"。需按"全站限流"独立排期，不要按模块零散加 |
| 18 | 契约 §12.3 的 `WATCHLIST_ITEM_EXISTS` 没有任何端点会抛出 | 🔵 | WAT-07 要求"同组同证券"幂等成功、WAT-09 要求撞车时合并，两条路都通不到报错分支。按"不加永不触发的分支"处理，保留在契约文档里，等真实数据源接入后若产品口径变化再评估 |
| 19 | 模拟 Provider 把"最近一个已完成交易日"当作 `REALTIME` 批次：周日拉 WAT-11 得到 `dataStatus=REALTIME` 但 `dataTime=2026-09-18T15:00:00+08:00` | 🔵 | M3-03 e2e 实测发现。真实行情源接入后此语义自然修正。**不要**在前端按"日期不是今天"自行降级——那会与后端的 `dataStatus` 打架，且周末必然误报 |
| 20 | **WAT-11 的 `latestNewsCount` 无法表达“0 条”**：`NewsCountProvider` 的端口注释声称“0 条”与“不知道”可区分，但实现里**没有任何代码路径产生“未知”**——`countSince` 无论资讯源是否可用都只返回“有条数”的证券，于是“0 条”这个事实被写成了“不知道”，前端只能渲染“—” | 🔵 | M3-04 e2e 实测发现（`sim-600519` 无资讯 → `null`；`newsSince=2026-09-19` 时三只全 `null`）。本轮**不改**：契约 §12.2 没规定 `null`/`0` 语义，且 M3-03 已立下“缺失即 `null`”的口径。**M3-05 已定前端处置**：`null` 时**不渲染该字段**（不是渲染成“0 条”，也不是渲染成“—”）；后端语义的最终决定留到有真实资讯源之后，届时要么给 WAT-11 补一个资讯新鲜度字段（契约增量），要么把缺失当 `0`（此时“未知”只剩“悬空证券”一种）。原建议是：要么给 WAT-11 补一个资讯新鲜度字段（契约增量），要么把缺失当 `0`（此时“未知”只剩“悬空证券”一种）。**在那之前，前端不要把 `null` 渲染成“0 条”** |
| 21 | **`GET /news/options` 的 `availableTimeRange` 多出一个契约外的 `empty` 字段**：`NewsTimeRange.isEmpty()` 是**无参** `isXxx()` 方法，Jackson 按 getter 规则把它序列化进了响应（契约 §4.3 只列 `startAt` / `endAt`） | 🔵 | M3-05 e2e 实测发现。修法是给该方法加 `@JsonIgnore`（一行）。**本轮不改**：M3-05 是前端里程碑、前端类型不认这个字段、功能无影响。同类风险：同模块的 `Sector.isType(SectorType)` 与 `LoginAttemptState.isLocked(Instant)` 都带参数，不受影响——**新增 record 的无参 `isXxx()` 方法前要想一下它会不会被序列化** |
| 22 | **AI 上下文只接入了 7 类 `dataCategory` 中的 3 类**（`QUOTE` / `SECTOR` / `NEWS`；V6 的 CHECK 另列 `KLINE` / `BUSINESS` / `CALENDAR` / `RULE`） | 🔵 | M3-06 交付范围。`dataCategories` **只列实际取到的类别**——为未接入的 4 类补一个「看起来合法的截止时间」就是编造。各自数据源就位后逐个接入 |
| 23 | ~~AI 域 V6 的 9 张表仍为零行零引用~~ | ✅ | **M3-07 关闭 6/9**：`ai_session` / `ai_task` / `ai_task_target` / `ai_context_snapshot` / `ai_message` / `ai_report` 已有真实数据（由 worker 真实执行写入，非夹具）。**M3-08 补 `ai_evidence` / `ai_feedback`、M3-09 补 `ai_usage` → 9/9 全部有真实数据** |
| 24 | **契约 §13.5 的全局 30 并发上限未实现**（只做了单用户并发上限 2） | 🔵 | 全局上限需要跨实例的计数（Redis 或 DB 聚合），而本轮只验证了单实例形态。单用户闸门已实现并有测试；全局闸门按"不要按模块零散加"的原则随限流一起排期（同 #17） |
| 25 | **`AiTaskStore.findByRequestId` 传非 ASCII 串会抛 collation 错而不是返回空**（`request_id` 是 `char(36) ascii_bin`） | 🔵 | M3-07 集成测试发现。生产路径上该参数永远是由 `(userId, Idempotency-Key)` 派生的 UUID，因此不可达；只记录，不加防御分支 |
| 26 | ~~**改了接口但没重新构建容器，新接口以 500「服务暂时不可用」出现**~~ | ✅ | **M3-12 已显著缓解 + 根因记录**。实测：运行中的镜像构建于 09-22 10:00 UTC，而 `CurrentUserController` 的改动时间是 18:27（+08），容器内 `/app/app.jar` 里 `ExportJobController` / `AiQuota` 等条目数为 **0** ⇒ M3-09 与 M3-12 的接口在运行的版本里根本不存在。**为什么这个坑特别贵**：缺失的路径原本落进兜底的 500 分支，报"服务暂时不可用"，把排查引向"服务是不是崩了"。现在返回 **404 `NOT_FOUND`** 且文案直接写"若刚刚新增/修改过接口，请确认服务已重新构建部署"——一句话指到根因。**改完代码后必须 `docker compose build <service> && docker compose up -d`** |
| 27 | **本机 3306 被原生 MySQL 占用**，Compose 的 MySQL 宿主映射改用 **3308** | 🔵 | `MYSQL_PORT` 只影响宿主机映射（容器间走服务名 `mysql:3306`），所以 `flyway` / `stock-api` / `stock-job` / `stock-ai-worker` **零配置改动**。`.env`（已 gitignore）为 3308，`.env.example` 保留 3306 并注明改法。**同一台机器上第二个项目再起 MySQL 时要再改一次** |
| 28 | 未登录请求一个**不存在的路径**返回 401 而不是 404 | 🔵 | Spring Security 在过滤器链上先拦。**不是缺陷**：契约 §23.1 的措辞是"404 = 资源不存在（**或出于安全隐藏**）"，且未认证时不泄露"这个路径是否存在"是更好的行为。已记录以免被当成 bug 反复排查 |
| 29 | **EXP-04（删除导出作业）前端没有调用方** | 🔵 | 后端已实现且有契约测试；下载后的文件由 `stock-job` 在 24h 后回收，当前没有任何界面需要"提前删除"。按"不加没有调用方的代码"处理，前端**不实现**这个 service 函数 |
| 30 | **契约里尚未排期的端点**：`GET /stock-rankings/options`（QTE-04）、`/market-indices`（MKT-05~07）、`/trade-calendars`（MKT-08） | 🔵 | 三者都不在 `TASKS.md` 的 M2/M3 清单里，前端也未调用。M3-12 逐条核对了前端实际发起的 24 条路径，确认差异只在这三条。**要做得先在任务清单里立项**，否则会以"顺手补一下"的形式变成无验收的增量 |

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

**8. nginx 缓存了 `stock-api` 的 IP，重建后端后全站 `/api/**` 变 502** ——
`frontend/nginx.conf` 里 `proxy_pass http://stock-api:8080;` 是**字面量**上游，
而 nginx 对字面量上游只在**启动时**解析一次主机名并把 IP 缓存下来；
`docker-compose up -d --build` 重建 `stock-api` 会分配新 IP，nginx 于是继续打旧 IP
（实测：`stock-api` 已是 `172.22.0.5`，nginx 仍在打 `172.22.0.6`）。
**症状极具误导性**：前端拿到 502 的 HTML 页面，`apiClient` 解析不出响应壳，
报的是「服务返回了无法识别的数据」（`INVALID_RESPONSE`）——看起来像前端没写完，
而后端明明是好的。**对策**：改用变量形式的 `proxy_pass` + 显式 `resolver 127.0.0.11`
（Docker 内嵌 DNS，`valid=10s`），解析发生在每次请求时，后端换 IP 最多 10 秒自愈。

**7. Spring 按具体类型解析 `@Bean` 时别名 Bean 会形成两个候选** —— M2-06 实测踩到：
为让依赖方按端口注入，曾把一个 `SimulatedQuoteSnapshotProvider` 实例拆成「具体类型 Bean + 两个返回接口类型的别名 Bean」，
结果 Spring 解析 `SimulatedQuoteSnapshotProvider` 时报
`NoUniqueBeanDefinitionException: expected single matching bean but found 2`——别名 Bean 的**运行时类型**同样是具体类型。
**对策**：只声明一个具体类型 Bean，依赖方按自己需要的端口声明参数，Spring 按可赋值性解析到同一实例。

## 7. 技术栈与运行方式

| 层 | 技术 |
| --- | --- |
| 前端 | Vue 3.5 / TypeScript 6 / Vite 8 / Vue Router 5 / Pinia 4 / ECharts 6 / Lucide；Vitest 5 + Vue Test Utils + Playwright |
| 后端 | Spring Boot 3.5.9 / Java 17 / MyBatis-Plus 3.5.5 / jjwt 0.12.5 / Spring Security / Spring Data Redis / JdbcTemplate / Apache POI 5.1.0（导出）；Maven 多模块 **10 个**（`stock-common` / `stock-market` / `stock-news` / `stock-system` / `stock-ai` / **`stock-export`** / `stock-integration` / `stock-backend` / `stock-job` / `stock-ai-worker`） |
| 数据 | MySQL 8.4（≥8.0.16）/ Redis 8.2 / Flyway V1–V8 |
| 编排 | Docker Compose：mysql → flyway → stock-api / stock-job / stock-ai-worker → frontend(nginx) |

```bash
# 前端
cd frontend && npm install && npm run dev      # http://localhost:5173/market
npm run typecheck && npx vitest --configLoader runner --run

# 后端（测试需 Docker）
export JAVA_HOME="D:/idea/JDK17"               # 必须用 Windows 路径，见环境故障 1
mvn -s backend/settings.xml -f backend/pom.xml test

# 全栈（空库路径）—— 本机 `docker compose` 子命令现已可用（2026-09-23 实测）
docker compose up -d --build                   # 只改了后端/前端代码时：先 build 对应服务再 up -d

# MySQL 从宿主机连（本机 3306 被占用，映射到 3308，见已知问题 #27）
mysql -h 127.0.0.1 -P 3308 -ustock -p

# 全栈（旧库升级路径：导入样本 + baseline）
docker compose -f compose.yaml -f compose.legacy.yaml -p zhishi-legacy up -d
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
                                        │       → 总览的 sectors[] 预览段走同一条路径取 GAINERS 前 3 名（M2-11）
                                        │         共用 QuoteBatch（批次视图，M2-11 下沉至 domain）与 RankingType.sectorOrder()
                                        │ 资讯：NewsProvider（确定性模拟源）→ NewsIngestionService
                                        │       → 来源 ID 幂等（uk_stock_news_source_content）
                                        │       → 内容指纹去重（dedup_status=DUPLICATE + canonical_news_id）
                                        │       → NewsRelationResolver 解析 SECURITY / SECTOR / MARKET 关联
                                        │       → news_source / stock_news / stock_news_relation（V4，落库）
                                        │ NewsQueryService：6 步可见性过滤链（PUBLISHED → ORIGINAL → 来源可用
                                        │       → rights_expire_at 未过 → 只认 CONFIRMED → published_at DESC）
                                        │       一份口径同时支撑 NEWS-01~04 / STK-10 / SEC-07 与 WAT-11 的资讯数
                                        │ AI 上下文（M3-06）：AiContextPreviewService → AiSceneCatalog（5 场景规则表）
                                        │       → AiContextBuilder 一次取数固化 AiContextSnapshot + AiEvidenceCandidate
                                        │       → 行情走 QuoteBatch（与榜单/板块同源）、板块走 SectorQuoteCalculator、
                                        │         资讯走 NewsEvidenceProvider（= NewsQueryService.visible() 之上叠 allow_ai_analysis）
                                        │       → 缺行情/资讯/板块只记 limitations，不抛异常；核心行情缺失时 canGenerate=false
                                        │ LlmProviderPort（端口）→ SimulatedLlmProvider（确定性六章节 + 引用约束 + 故障注入四态）
                                        │       LlmEvidence 类型上不含 URL ——「模型不生成可信 URL」是类型保证而非事后校验
                                        ▼
                              Redis 8.2 ◀──▶ MySQL 8.4
                                   ▲              ▲
                                   └── stock-job（行情每 60s / 资讯每 120s）── SimulatedQuoteProvider
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
资讯接口（M3-04，六个全 PUBLIC）：NEWS-01~04、STK-10、SEC-07 —— **前端已由 M3-05 消费**（`/news` 列表 + 筛选项）
                                        WAT-11 的 latestNewsCount / newsSince 已接真实资讯数（M3-04）
AI 接口（M3-06，两个均需认证）：AI-01 `GET /ai/scenes`、AI-02 `POST /ai/context-previews` —— **前端已由 M3-10 消费**（`/ai` 工作台：场景下拉来自 AI-01，预览与数据缺口来自 AI-02）
配额接口（M3-09，需认证）：USER-07 `GET /users/me/ai-quota` —— **前端已消费**（`/ai` 进页面即读，显示今日剩余与重置时刻）
调用用量：`ai_usage` 每次真实 Provider 调用一行（成功与失败都记），`estimated_cost` 恒 0（无价格表，不做估算）
导出（M3-12，需认证，四个端点全部只认本人）：
  EXP-01 `POST /export-jobs`（202 + `Idempotency-Key`）→ ExportJobService 校验 + 限流（Redis 2/min，计数在幂等回放之后）
         → 作业存 Redis（`export:job:*` + `export:jobs:purge` 有序集，**不建表**）→ 立即返回，生成在另一线程
         → 取数走 StockRankingQueryService **同一条榜单路径**（StockRankingDataset 全量，上限 5,000 行）
         → PoiExportFileWriter 写 xlsx（说明区 + 冻结表头 + autoFilter）→ VolumeExportFileStore 落 `export-files` 卷
  EXP-02 `GET /export-jobs/{id}` 状态（`QUEUED`/`RUNNING`/`COMPLETED`/`FAILED`/`EXPIRED`）
  EXP-03 `GET /export-jobs/{id}/download` 二进制 + `Content-Disposition`（`filename` 与 `filename*` 都给）+ `X-Data-Cutoff-At`
  EXP-04 `DELETE /export-jobs/{id}` 删除
  到期清理：`stock-job` 的 `ScheduledExportSweeper`（记录 48h / 文件 24h，**记录刻意多活一天**才能区分"过期"与"不存在"）
  前端：`useRankingExport` 三段式（创建 → 1s 轮询 → 下载），`apiDownload` 是独立于 JSON 的二进制通道（超时 120s）
```

**不可动摇的架构约束**：前端不直连任何数据源；AI 由 Spring Boot 编排第三方 LLM（不提前拆 FastAPI）；MySQL 无外键，业务写入靠应用层事务 + 乐观锁 + Outbox。

## 9. 待用户处理

1. **配置 GitHub 分支保护**，把 CI 作业设为必需检查（Settings → Branches → Branch protection rules → Require status checks）。
   建议至少勾选 `backend`、`frontend`、`sql`、`sql-legacy-upgrade`。本机无 `gh` CLI，无法代设。

> `.workbuddy-ai/` 已加入 `.gitignore`（第 40 行），无需再处理。

## 10. 下一步

**M1 已完成**（仅剩 M1-07 的分支保护配置，需用户操作）。**M2 全部 11 个任务已完成 —— 市场域纵向补全收尾。**

**M2 的完成含义**：游客主流程（市场总览 → 榜单 / 板块 → 板块详情 → 个股详情 + 顶栏全局搜索）的
**每一个数字都来自真实接口**，且**每一个跳转主键都能被对应的详情接口解析**。
后端 19 个非认证 GET 接口中（M2 的 13 个 + M3-04 新增的 6 个资讯接口），**11 个已被前端消费**
（这个分母只统计**非认证** GET；`/watchlist-groups` 等 `USER` 接口与 M3-06 新增的 AI-01 / AI-02 都需认证，不计入，同 `GlobalSearch.vue` 的说明）：
`/markets/overview`、`/markets/{code}/status`、`/stock-rankings`、`/sector-rankings`、
`/sectors/{id}`、`/sectors/{id}/constituents`、`/securities/search`、
`/securities/{id}/quote`、`/securities/{id}/klines`、`/news`、`/news/options`。

**剩余 8 个已实现但前端尚未消费**——M2 的 4 个各有原因（见下表），资讯域剩 4 个：

| 接口 | 归属 | 前端未消费的原因 |
| --- | --- | --- |
| `GET /markets/{marketCode}/breadth` | MKT-03 | 总览页读的是 `/markets/overview` 里内嵌的 `breadth` 字段，无需再请求 |
| `GET /markets/{marketCode}/turnover-trend` | MKT-04 | 同上，总览页用 `overview.turnover`；**独立趋势接口（含 5D / 20D 档位）尚无页面消费** |
| `GET /sectors` | SEC-01 | 板块页用 `/sector-rankings` 取同一快照下的全部板块行情；`/sectors` 是"管理型选择组件"的入口 |
| `GET /securities` | STK-02 | 同上，无页面消费（全局搜索走 STK-01） |
| `GET /news/{id}`、`GET /news/sync-status` | — | 列表页不需要：NEWS-01 已含契约 §4.3 的全部摘要字段，`dataStatus` / `lastSuccessfulSyncAt` 也与 NEWS-03 同源。**详情页未排期**，列表已能完成「看摘要 → 跳原文」 |
| `GET /securities/{id}/news`、`GET /sectors/{id}/news` | 后续 | 个股页与板块页的资讯段，随这两页的后续工作排期 |

**下一步：M3 已开工（10/12）**，按契约依赖推进：

1. ✅ M3-01 自选分组 CRUD → ✅ M3-02 自选项 CRUD + 排序 + 行情概览 → ✅ M3-03 前端 `/watchlist` 接真实 API
   —— **自选闭环已端到端可用**（后端 12 接口 + 前端页，e2e 实测）
2. ✅ **M3-04 资讯 Provider 抽象 + 模拟源 + 去重 + 标的关联** —— 第 7 个模块 `stock-news`，
   六个接口 + 定时采集落库，e2e 实测通过；同时解开 WAT-11 的 `latestNewsCount` 与 `newsSince`
3. ✅ **M3-05 前端 `/news` 接真实接口** —— 列表接 NEWS-01、筛选项接 NEWS-04，筛选与分页全部走服务端；
   `services/mockApi.ts` **整体删除**（仓库里不再有 mock 通路）；e2e 实测：首屏恰好 2 个请求、
   切类型只发 1 个请求且带 `newsTypes`、关键字搜索保留筛选条件
4. ✅ **M3-06 AI Provider 抽象 + 确定性模拟实现** —— 第 8 个模块 `stock-ai`（31 个 main 类型 + 4 个测试类），
   AI-01 / AI-02 两个接口；e2e 实测 + 受控实验验证 `allow_ai_analysis`；本轮顺带修掉 2 个真实缺陷
   （上下文哈希对数据时间不敏感、区间不合法被报成 `AI_TARGET_INVALID`）
5. ✅ **M3-07 AI 任务编排 + SSE 流式契约** → ✅ **M3-08 报告/证据/反馈持久化（HIS-01~HIS-09 全部收口）**
   → ✅ **M3-09 配额与用量统计** → ✅ **M3-12 行情榜单 Excel 导出（第 10 个模块 `stock-export`）**
   → ⏭ **M3-10 续作**（未接：SSE AI-05、取消/重试/追问 AI-06/07/08）→ **M3-11 后台 admin 最小集**
6. **STK-05 批量行情接口**（已知问题 #10）—— M2-09 遗留的搜索建议无涨跌幅依赖它；
   底层 `SimulatedQuoteSnapshotProvider` 已同时提供单只与整批，实现成本很低
7. **M3 完成后回归 M2 降级字段**：市盈率、市值、业务描述（STK-08/09）、板块走势（SEC-05）、
   搜索建议的涨跌幅（STK-05），以及 WAT-11 的 `latestNewsCount` 渲染（后端已有真实值，
   卡在已知问题 #20 的 `null` 语义未定）

**M3 也是把 M2-08 本轮降级掉的字段逐个填回来的阶段**：市盈率、市值、业务描述、所属板块（STK-08/09）、
关联资讯（STK-10）、板块走势（SEC-05）、搜索建议的涨跌幅（STK-05），以及 WAT-11 的 `latestNewsCount`（后端已有真实值；前端因已知问题 #20 的 `null` 语义未定而仍未渲染）。

> 仍待用户操作：GitHub 分支保护配置（见第 9 节）；`.worktrees/` 残留 2 个被进程占用的 `element-plus` 文件（128K），关闭编辑器后可手动删除。
