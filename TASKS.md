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
- [x] **M1-09** P1 文档同步与补全 — 已完成，见下方详情
- [x] **M1-10** P2 移除未使用的 element-plus — 已完成（typecheck / 35 测试 / build 全绿）
- [x] **M1-11** P2 修复 AppShell 测试的 router 注入警告 — **无需改动**：该警告已由合流带入的 `e769850` 修复
  - 复核方式：跑 `vitest --run` 全量测试，输出中 `warn|injection|not found` 匹配数为 **0**
  - 对照实验：临时挂载一个未注入 router 却调用 `useRouter()` 的组件，vitest **确实会**输出 `[Vue warn]: injection "Symbol(router)" not found` → 证明「0 警告」是真实的，而非工具静默

### M1 执行中新发现的任务

- [x] **M1-12** P1 校验 `preflight_existing_schema.sql` 在旧库升级前的实际行为 — 已完成
  - 做法：起一个仅导入旧库样本、未执行 Flyway 的 MySQL，先跑 preflight，再注入违规数据重跑
  - **结果**：脚本 9 段查询全部可执行（无语法错误）。干净样本下 `missing_count = 0` 且各 blocking 段无输出；
    注入违规数据后，邮箱重复、角色名重复、权限 code 重复**三项 blocking 全部被检出**，
    重复关系 / 空关系 / 无法映射日志引用等 informational 项也被正确报出
  - **新增测试资产** `sql/tests/preflight_negative_case.sql`（负向用例，已纳入 CI）
  - **发现**：第 8 段检查 `CHAR_LENGTH(block_label) > 20`，但该列实际是 `varchar(10)`，
    因此**永远不会触发**。属设计上的防御性检查（作者注释已说明"理论上不应返回记录"），非缺陷，但已记录
- [x] **M1-13** P2 补建 CHANGELOG.md — 已完成（按日期分段，覆盖全部 26 条提交）
- [x] **M1-14** P1 在 CI 中增加「旧库升级路径」作业 — 已完成
  - 新增 `sql-legacy-upgrade` 作业（MySQL 8.4 service + 6 步骤）：
    导入样本 → preflight → Flyway baseline+migrate → 迁移后校验断言 → preflight 负向用例
  - 断言设计：`post_migration_validation.sql` 除「外键计数」与 informational 段外都应返回 0 行，
    故 `-N` 模式下输出应恰好为 `0`，否则判失败
  - **本地已完整复现该作业全部 5 步并验证通过**（含负向用例 4 项断言）
- [x] **M1-15** P2 统一本机与文档的 Compose 调用方式 — 已完成
  - 本机 `docker compose` 子命令**不可用**，只有独立命令 `docker-compose`（v5.5.1）
  - 已统一 `compose.legacy.yaml`、路线图、README、`backend/README.md` 中的调用方式，并注明两者等价

### M1 收尾阶段新发现

- [x] **M1-16** P1 修复本地 `npm run dev` 无法联调真实接口 — 已完成（采用方案 1）
  - 现象：dev 模式下请求 `/api/v1/markets/overview` 返回 **SPA 的 index.html**（HTTP 200 但内容为 HTML），前端会按 JSON 解析失败
  - 根因：`vite.config.ts` **未配置 `server.proxy`**，且后端**全无 CORS 配置**（全仓库 grep `cors|allowedOrigin|CrossOrigin` 零命中）
  - 修复：① `vite.config.ts` 增加 `server.proxy` 把 `/api` 转发到 `http://localhost:8080`；
    ② `compose.yaml` 为 stock-api 补上端口映射 `${API_PORT:-8080}:8080`（此前未暴露，代理无处可转）；
    ③ `.env.example` 增加 `API_PORT`
  - 验证：dev server 下 `GET /api/v1/markets/overview` 返回**真实 JSON**（此前为 HTML），typecheck 通过

## M2：市场域纵向补全（游客主流程全真实）

- [x] **M2-01** P0 交易日历与市场状态（MKT-02） — 已完成，见下方详情
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

## M1 收尾交付详情（M1-09 / M1-10 / M1-11 / M1-13 / M1-15）

| 任务 | 产出 | 验证 |
| --- | --- | --- |
| M1-09 | 新建根 `README.md`、`backend/README.md`、`frontend/.env.example`；重写已过时的 `frontend/README.md` | 内容逐项对照源码核实（模块数、配置项默认值、路由数量、命令参数） |
| M1-10 | 从 `package.json` 移除 `element-plus`，`npm install` 同步 lock（移除 20 个包） | typecheck 0 错误 · vitest 13 文件 / 35 测试全绿 · build `✓ built in 1.31s` |
| M1-11 | 无代码改动（警告已由合流带入的 `e769850` 修复） | 全量测试输出中 `warn\|injection\|not found` 匹配数为 0；并用对照实验证明 vitest 会显示该警告 |
| M1-13 | 新建 `CHANGELOG.md`（Keep a Changelog 结构，按日期分段，覆盖全部 26 条提交） | 与 `git log` 逐条核对 |
| M1-15 | 统一 `compose.legacy.yaml`、路线图、`README.md`、`backend/README.md` 的 Compose 调用方式为 `docker-compose`，并注明两者等价 | 全仓库 grep 复核 |

**文档纠错记录**（旧文档与代码不符之处，已一并修正）：

1. `frontend/README.md` 称"当前通过 Mock API 提供完整演示数据，后续可将 mockApi 替换为真实适配器"——实际 `/market` 与 `/login` 已接真实 API。
2. 同文件路由表漏列 `/sectors/:id`（实际 11 个页面路由，非 10 个）。
3. 同文件的命令示例用 PowerShell 语法且缺少必需的 `--configLoader runner` 参数。

---

## 阻塞项

| 阻塞 | 影响任务 | 需要 |
| --- | --- | --- |
| GitHub 分支保护未设置 | M1-07 的「必需检查」语义 | 用户在仓库 Settings → Branches 配置（本机无 `gh` CLI，无法代设） |
| 本机无 PowerShell 7 | 本地运行 `validate_migrations.ps1` | 仅影响本地；CI 的 ubuntu-latest 预装 pwsh 7，可正常执行 |
| 真实行情/资讯/LLM Provider 未就位 | M2-*、M3-04~M3-10 | 不阻塞开发，统一以模拟 Provider 落地 |

---

## M2-01 交付详情

| 项 | 内容 |
| --- | --- |
| 接口 | `GET /api/v1/markets/{marketCode}/status`（PUBLIC，可选 `date`） |
| 返回字段 | `marketCode`、`tradeDate`、`isTradingDay`、`sessionStatus`、`currentSession`、`nextSessionAt`、`calendarSourceTime` |
| 粒度设计 | `sessionStatus`（5 值粗粒度）+ `currentSession`（7 值细粒度），映射关系只在 `TradingSession` 枚举里维护一处 |
| 时段划分 | 盘前 / 开盘集合竞价 09:15–09:25 / 静默 09:25–09:30 / 上午连续 09:30–11:30 / 午休 11:30–13:00 / 下午连续 13:00–14:57 / 收盘竞价 14:57–15:00；15:00 后无窗口，按 `CLOSED` 兜底 |
| 数据来源 | 新增端口 `TradingCalendarProvider`，当前实现 `SimulatedTradingCalendarProvider`（周末 + 可配置节假日；节假日由 `stock.market.holidays` 注入，**不硬编码未经核实的法定节假日日期**） |
| 枚举统一 | 删除 `MarketOverview.SessionStatus`，MKT-01 与 MKT-02 共用新的 `MarketSessionStatus`（超集）；**MKT-01 的 JSON 输出逐字不变**（仍 `TRADING`/`CLOSED`/`BREAK`） |
| 历史/未来日期 | 只有查询日等于「今天」才按当前时刻推导时段；查历史或未来日期整日返回 `CLOSED`，`nextSessionAt` 为 `null` |
| 错误契约 | 不受支持的市场 → 404 `MARKET_NOT_FOUND`；`date` 格式非法 → 400 `INVALID_REQUEST` |
| 测试 | 后端 **71 测试全绿**（原 45 + 新增 26）：`MarketStatusQueryServiceTest` 16 项（含 8 个时段边界与 4 条 `nextSessionAt` 分支）、`SimulatedTradingCalendarProviderTest` 7 项、契约测试 +3 项 |
| 前端 | `domain.ts` 新增 `MarketSessionStatus` / `TradingSession` 类型，`marketStatus` 放宽为 5 值；typecheck 0 错误、13 文件 / 35 测试全绿 |

### 执行中发现并修复的两个问题

**1. 独立 MockMvc 的日期序列化与线上不一致（新发现）**
`MockMvcBuilders.standaloneSetup` 的默认 `ObjectMapper` 未注册 `JavaTimeModule`，且 Spring 的 `Jackson2ObjectMapperBuilder` 默认**也不关闭** `WRITE_DATES_AS_TIMESTAMPS`（该开关是 Spring Boot 自动配置打开的）。结果是 `LocalDate` 被序列化成 `[2026,9,11]`，与线上 ISO 字符串不符。
修复：契约测试显式用 `Jackson2ObjectMapperBuilder.json().featuresToDisable(WRITE_DATES_AS_TIMESTAMPS)` 构造 mapper 并注入转换器，使断言对齐真实线上格式。

**2. `MarketStatus` 的 JSON 字段名**
record 组件为 `tradingDay`（Java 访问器 `tradingDay()`），JSON 名由 `@JsonProperty("isTradingDay")` 显式指定，与契约逐字一致。Java 访问器与 JSON 名解耦，避免依赖 Jackson 对 `is` 前缀的启发式推断。

---

## 已完成

- [x] 阶段 0 只读审计（2026-09-19）
- [x] 阶段 1 交付路线图（2026-09-19）
- [x] M1-01 / M1-02 / M1-03 / M1-04 / M1-05 / M1-06 / M1-09 / M1-10 / M1-11 / M1-12 / M1-13 / M1-14 / M1-15 / M1-16（2026-09-19）
- [x] M2-01（2026-09-19）
