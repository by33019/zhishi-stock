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
- [x] **M2-02** P0 市场广度（MKT-03） — 已完成，见下方详情
- [x] **M2-03** P0 成交趋势（MKT-04） — 已完成，见下方详情
- [x] **M2-04** P0 证券主数据与搜索建议 — 已完成，见下方详情
- [x] **M2-05** P0 个股快照与日/周/月 K 线（STK-04 / STK-07） — 已完成，见下方详情
- [x] **M2-06** P0 榜单（涨跌幅/成交额/换手）+ 分页筛选 — 已完成，见下方详情
- [x] **M2-07** P1 板块排行、详情与成分股 — 已完成，见下方详情
- [x] **M2-08** P0 前端接入 rankings / sectors / sectors:id / stocks:id — 已完成，见下方详情
- [x] **M2-09** P1 全局搜索接真实接口 — 已完成，见下方详情
- [x] **M2-10** P0 市场状态真实化（关闭已知问题 #7 / #12） — 已完成，见下方详情
- [x] **M2-11** P0 总览板块预览真实化（首页三张卡片 404） — 已完成，见下方详情

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

## M2-02 交付详情

| 项 | 内容 |
| --- | --- |
| 接口 | `GET /api/v1/markets/{marketCode}/breadth`（PUBLIC，可选 `snapshotTime`） |
| 返回字段 | `marketCode`、`riseCount`、`fallCount`、`flatCount`、`suspendedCount`、`limitUpCount`、`limitDownCount`、`totalCount`、`dataTime`、`dataStatus`、`lastSuccessfulSyncAt`、`snapshotVersion` |
| 口径保证 | 全部计数来自**同一个** `MarketOverview` 快照对象；"不混用不同批次行情"是构造保证，不是约定 |
| 计数定义 | 归类优先级：停牌 → 涨停 → 跌停 → 上涨 → 下跌 → 平盘。`limitUpCount ⊆ riseCount`、`limitDownCount ⊆ fallCount`（与"涨停家数是上涨家数子集"的市场惯例一致） |
| 涨跌停判定 | **按价格而非比例**。交易所把限价四舍五入到分后该价格即为上限：前收 `10.03` 的涨停价是 `11.03`，涨幅只有 `9.97%`，按比例判定会漏掉这个涨停 |
| 规则缺失处置 | 匹配不到规则时**不计入涨跌停**，但仍按价格计入涨/跌/平。把"无规则"当成"不限幅"会把一只 10% 上涨的普通股算成涨停，是更严重的错误 |
| 规则匹配 | 静态属性全等 → 生效窗口 → 上市天数窗口 → `priorityNo` 最小 → `ruleCode` 字典序。最后一步保证结果确定性，不依赖集合遍历顺序 |
| 数据来源 | 新增端口 `LimitRuleProvider` / `SecurityQuoteProvider`；模拟实现 `SimulatedLimitRuleProvider`（10 条稳定规则）、`SimulatedSecurityQuoteProvider`（5149 只确定性个股行情） |
| 模拟规则集 | 主板 ±10%（ST ±5%）、创业板/科创板 ±20%（ST 同）、北交所 ±30%。**不编码"新股首日不限幅"**——该条款随板块与时期变化，无法核实到可写进代码的程度；模型与匹配器保留了 `noPriceLimit` 与上市天数窗口能力并用合成规则覆盖 |
| 时间回溯 | `snapshotTime` 给出时：实时存储命中且 `dataTime <= snapshotTime` 才可用，否则回到归档取"不晚于该时刻的最近一条"并标记 `STALE`；都没有 → 503 `MARKET_DATA_UNAVAILABLE` |
| 实测分布 | 5149 只 → 涨 2976 / 跌 1915 / 平 209 / 停牌 49 / 涨停 46 / 跌停 43（ST 91 只）。与旧硬编码 `2876 / 1924 / 164 / 82 / 7` 量级接近 |
| 测试 | 后端 **120 测试全绿**（M2-01 的 71 + 新增 49）：`LimitRuleMatcherTest` 9 项、`BreadthCalculatorTest` 11 项、`MarketBreadthQueryServiceTest` 7 项、`SimulatedLimitRuleProviderTest` 7 项、`SimulatedSecurityQuoteProviderTest` 11 项、契约测试 +2 项、`JdbcMarketOverviewArchiveTest` +1 项 |
| 前端 | `domain.ts` 的 `BreadthData` 补 `suspendedCount`（同步 MKT-01 响应新增字段）；typecheck 0 错误、13 文件 / 35 测试全绿 |

### 关键设计取舍

**1. 生成与计数互为逆运算**
`SimulatedSecurityQuoteProvider` 先为每只证券定出一个**目标状态**（涨停/上涨/平盘/下跌/跌停/停牌），再按匹配到的规则**反推价格**：涨停股最新价恰好等于涨停价，跌停股恰好等于跌停价，涨/跌股严格落在限价之内。于是"按规则计数"这句话可以被直接验证——单测断言「落在限价上的非停牌证券数 == `limitUpCount`」，如果生成器有一分钱的偏差，这个等式立刻破裂。

**2. `totalCount` 派生而不落盘**
`BreadthData.totalCount()` 标 `@JsonIgnore`：快照会被持久化并在读取时反序列化，派生字段一旦落盘就会在归档里形成第二个可能与四态不一致的真相。而 `MarketBreadth.totalCount` 是响应视图、不会被反序列化，因此正常序列化。

**3. 快照解析逻辑只有一处**
`MarketBreadthQueryService` 复用 `MarketOverviewQueryService.getOverview(marketCode, snapshotTime)`，MKT-01 与 MKT-03 不会各自演化出一套"实时 / 归档 / 过期"语义。原 `getOverview(marketCode)` 行为逐字不变。

---

## M2-03 交付详情

| 项 | 内容 |
| --- | --- |
| 接口 | `GET /api/v1/markets/{marketCode}/turnover-trend`（PUBLIC，`range` 必填，`interval` 可选） |
| 返回字段 | `marketCode`、`range`、`interval`、`unit`、`dataCutoffAt`、`points[]{time, tradeAmount, tradeVolume}` |
| 档位 | `TODAY`（分钟粒度，`interval` 缺省 `1m`，可选 `1m\|5m\|15m\|30m\|60m`）、`5D`、`20D`（日粒度） |
| 点位语义 | `TODAY` 为**从开盘累计**到该时刻的成交额/量（单调不减）；`5D`/`20D` 为该交易日全天总量 |
| 点位时间 | 取**区间结束时刻**而非开始时刻——这样每个点的时间就是它累计值的生效时刻，`dataCutoffAt` 天然等于最后一个点的时间 |
| 未完成区间 | **只返回已走完的区间**，不返回未完成分钟的伪数据（与 STK-06 口径一致）。`interval=5m` 在 10:02 只返回 6 个点 |
| 分钟网格 | 连续竞价两段 `09:30–11:30`、`13:00–15:00`（240 分钟）；集合竞价成交并入相邻点，不单独建模 |
| 权重模型 | `24 + 60/(1+i/5) + 60/(1+(239-i)/5) + (1+mix(i)%12)`，刻画"开盘尾盘放量、午间清淡"；**全程整数运算**，刻意避开 `Math.sin`/`Math.exp`（允许跨平台 1 ulp 差异，会让确定性在 CI 与本机之间失效） |
| 累计算法 | `全天总量 × 累计权重 / 总权重`，而非逐分钟值相加——后者会因逐项截断丢末位，导致收盘点与日粒度对不上 |
| 兜底规则 | `TODAY` 在盘前 / 非交易日 / 刚开盘时回落到最近一个交易日的全天序列（沿用 MKT-01「非交易日返回最近有效收盘快照」语义），避免空曲线 |
| 日粒度 | 起点 = 最近一个**已经收盘**的交易日（盘中与盘前都回退到上一交易日）——把半天的成交量画进日线图就是一次虚假缩量 |
| 数据来源 | 新增端口 `TurnoverTrendProvider`，实现 `SimulatedTurnoverTrendProvider`（确定性，无随机数） |
| 参数校验 | 未知 `range` / 非法 `interval` / 日粒度传 `interval` → 400 `INVALID_REQUEST`；不受支持市场 → 404 `MARKET_NOT_FOUND` |
| 测试 | 后端 **145 测试全绿**（M2-02 的 120 + 新增 25）：`TurnoverTrendQueryServiceTest` 8 项、`SimulatedTurnoverTrendProviderTest` 13 项、契约测试 +4 项 |
| 前端 | `domain.ts` 新增 `TurnoverRange` / `TurnoverTrend` / `TurnoverTrendUnit` / `TurnoverTrendPoint` 类型（页面接入归 M2-08）；typecheck 0 错误、13 文件 / 35 测试全绿 |

### 关键设计取舍

**1. 往返一致性：今日曲线的终点 == 同一天在日粒度上的点**
模拟器对"某交易日的全天总量"使用同一个确定性算法，盘中曲线按累计权重分摊到各分钟，
累计权重在收盘时恰好等于总权重，因此终点精确等于全天总量。单测直接断言这条等式——
若累计算法改成逐项相加，等式会因截断误差破裂。

**2. `time` 与 `unit` 的形态选择**
`time` 随 `range` 在 datetime / date 之间切换，不存在单一 Java 时间类型能精确表达两者，
因此用 `String` 并在生成处显式格式化，比引入联合类型或统一成近似时刻更诚实。
`unit` 同时承载金额与数量两种单位，单个字符串无法表达，故按字段拆成对象。

**3. `range` / `interval` 在 Controller 层声明为 `String`**
非法取值要返回业务码 `INVALID_REQUEST`；若绑定为枚举，Spring 会抛
`MethodArgumentTypeMismatchException`，把"取值不在白名单"混同为"参数格式错误"。

**4. 日粒度传 `interval` 明确报错而非静默忽略**
客户端以为参数生效、实际被丢弃，是比报错更糟的结果。与文档对 STK-07 的要求同源。

**5. 未落库**
`stock_minute_bar` / `stock_kline_day` 是**证券级**表，市场级趋势需对全市场证券跨表聚合
（约 120 万行/交易日），MVP 阶段代价与收益不匹配；个股级落库由 M2-05 承担。

---

## M2-04 交付详情

| 项 | 内容 |
| --- | --- |
| 接口 | `GET /api/v1/securities/search`（STK-01，PUBLIC）、`GET /api/v1/securities`（STK-02，PUBLIC） |
| STK-01 参数 | `q`（必填，1–50 字符）、`types`、`exchangeCodes`（逗号分隔）、`limit`（1–20，默认 10） |
| STK-01 返回 | `items[]{security, matchedField, highlight}`；`matchedField ∈ CODE\|NAME\|PINYIN\|PINYIN_ABBR` |
| STK-02 参数 | `keyword`、`securityType`、`exchangeCode`、`boardCode`、`listingStatus`、`sectorId`、`page`、`size`、`sort` |
| STK-02 返回 | `PageData<SecuritySummary>`：`items`、`page`、`size`、`total`、`totalPages`、`hasNext` |
| 匹配优先级 | `CODE`（代码 / `fullSymbol` **前缀**）→ `NAME`（名称**包含**）→ `PINYIN` → `PINYIN_ABBR`；一只证券只产生一条结果 |
| 排序确定性 | 搜索按「`matchedField` 优先级 → `fullSymbol` 升序」；列表按白名单字段，默认 `fullSymbol,asc` |
| 数据来源 | 新增端口 `SecurityMasterProvider`，实现 `SimulatedSecurityMasterProvider`（**投影**自行情全集，不重复定义代码段） |
| 参数校验 | `q` 长度、`limit`、`page`、`size`、`sort` 字段与方向非法 → 400 `INVALID_REQUEST`；**筛选值不存在不报错**，返回空结果 |
| 测试 | 后端 **189 测试全绿**（M2-03 的 145 + 新增 44）：`SecurityQueryServiceTest` 28 项、`SimulatedSecurityMasterProviderTest` 8 项、契约测试 8 项 |
| 前端 | `domain.ts` 新增 `PageData` / `SecuritySummary` / `SecuritySearchMatch` / `SecuritySearchResult` / `SecurityListQuery` 等类型（页面接入归 M2-08 / M2-09）；typecheck 0 错误、13 文件 / 35 测试全绿 |

### 关键设计取舍

**1. 主数据**投影**自行情全集，不复制代码段**
`SimulatedSecurityMasterProvider` 不自己定义证券全集，而是把 `SecurityQuoteProvider.fetchUniverse()`
的结果映射成 `SecuritySummary`。代码段一旦在两处各写一遍，改动其中一处就会让"主数据"与"广度计数"
指向不同的证券全集，**且没有任何测试会红**。

**2. 筛选值不校验合法性，排序字段必须校验**
`types=ETF` 是合法取值，只是当前没有 ETF——报 400 会把"没有数据"错报成"参数非法"，故不匹配即空。
但 `sort` 字段被静默忽略时，调用方拿到的是"顺序不对但看起来正常"的响应，极难排查，故白名单外直接报错。

**3. 拼音保留能力但不填值**
`SecuritySummary` 含 `pinyin` / `pinyinAbbr` 组件并标 `@JsonIgnore`——JSON 输出严格等于文档 §4.1 的
11 个字段。合成名称（`模拟证券600000`）没有可核实的拼音，编一份假拼音会污染真实逻辑；
单测用一只带拼音的桩数据（贵州茅台）覆盖 `PINYIN` / `PINYIN_ABBR` 两条分支，能力不会腐烂。

**4. `PageData` 落在 `stock-common`**
它是跨域共用的响应外壳（榜单、资讯、AI 历史都要用），放进行情域会让别的域反向依赖它。

**5. `sectorId` 当前必然返回空页**
板块关系数据在 M2-07 之前不存在，"没有任何证券属于该板块"在当下是事实而非错误。
保留参数是为了契约完整，M2-07 落地后自然生效。

**6. 验收标准「搜索 P95 < 500ms」以宽松冒烟测试落实**
内存线性扫描 5149 条，实测单次在毫秒级。测试取 100 次采样的 P95 并断言 < 500ms——
两个数量级余量，CI 上不会抖动，但搜索一旦退化成 O(n²) 或引入阻塞 IO 会立刻变红。

---

## M2-05 交付详情

| 项 | 内容 |
| --- | --- |
| 接口 | `GET /api/v1/securities/{securityId}/quote`（STK-04，PUBLIC）、`GET /api/v1/securities/{securityId}/klines`（STK-07，PUBLIC） |
| STK-04 返回 | `QuoteSnapshot`：`security`、前收 / 开 / 高 / 低 / 最新价、涨跌额与幅度、量额、换手率、`dataTime`、`serverTime`、`sequence`、`dataStatus`、`delaySeconds` |
| STK-07 参数 | `period=DAY\|WEEK\|MONTH`（必填）、`startDate`、`endDate`（缺省最近 **120 个交易日**）、`adjustment`（缺省 `NONE`） |
| STK-07 返回 | `KlineSeries`：`security`、`period`、`adjustment`、`dataCutoffAt`、`dataStatus`、`points[]{time, 开高低收, 前收, 涨跌额与幅度, 量额, 换手率, qualityStatus}` |
| 数据来源 | 新增端口 `QuoteSnapshotProvider` / `KlineProvider`，实现 `SimulatedQuoteSnapshotProvider` / `SimulatedKlineProvider`；价格算法抽为共享的 `SimulatedPriceSeries`，取数辅助 `SimulatedMarketAccess` |
| 参数校验 | `securityId` 不存在 → 404 `SECURITY_NOT_FOUND`；`period` 非法 / 日期格式错 / 起止颠倒 → 400 `INVALID_REQUEST`；跨度超限 → 400 `KLINE_RANGE_TOO_LARGE`；**不支持的复权方式 → 400 `ADJUSTMENT_NOT_SUPPORTED`（不静默替换）** |
| 测试 | 后端 **241 测试全绿**（M2-04 的 189 + 新增 52）：`SecurityDetailQueryServiceTest` 17 项、`SimulatedKlineProviderTest` 16 项、`SimulatedQuoteSnapshotProviderTest` 12 项、契约测试新增 7 项 |
| 前端 | `domain.ts` 新增 `QuoteSnapshot` / `KlinePeriod` / `KlineAdjustment` / `KlineQualityStatus` / `KlinePoint` / `KlineSeries` / `KlineQuery`；原型同名类型改名 `MockKlinePoint`；typecheck 0 错误、13 文件 / 37 测试全绿 |
| 设计文档 | `docs/superpowers/specs/2026-09-19-security-detail-and-klines.md`（311 行） |

### 关键设计取舍

**1. K 线按需生成，不预生成全市场历史**
5149 只 × 5 年 ≈ 640 万点，内存里既存不下也不该存。Provider 给定证券与区间现算，
默认区间单次约 120 个点。代价是每次请求要扫一遍行情全集来定位证券——与 M2-04 同构，
已有 P95 < 500ms 的证据。

**2. 价格序列末端锚定 + 向前倒推**
硬约束是「日 K 最后一根收盘价 == 该证券快照的最新价」，否则会出现"头部显示 1580、
K 线末端却是 1543"的自相矛盾。而 `latestPrice` 由涨跌停规则反推得出、**不可改**
（它是广度计数的输入），因此序列从最近交易日向前倒推，倒数第二根直接钉在前收价上。
**已知代价：跨交易日会漂移**——`last` 前移一天，同一历史日期的价格随之变化。
这是有意接受的取舍，接入真实数据源后自然消失（见 spec §11）。

**3. 快照与 K 线共用一份价格算法**
`SimulatedPriceSeries` 被两个 Provider 共用。若各写一遍，改一处就会让"行情头部"与
"K 线末端"分叉，**且没有任何测试会红**。同理，证券身份一律投影自 `SecurityMasterProvider`，
不自己拼一份。

**4. 周 / 月 K 由日 K 聚合，不独立生成**
两种周期是同一份数据的两种切法，必须永远自洽。`time` 取该周期**最后一个交易日**
（沿用 M2-03「点位取区间结束时刻」原则），`dataCutoffAt` 因此天然等于最后一个点的时刻。
空周 / 空月不产生点，不做自然日补齐（PRD 明确）。

**5. 不支持的复权方式报错而非静默降级**
§8.3 明确要求"不支持的复权方式返回明确错误而非静默替换"。`adjustment=FORWARD` 直接 400——
调用方以为拿到前复权数据、实际拿到不复权数据，是最难排查的一类问题（同 M2-03 的 `interval`）。

**6. 三个 400 共用一个异常类**
`INVALID_REQUEST` / `KLINE_RANGE_TOO_LARGE` / `ADJUSTMENT_NOT_SUPPORTED` 的 HTTP 状态相同，
只有业务码不同。拆成三个异常类只会让处理器里出现三段几乎相同的代码；业务码由异常自身携带，
处理器不做 `instanceof` 推断。

**7. 涨跌停价夹取开高低**
`high` / `low` 被夹在涨跌停价之内，否则会出现"K 线最高价超过涨停价"这种一眼假的数据，
并与 M2-02 的涨跌停计数口径冲突。

**8. 前端原型同名类型改名 `MockKlinePoint`**
`domain.ts` 里原有一个原型阶段的 `KlinePoint`（字段简写、价格是 `number`）。与契约类型同名
会触发 TypeScript 的**声明合并**，让 mock 数据因"缺少契约字段"而报错。改名后两者并存，
M2-08 接入真实接口时由契约类型取代原型。

---

## M2-06 交付详情

| 项 | 内容 |
| --- | --- |
| 接口 | `GET /api/v1/stock-rankings`（QTE-01，PUBLIC） |
| 参数 | `rankingType=GAINERS\|LOSERS\|TURNOVER`（必填）；可选 `exchangeCodes`、`boardCodes`（逗号分隔多值）、`sectorId`、`excludeSt`（默认 `false`）、`excludeSuspended`（默认 **`true`**）、`page`（默认 1）、`size`（默认 20，上限 100） |
| 返回 | **扁平** `data`：`items[]{QuoteSnapshot}`、`page`、`size`、`total`、`totalPages`、`hasNext`、`rankingType`、`snapshotVersion`、`dataTime`、`dataStatus` |
| 排序口径 | 涨幅榜按 `changeRate` 降序、跌幅榜升序、成交额榜按 `tradeAmount` 降序；统一兜底键 `security.fullSymbol` 升序（**不随主键方向翻转**） |
| 数据来源 | 新增端口 `QuoteSnapshotBatchProvider`，由 `SimulatedQuoteSnapshotProvider` 与单只查询**共用同一实例与同一装配方法** |
| 参数校验 | `rankingType` 缺失 / 非法 → 400 `INVALID_REQUEST`；`page < 1`、`size` 越界 → 400 `INVALID_REQUEST`；筛选值不存在 → **200 + 空页**（不报错） |
| 测试 | 后端 **286 测试全绿**（M2-05 的 241 + 新增 45）：`StockRankingQueryServiceTest` 22 项、`SimulatedQuoteSnapshotBatchProviderTest` 9 项、`RankingControllerContractTest` 12 项、`SimulatedQuoteProviderTest` 新增 2 项 |
| 前端 | `domain.ts` 新增 `RankingType` / `StockRanking` / `RankingQuery`；typecheck 0 错误、13 文件 / 37 测试在默认时区与 `TZ=UTC` 下均全绿 |
| 顺带修复 | `SimulatedQuoteProvider.rankings()` 由三个写死常量改为**投影自涨幅榜前 3 名**（见取舍 7） |
| 设计文档 | `docs/superpowers/specs/2026-09-19-stock-rankings.md`（11 节） |

### 关键设计取舍

**1. 排序 / 筛选 / 分页放应用层，端口只提供整批快照**
契约要求"整个榜单使用同一已完成快照版本"。把这三件事留在用例层、让端口**不接收筛选条件**，
这条要求就由接口形状决定，而不是依赖实现方记得只取一个批次。附带好处是排序口径可以脱离
数据源测试。代价：真实 Provider 若用 SQL `ORDER BY ... LIMIT` 实现，应用层这套逻辑会退化为
参考实现而非实际执行路径。

**2. 排序键必须解析成 `BigDecimal`**
`changeRate` / `tradeAmount` 是十进制定点**字符串**。按字典序比较是错的——
`"0.10"` 的字典序小于 `"0.0218"`，数值上却更大。这是本轮最容易埋雷的地方，
`RankingType.order()` 里统一解析后比较。

**3. 兜底键不随主键方向翻转**
涨幅榜与跌幅榜的主键方向相反，但同值项的先后必须**一致**（都用 `fullSymbol` 升序），
否则"排序稳定"就是空话。PRD §7.3 QTE-02 明确要求排序稳定。

**4. 两个筛选默认值不同是有意的**
`excludeSuspended` 默认 `true`（PRD：「停牌和无有效价格默认排除」），
`excludeSt` 默认 `false`（PRD 未给默认，且"是否回避 ST"是投资者的主动选择）。
因此 `RankingCriteria` 用 `Boolean` 包装类型——用 `boolean` 会把"未传"悄悄变成 `false`，
让停牌股意外进入榜单。

**5. 无有效排序键的行不进榜**
涨跌幅榜要求该行有涨跌幅，成交额榜要求有成交额；PRD 的「无有效价格默认排除」说的就是这件事。
先过滤再排序，比较器因此不必处理 `null`。

**6. 扁平响应 vs 嵌套 `PageData`**
契约只写了「`PageData<QuoteSnapshot>`、`rankingType`…」，没写明外层形状。选扁平让前端少一层
解引用，代价是 `StockRanking` 里重复了 5 个分页字段；因此分页算术仍由 `PageData.slice` 负责，
只有一份实现。

**7. 顺带修掉总览榜单预览的两个可见缺陷**
`SimulatedQuoteProvider.rankings()` 原本是三个写死常量，`securityId` 用的是
**主数据里不存在**的 `stock-600519`（主数据实际是 `sim-600519`），导致前端首页
`MarketOverview.vue` 渲染的 `/stocks/stock-600519` 链接 **404**；且预览值与榜单页必然对不上。
改为投影自涨幅榜前 3 名后，两处一致性由构造方式保证。`sparkline` 只给「开盘 → 最新价」
两个**真实**点位——模拟源没有分钟数据，编一条假的日内路径不如给一条真实的当日方向线。

**8. 一个 Bean 同时满足两个端口，不声明别名 Bean**
`quoteSnapshotProvider` 只声明一个具体类型的 Bean，依赖方按自己需要的端口声明参数，
Spring 按可赋值性解析到同一实例。若额外声明两个返回接口类型的别名 Bean，Spring 按具体类型
解析时会看到两个候选（别名 Bean 的运行时类型同样是 `SimulatedQuoteSnapshotProvider`），
反而需要 `@Qualifier` 消歧——这一步实测踩过，见下。

**9. 多值筛选的解析规则收敛到 `QueryParameters`**
`exchangeCodes=SH,,SZ` 在 STK-02 与 QTE-01 上必须给出同一结果（裁剪空白、丢弃空项、大小写不敏感）。
原本 `SecurityQueryService` 里有一份私有实现，本轮抽成包级 `QueryParameters` 并让两处共用——
各写一遍的话，两者会各自演化且**没有任何测试会红**。

---

## M2-07 交付详情

| 项 | 内容 |
| --- | --- |
| 接口 | `GET /api/v1/sectors`（SEC-01）、`/sector-rankings`（SEC-02）、`/sectors/{id}`（SEC-03）、`/sectors/{id}/quote`（SEC-04）、`/sectors/{id}/constituents`（SEC-06），全部 PUBLIC |
| 参数 | SEC-01：`sectorType`、`parentId`、`keyword`、`status`（默认 `ACTIVE`）；SEC-02：`sectorType`、`rankingType`（默认 `GAINERS`）、`page`、`size`；SEC-06：`effectiveDate`、`rankingType`、`page`、`size` |
| 返回 | SEC-01 `data.items[]{Sector}`；SEC-02 **扁平** `data`：`items[]{SectorQuote}` + 分页 + `sectorType`/`rankingType`/`snapshotVersion`/`dataTime`/`dataStatus`；SEC-03 `data{sector, parent, quote}`；SEC-04 `data{SectorQuote}`；SEC-06 标准 `PageData<SectorConstituent>`，每项含嵌套 `quote` + `relationType` + `isPrimary` + `contributionRank` |
| 数据来源 | 新增端口 `SectorProvider`（`findAll` + 按板块分组的 `memberships`），实现 `SimulatedSectorProvider`：5 个大类 + 20 个二级行业 + 8 个概念 + 6 个地域 = **39 个板块**，成分关系投影自 `SecurityMasterProvider`，只由 `securityCode` 哈希导出（与列表顺序解耦） |
| 统计口径 | 唯一实现在 `SectorQuoteCalculator`：`companyCount` 含停牌；`averagePrice` / `changeRate` 为**非停牌**成分股等权平均；量额用 `BigDecimal` 累加且不计停牌；领涨/领跌股与 `contributionRank` **共用同一个"有效行情"判定与同一个比较器** |
| 参数校验 | `sectorType` / `status` / `rankingType` 不在白名单、`page`/`size` 越界、`effectiveDate` 格式非法 → 400 `INVALID_REQUEST`；`sectorId` 不存在 → 404 `SECTOR_NOT_FOUND`；SEC-04/06 目标板块停用 → 404 `SECTOR_INACTIVE`；SEC-06 无成分关系 → 404 `SECTOR_CONSTITUENTS_MISSING`；成分全停牌 → 503 `SECTOR_QUOTE_NOT_AVAILABLE`；`parentId`/`keyword` 无匹配 → 200 + 空结果 |
| 顺带修复 | ① 拆掉 STK-02 与 QTE-01 里 `sectorId` 的 `List.of()` 短路，改为按成分关系真实筛选（共用 `SectorMembershipIndex`）；② `SecurityConfiguration` 放开 QTE-01 与 SEC-01~06 的 GET（契约标 PUBLIC，此前落到 `anyRequest().authenticated()`） |
| 测试 | 后端 **338 测试通过**（M2-06 的 286 + 新增 52，另有 1 项 Testcontainers 用例因本机未启动 Docker 守护进程跳过）：`SectorQuoteCalculatorTest` 11 项、`SimulatedSectorProviderTest` 12 项、`SectorControllerContractTest` 24 项，`SecurityQueryServiceTest` / `StockRankingQueryServiceTest` / 两个契约测试改写 `sectorId` 断言 |
| 前端 | `domain.ts` 新增 `SectorType` / `SectorStatus` / `SectorRelationType` / `Sector` / `SectorList` / `SectorListQuery` / `SectorLeaderStock` / `SectorQuote` / `SectorRanking` / `SectorRankingQuery` / `SectorDetail` / `SectorConstituent` / `ConstituentQuery`；原型 `SectorQuote` 改名 `MockSectorQuote`（同 M2-05 的 `MockKlinePoint`）；typecheck 0 错误、13 文件 / 37 测试全绿 |
| 设计文档 | `docs/superpowers/specs/2026-09-20-sector-analysis.md`（11 节） |

### 关键设计取舍

**1. 成分关系一次全取，而不是"每个板块查一次"**
`SectorProvider.memberships(marketCode, effectiveDate)` 一次返回按 `sectorId` 分组的全量关系。
SEC-02 要对 39 个板块各取一次成分，端口若是 `findMembers(sectorId)`，一次排行就是 39 次全表关系计算；
QTE-01 的 `sectorId` 筛选还要再来一遍。代价是返回值体积大，由调用方决定何时复用。

**2. 板块不新增任何行情生成逻辑**
板块行情完全由已有整批快照按成分关系分组后聚合得出。因此"成分股行情 ↔ 个股快照 ↔ 榜单"
对同一只证券给出同一套事实，是**构造保证**而非约定。

**3. 停牌股计入 `companyCount` 但不参与统计**
停牌没有有效价格，把它的前收价混进均价会让板块均价失真；而"该板块有几只成分股"是成分事实。
代价是 `companyCount` 与 `averagePrice` 的分母不同，前端若用前者反推后者会算错（已写入类型注释）。

**4. 等权平均而非市值加权**
模拟源没有总股本字段，编一个会让"板块涨跌幅"变成两个编造数相乘的结果。等权口径下
`changeRate` 就是成分股涨跌幅的平均，可被逐项复核。真实数据源接入后若改口径，`SectorQuoteCalculator`
是唯一改动点。

**5. 板块平均涨跌幅可为 `null`**
成分股全部停牌时"平均涨跌幅"没有定义。补 `0` 会被读成"板块平盘"——PRD §7.4 SEC-02 明确
「历史断点不得补 0」。SEC-04 因此报 503 而不是返回一个 `changeRate: "0.0000"` 的对象。

**6. `contributionRank` 是数据属性，不受 `rankingType` 影响**
成分股列表默认按涨幅排序，但贡献度排名恒为"按涨跌幅降序"。若随 `rankingType` 变化，
按跌幅排序时"第 1 名"看起来会变成板块龙头。它与 SEC-04 的 `leadingStock` 共用同一个比较器，
`contributionRank = 1` 必然就是领涨股。

**7. `Sector.status` 不进 JSON**
`@JsonIgnore`：契约 §10 SEC-01 只列 6 个字段，而 `status` 在服务端是筛选与排序规则的输入
（`INACTIVE` 不进排行、SEC-04/06 报 `SECTOR_INACTIVE`），不是展示字段。同 M2-04 的 `pinyin`。

**8. SEC-06 每项嵌套 `quote` 而非摊平**
契约写「`PageData<QuoteSnapshot>`；每项附 `relationType`、`isPrimary`、`contributionRank`」。
摊平需要再造一个 19 字段记录，于是 `QuoteSnapshot` 的字段增删要同步两处且不会有测试变红。
代价是与 QTE-01 的 `items[]` 形状不一致。

**9. 抽出 `SimulatedHashing` 并回改两个既有 Provider**
`SimulatedSecurityQuoteProvider` 与 `SimulatedPriceSeries` 各有一份私有的 SplitMix64 收尾混合，
本轮第三个使用方出现，抽成唯一实现。改动是纯搬移，既有的确定性测试即为回归保护。

**10. 板块归属由 `securityCode` 哈希导出，而非"在全集中的序号"**
序号是列表的属性，代码是证券的属性。用序号决定归属的话，生成顺序一变同一只证券就会换板块——
这种漂移不会有任何测试报错，只会让历史对比失去意义。已写成测试
（`derivesMembershipFromCodeRatherThanListOrder`）。

### 不在本轮范围

| 项 | 归属 |
| --- | --- |
| SEC-05 板块走势 `/sectors/{id}/trend` | 契约列于 §10，但需要按成分股聚合出时间序列，属独立增量；已记入 `PROJECT_STATUS.md` |
| SEC-07 板块资讯 `/sectors/{id}/news` | 依赖资讯域（M3-04） |
| 板块 AI 解读（PRD SEC-04） | 依赖 AI 编排（M3-06 / M3-07） |
| 板块与成分关系落库 | 与 M2-01~M2-06 一致：模拟 Provider 内存生成，`stock_sector` / `stock_security_sector` 继续空置 |
| 前端 `/sectors`、`/sectors/:id` 接入 | M2-08（本轮只补契约类型） |
| 板块 Excel 导出 | M3-12 |

---

## M2-08 交付详情

| 项 | 内容 |
| --- | --- |
| 范围 | 4 个页面从 `mockApi` 切到真实接口：`/rankings`、`/sectors`、`/sectors/:id`、`/stocks/:id` |
| 调用 | `/rankings` → `GET /stock-rankings`；`/sectors` → `GET /sector-rankings?size=100`；`/sectors/:id` → `GET /sectors/{id}` + `/sectors/{id}/constituents?size=100`；`/stocks/:id` → `GET /securities/{id}/quote` + `/securities/{id}/klines?period=` |
| 新增 | `services/rankingApi.ts`、`services/sectorApi.ts`、`services/securityApi.ts`；`composables/useRemoteData.ts`（一次请求的三态 + 过期响应守卫）；`apiClient.toQueryString`（丢弃空值但保留 `false` / `0`） |
| 修改 | 四个页面；`format.ts` 的 `formatDateTime` 接受 `null` 并识别非法时间；`domain.ts` 删除 `StockDetail` / `MockKlinePoint`、`MockSectorQuote` 改名 `OverviewSectorQuote`；`mockApi.ts` 删除 `getStockDetail` 与 `stockDetail` 常量 |
| 榜单页 | 三档口径（涨幅/跌幅/成交额）驱动 `rankingType`、交易所单选驱动 `exchangeCodes`、真实分页（`page` / `totalPages` / `hasNext`）；**移除契约不存在的"换手率榜"**与**客户端关键字过滤**；导出按钮置 `disabled`（M3-12） |
| 板块列表页 | 卡片字段全部来自 `SectorQuote`；原型里写死的"金融领涨，科技成交活跃"改为**数据驱动的客观摘要**（涨幅第一 + 成交额第一 + 上涨板块计数），并移除死的"生成板块综述"按钮 |
| 板块详情页 | 头部取 `sector` / `parent` / `quote`；**移除假分时曲线**（SEC-05 未实现）；强度拆解只用真实成分股重算涨跌家数（停牌单列）并写明分母；成分股表格用服务端的 `contributionRank` 而非页内序号 |
| 个股详情页 | 头部与指标条全部来自 `QuoteSnapshot`；K 线周期切换（日/周/月）真实重新请求；**移除市盈率**；公司资料 / 所属板块 / 关联资讯 / AI 速览改为"尚未实现"说明 |
| 测试 | 前端 **17 文件 / 62 项通过**（原 13 文件 / 37 项），含 `RankingsPage` 5、`SectorsPage` 4、`SectorDetailPage` 5、`StockDetailPage` 7、`useRemoteData` 4、`format` 新增 1；`vue-tsc --noEmit` 0 错误；`vite build` 成功；**`TZ=UTC` 下重跑同样全绿** |
| 设计文档 | `docs/superpowers/specs/2026-09-20-frontend-integration.md`（9 节 + 8 条取舍） |

### 关键设计取舍

**1. 没有数据来源的字段一律降级，不保留编造值**
这是本轮最重要的决策。原型的 `peRatio: '6.21'`、`marketCap: '362400000000'`、
`"量价可信度：高"`、`"+2.15% 相对大盘"` 全是编造的，但**看起来像真实数据**。
在一个投资辅助工具里，编造的估值指标比空白危险得多——用户会据此做判断。
因此契约有字段就接真实值，没有就显示"尚未实现"并移除图表/指标。
代价是个股页明显变空（PE、市值、业务描述、板块、资讯、AI 速览都空），
接受这个代价：M3 会把它们逐个填回来，而填回来时它们会是真数据。

**2. 抽 `useRemoteData` 统一三态**
四个页面都需要"加载中 → 成功 / 失败（带 `traceId`，可重试）"。抽一个 ~35 行的 composable，
避免四份各自演化的 `try/catch/finally`。**刻意不做**缓存、并发去重、轮询、重试退避——都没有需求支撑，
且 `apiClient` 已经处理了 401 刷新。
里面有一个容易被漏掉的守卫：**请求序号**。快速切换口径时会并发多个请求，而返回顺序不保证与发出顺序一致；
没有它，先发的慢请求后到达会把新口径的数据覆盖成旧口径的——页面显示"跌幅榜"却列着涨幅榜的内容，
且不会有任何异常。已用一条专门的测试（`丢弃过期响应`）钉住。

**3. 筛选条件变化触发重新请求，而不是客户端过滤**
口径、交易所、页码、K 线周期都是**服务端参数**。榜单页因此移除了原型的关键字筛选框：
QTE-01 没有 `keyword` 参数，在当前页做客户端过滤会让排名号与真实名次不符
（第 7 名被过滤掉后，第 8 名仍显示"08"），而排名正是榜单最不能被破坏的东西。

**4. `/sectors` 用 `sector-rankings` 而不是 `sectors` + 逐个 `quote`**
板块卡片要的涨跌幅 / 成交额 / 领涨股只在 `SectorQuote` 里。逐个取的话 39 个板块就是 39 次请求，
且**每次取到的快照批次可能不同**——页面上的板块涨跌幅会来自不同时刻。
排行接口一次返回同一快照下的全部板块行情。代价是页面上看不到停用板块（排行不含 `INACTIVE`）。

**5. 板块详情的涨跌家数写明分母**
`size` 上限是 100，而板块最多约 257 只成分股。不翻三次页只为算一个家数，
但**必须写明"基于已取回的 N 只成分股"**，否则"38 家上涨"会被读成整个板块。

**6. 个股页的行情与 K 线各显示自己的数据截止时间**
契约 STK-05 明确"不保证不同证券源时间完全相同"：K 线的最后一个交易日与快照的盘中时刻
本就是两个不同的时间点。合成一个"数据截止"会让用户以为它们同源同时。

**7. 停牌成分股的涨跌幅 `null` 不补成 0**
补 0 会让停牌股看起来是"平盘"，这是最容易误导的一类错。详情页把它单列为"停牌 N 只"。

**8. `MockSectorQuote` 改名 `OverviewSectorQuote`（纠错）**
它**不是 mock**——`MarketOverview.vue` 早已接真实接口，这个类型就是后端
`MarketOverview.SectorPreview` 的前端契约（`leadingStock` 只有名称、无 `securityId`）。
`Mock` 前缀会让人以为它是可以随手改的占位数据。同时删掉 `StockDetail` 与 `MockKlinePoint`：
它们的唯一使用者是刚被删掉的 `stockDetail` 常量，而 `StockDetail` 上挂着的
`peRatio` / `marketCap` / `businessDescription` / `aiPrompts` 正是本轮判定为"无数据来源"的那批字段，
留着等于给下一个人留一份"看起来能用"的假契约。

### 不在本轮范围

| 项 | 归属 |
| --- | --- |
| `/news` 接真实接口 | M3-05（依赖资讯 Provider M3-04） |
| `/watchlist` 接真实接口 | M3-03（依赖自选 CRUD M3-01 / M3-02） |
| `/ai`、`/history` 接真实接口 | M3-10（依赖 AI 编排与持久化 M3-07 / M3-08） |
| `/admin` | M3-11 |
| 全局搜索接真实接口 | M2-09 |
| 个股资料（STK-08）、所属板块（STK-09）、关联资讯（STK-10） | 后端未实现，本轮显示"尚未实现" |
| 板块走势（SEC-05）、板块 AI 解读 | 后端未实现 / 依赖 M3-06、M3-07 |
| 榜单 Excel 导出 | M3-12 |

---

## M2-09 交付详情

| 项 | 内容 |
| --- | --- |
| 接口 | `GET /api/v1/securities/search`（STK-01，PUBLIC），`q`（裁剪后 1–50 字符）+ `limit` |
| 交付范围 | 路线图指定的 `components/GlobalSearch.vue`（1 个组件 + 1 个 API 函数 + 4 条样式） |
| 修复的既有缺陷 | 原型**从未发出过任何请求**：3 条写死的建议、固定显示编造的 `+2.74%`、点击任一条都跳到 `/stocks/19876543210001`（该 ID 在主数据里不存在 → **个股页 404**）。这是全站最显眼的入口，点谁都坏 |
| 交互 | 300ms 防抖；`↑`/`↓` 移动、`Enter` 选中、`Esc` 收起、`⌘K`/`Ctrl+K` 聚焦；点击组件外部关闭 |
| 结果项 | 名称、交易所 · 代码（命中片段用 `highlight` 原文高亮）、状态徽标（停牌 / 退市 / 待上市 / ST） |
| 状态 | 加载中 / 无结果 / 失败（展示后端文案 + `traceId` + 重试，**关键词保留在输入框**）/ 空输入提示 |
| 复用 | `useRemoteData`（三态 + 请求序号守卫）；`securityApi.searchSecurities(q, limit)` |
| 测试 | 前端 **18 文件 / 75 项通过**（M2-08 的 17 / 62 + 本组件 13 项）；`vue-tsc --noEmit` 0 错误；`vite build` 成功；`TZ=UTC` 下同样全绿 |
| 设计文档 | `docs/superpowers/specs/2026-09-20-global-search.md`（9 节 + 7 条取舍） |

### 关键设计取舍

**1. 结果项不展示涨跌幅——PRD 要求了，但拿不到**
PRD QTE-01 的输出列写「含代码、名称、交易所、状态和涨跌幅」，但 STK-01 的响应里**没有任何价格字段**
（`SecuritySummary` 是主数据）。拿到涨跌幅只能靠 `STK-05 POST /quotes/securities/batch-query`
（契约明确它的用途就是"批量查询自选、板块成分股等首屏行情"），而**该接口后端未实现，
`TASKS.md` 的 M2 清单里也没有它**。
逐条调 STK-04 是 10 条建议 11 次请求，与 PRD「输入后 500 毫秒内出现结果」直接冲突。
因此本轮不展示，与 M2-08 同一条原则：契约没有字段就降级，不保留编造值。
**建议补一个小任务实现 STK-05**——一次请求即可补齐，M3-03 自选页的首屏行情也需要它。

**2. 复用 `useRemoteData` 的请求序号守卫**
防抖只降低并发概率，不消除：300ms 后发出请求 A，用户在 A 返回前继续输入又发出 B，
若 A 比 B 慢，列表会回退成 A 的结果。守卫已经在 M2-08 抽好了，这里直接复用而不是重写一遍。

**3. 高亮不按 `matchedField` 分支，而是对代码与名称各试一次**
后端只说命中"哪个类别"，没说具体是哪个字段的哪一段。谁包含 `highlight` 就切谁——
`CODE` 与 `NAME` 两种情况共用一条逻辑，也不会因为将来后端调整 `matchedField` 取值而把高亮打到错误的字段上。

**4. 空输入不发请求**
契约要求 `q` 裁剪后 1–50 字符。不判断的话，用户每敲一个空格都会产生一条 400 与服务端日志噪音。

**5. 点击外部用 document 监听，不用 `blur` + 定时器**
原型用 `@blur` + `setTimeout(120ms)` 再配合 `@mousedown.prevent` 让"点击先于失焦生效"——
那是靠时间窗赌顺序，机器卡顿时 120ms 不够，点击就会丢。改成判断点击落点，行为确定。

**6. `focused` 与 `open` 拆成两个状态**
"点击组件外部"会让面板收起而输入框仍握着光标。若用一个 `open` 同时控制聚焦样式，
输入框会在有光标时看起来是失焦的。

**7. 改占位符，而不是保留一句做不到的承诺**
原占位符是「搜索股票、代码或板块」。STK-01 只搜证券，用户输入"银行"得到的是名字含"银行"的**股票**，
不是板块。改为「输入代码或名称搜索证券」。

### 不在本轮范围

| 项 | 归属 |
| --- | --- |
| 搜索建议里的涨跌幅 | 需先实现 STK-05（见取舍 1） |
| 板块搜索 | STK-01 只搜证券；板块走 `/sectors` 页 |
| 热门 / 最近搜索建议 | 无数据来源（没有"热搜"接口） |
| 搜索结果独立页 / 全量结果 | STK-01 是搜索**建议**（`limit` 上限 20），不是结果页 |

---

## M2-10 交付详情

> 关闭已知问题 **#7**（非交易日 `tradeDate` / `marketStatus` 未回退）与 **#12**（顶栏写死的"交易中 14:32"）。
> 设计文档：`docs/superpowers/specs/2026-09-20-market-status-truth.md`。
> 对应契约：MKT-01 / MKT-02。

### 交付内容

| 层 | 内容 |
| --- | --- |
| 新增纯函数 | `stock-market/domain/TradingSessions`：`latestTradeDate` + `currentSession`，两条规则各只有一处实现 |
| 新增辅助 | `stock-integration/.../SimulatedSessionTimes`：收盘时刻从日历取（不硬编码 15:00），总览与个股快照共用推导过程、各自保留兜底策略 |
| 后端口径 | `SimulatedQuoteProvider` 注入 `TradingCalendarProvider`；`tradeDate` / `marketStatus` / `dataTime` 由日历推导；`breadth` 与榜单预览共用同一 `tradeDate` |
| 既有实现收口 | `SimulatedMarketAccess.latestTradeDate()`、`MarketStatusQueryService.getStatus()` 改为委托 `TradingSessions` |
| 装配 | `BackendConfiguration` / `JobConfiguration` 的 `quoteProvider` 传入共享日历；`stock-job` 新增 `TradingCalendarProvider` Bean 与 `MARKET_HOLIDAYS` 配置（此前它自建了一份**空节假日表**的日历） |
| 前端 | `types/domain.ts` 新增 MKT-02 契约类型 `MarketStatus`；`marketApi.getMarketStatus`；新增 `composables/useMarketStatus`（60s 刷新 + 页面不可见暂停）；`AppShell` 顶栏与侧栏接真实状态；`MarketOverview` 标注交易日与休市标记 |
| 格式化 | `format.ts` 新增 `formatDate` / `formatTime`，均显式钉 `Asia/Shanghai` |

### 修复的三处"自相矛盾"

**1. 同一份快照内部两个字段来自不同交易日（#7 的真正形态）**

`breadth` 用 `now.toLocalDate()`（周日），`rankings` 用 `SimulatedMarketAccess.latestTradeDate()`（周五）。
两处各自都是"合法"的值，**没有任何测试会因此变红**——这正是口径分歧的代价：它不报错，只让数字互相矛盾。
`latestTradeDate()` 早已实现了正确规则，问题在于它只被部分链路使用。

**2. 采集侧与查询侧对"今天是哪一天"给出不同答案**

`stock-job` 此前没有 `TradingCalendarProvider` Bean，`SimulatedQuoteProvider` 自建了一份**空节假日表**的日历；
而 `stock-backend` 用的是 `stock.market.holidays`。一旦配置节假日，采集任务会认为当天是交易日并落盘快照，
接口却按节假日回退到上一交易日。已在 `compose.yaml` 的共享环境锚点与两个 `application.yml` 里统一 `MARKET_HOLIDAYS`。

**3. 总览页的数字与它自己引用的数据矛盾**

原型写死「较昨日 +8.69%」，而同一份响应里的 `amount` / `previousAmount` 算出来是 +7.25%。
已改为按这两个字段计算。同页写死的「今日市场，温和放量。」与「金融与科技方向形成共振」也一并移除——
它们在周日显示时，"今日市场"本身就是错的；导语改为由真实广度数据拼出。

### 关键设计取舍

**1. 抽 `TradingSessions`，而不是在总览里再补一段相同逻辑**
若只在 `SimulatedQuoteProvider` 里补一遍，就有了**两份**实现，下次改一处就会让总览与榜单分叉，
而且同样不会有测试报错。抽到 `domain` 层纯函数后，三处调用方全部改为委托。

**2. `dataTime` 的"盘中"判定用时段窗口，不比较 `tradeDate == today`**
两种写法只在**收盘后**有差别：交易日 20:00 时 `tradeDate == today` 成立，但市场 15:00 就已收盘。
用窗口判定得到 15:00（真实收盘时刻），用日期比较会得到 20:00（一个没有数据的时刻）。

**3. `Scenario.CLOSED` 保留为强制覆盖，不删除**
日历接管后它已冗余，但被 `compose.yaml`、`backend/README.md`、两个 `application.yml` 与三处测试引用，
删除属于扩大范围且会破坏用户的 compose 环境变量。语义明确为"演示 / 测试用的强制覆盖"。

**4. 顶栏收盘后不显示时间**
原型「交易中 14:32」的 `14:32` 是写死的。改为：时段进行中显示状态文案 + 当前北京时间；
已收盘 / 非交易日只显示状态文案，把 `nextSessionAt` 放进 `title`。
收盘后显示"已收盘 20:00"是误导——20:00 没有数据。

**5. 60 秒刷新放在独立 composable，不进 `useRemoteData`**
`useRemoteData` 刻意不做轮询（M2-08 决策），不应为顶栏破例。
刻意**不**按 `nextSessionAt` 定时唤醒：那样在日历本身过期时会静默停更，固定节拍不会失效。

**6. 休市与已收盘分开**
判据是**快照的交易日是不是今天**（按北京时间比较）。
不能用"`dataTime` 与 `tradeDate` 是否同日"——后端在非交易日会把 `dataTime` 回退到上一交易日的收盘时刻，
两者本来就同日，区分不出周末与盘后。周日显示"已收盘"会让人以为今天开过市。

**7. 写死的指数可以留，写死的板块 ID 不能留**
判据是"这个值是否需要与系统其它部分对齐"：`idx-*` 不与任何链路冲突，作为模拟源数据自洽；
而 `sectors()` 返回的 `bk-ai` / `bk-chip` / `bk-broker` 会被总览页当作真实 `sectorId` 跳转，
必然 404——**写死的数值可以是模拟数据；写死的标识符只要需要被别处解析，就是缺陷**。
后者归 M2-11。

### 不在本轮范围

| 项 | 归属 |
| --- | --- |
| 总览快照写死的板块预览（三张卡片 404） | **M2-11**（已完成，见下方 M2-11 交付详情） |
| 总览快照写死的指数 | 不做（`idx-*` 无坏链接，见取舍 7） |
| 顶栏"消息通知"铃铛 | M3 通知域 |
| STK-05 批量行情 | M3-03 |
| 分时 STK-06 | 单独排期 |

### 验证结果

| 项 | 结果 |
| --- | --- |
| 后端 | **354 项通过**（本次新增 13 项：`TradingSessionsTest` 8 + 总览非交易日 5） |
| 前端 | **19 文件 / 99 项通过**（基线 18 / 75；新增 `useMarketStatus` 9、`AppShell` +4、`MarketOverview` +7、`format` +4），`TZ=UTC` 下同样全绿 |
| 类型 / 构建 | `npm run typecheck` 0 错误；`vite build` 成功 |
| 环境限制 | `InfrastructureIntegrationTest` 报 `Could not find a valid Docker environment`（本机 Docker 未启动），非回归，CI 覆盖 |

---

## M2-11 交付详情

总览快照（MKT-01）的板块预览段此前是三个写死的常量，`sectorId` 用的是板块源里不存在的
`bk-ai` / `bk-chip` / `bk-broker`，而总览页把它们当作主键跳转 `/sectors/{sectorId}`
→ **首页三张板块卡片点进去全部 404**；三个数字也与「板块分析」页对不上。
本轮把它改为投影自真实板块源，与 SEC-02 板块排行走同一条取数路径。

### 交付内容

| 项 | 内容 |
| --- | --- |
| 新增 spec | `docs/superpowers/specs/2026-09-20-overview-sector-preview-truth.md` |
| 板块预览改为投影 | `SimulatedQuoteProvider.sectors(QuoteBatch)` 按 `SectorProvider` + `SectorQuoteCalculator` 计算，取 `GAINERS` 前 3 名 |
| `QuoteBatch` 下沉到 domain | 从 `market.application` 包私有类改为 `market.domain` 公开类，第 4 个消费方（摄入侧适配器）才能复用同一份"按成分关系取数"语义 |
| 装配补全 | `BackendConfiguration.quoteProvider` 注入 `SectorProvider`；便捷构造自建时整批快照源与板块源**共用同一份证券主数据** |
| 前端契约对齐 | `OverviewSectorQuote.leadingStock` 改为 `string \| null`（后端不编造），页面渲染 `?? '--'` |
| 后端测试 | `SimulatedQuoteProviderTest` 9 → 11 项 |
| 前端测试 | `MarketOverview.test.ts` 11 → 13 项 |

### 修复的缺陷：同一份快照里两处对同一个板块给出不同事实

| 位置 | 修复前 | 修复后 |
| --- | --- | --- |
| 总览 `sectors[].sectorId` | `bk-ai`（板块源里不存在）→ 点进去 404 | `sim-bk0033` 等真实 ID |
| 总览 `sectors[].sectorName` | 人工智能 / 半导体 / 证券 | 一带一路 / 通信设备 / 消费电子（真实前三名） |
| 总览 `sectors[].changeRate` | 0.0342 / 0.0286 / 0.0231（写死） | 0.0228 / 0.0220 / 0.0215（等权平均，与 SEC-02 一致） |
| 总览 `sectors[].companyCount` | 68 / 81 / 50（写死） | 149 / 247 / 245（成分事实） |

### 关键设计取舍

**1. `QuoteBatch` 下沉到 `domain`，而不是在适配器里再抄一遍**
要让预览与 SEC-03 逐字段相同，最可靠的做法是走同一条路径。但 `QuoteBatch` 原本是
`market.application` 里的包私有类，`stock-integration` 够不到。两条路：在适配器里重写
"按成分关系取数 + 缺快照则跳过"，或把它下沉一层。选后者——`QuoteBatch` 只依赖领域类型，
是纯视图，下沉不构成依赖倒置；而"缺快照的成分如何处理"从此只有一份实现。

**2. 排序口径复用 `RankingType.GAINERS.sectorOrder()`，不另写一个比较器**
`SectorParameters.DEFAULT_RANKING_TYPE` 就是 `GAINERS`，因此预览与 SEC-02 默认口径天然一致。
另写一个"按涨跌幅降序"的比较器，就会出现"榜单兜底键按 sectorCode 升序、预览按别的顺序"
这种只在同涨幅时才暴露的分叉。

**3. `dataTime` / `dataStatus` 沿用整批快照的口径**
`QuoteSnapshot.dataTime` 是**该交易日收盘时刻**，而总览自身的 `dataTime` 盘中为 `now`，
两者不同源。这里刻意用批次口径，使本路径与 `SectorQueryService.statistics(sector)` 逐字相同；
这两个值不会外泄——预览段的契约里没有它们。

**4. 便捷构造让整批快照源与板块源共用同一份证券主数据**
`SimulatedSectorProvider` 的成分是投影自证券主数据的。若两者各持一份主数据实例而将来某一方
换了日历，"板块成分指向的证券"与"整批快照里的证券"会静默失配，板块预览会变成空列表而不报错。
共用一份从构造上消除这个面（M2-10 的日历分叉是同一类问题）。

**5. `stock-job` 继续用便捷构造，不新增四个 Bean**
M2-10 给 `stock-job` 补 `TradingCalendarProvider` Bean 是因为节假日是**可配置输入**，
两边配置不同就会对"今天是哪一天"给出不同答案。板块源没有任何可配置输入，
完全由传入的日历决定，因此便捷构造已足够；为对称而新增四个纯样板 Bean 是投机性改动。

**6. 前端 `leadingStock` 改为可空**
后端契约里它是可空的。当前 `GAINERS` 口径下它必非空（排序键可用 ⟹ 至少一只成分股有有效行情
⟹ 领涨股存在），但类型照实写可空，免得换口径时把 `null` 渲染成空白。
`SectorDetailPage.vue` 对同一字段早已用 `?? '--'`，此处对齐。

### 不在本轮范围

| 项 | 归属 |
| --- | --- |
| 总览快照写死的指数（`idx-sh` 等） | 不做（无坏链接，模拟源自洽） |
| 总览 `turnover` 写死的量额与点位 | 不做（无可对齐的第二来源，且非标识符） |
| 总览 `news()` 单条模拟资讯 | M3-05 |
| 顶栏"消息通知"铃铛 | M3 通知域 |
| STK-05 批量行情 | M3-03 |

### 验证结果

| 项 | 结果 |
| --- | --- |
| 后端 | **353 项通过**（全量 354 项，其中 `InfrastructureIntegrationTest` 因本机未启动 Docker 报错）；本次新增 2 项 |
| 前端 | **19 文件 / 101 项通过**（基线 19 / 99；新增跳转主键 1、`leadingStock` 为 null 1），`TZ=UTC` 下同样全绿 |
| 类型 / 构建 | `npm run typecheck` 0 错误；`vite build` 成功 |
| 环境限制 | `InfrastructureIntegrationTest` 报 `Could not find a valid Docker environment`（本机 Docker 未启动），非回归，CI 覆盖 |

---

## 已完成

- [x] 阶段 0 只读审计（2026-09-19）
- [x] 阶段 1 交付路线图（2026-09-19）
- [x] M1-01 / M1-02 / M1-03 / M1-04 / M1-05 / M1-06 / M1-09 / M1-10 / M1-11 / M1-12 / M1-13 / M1-14 / M1-15 / M1-16（2026-09-19）
- [x] M2-01（2026-09-19）
- [x] M2-02（2026-09-19）
- [x] M2-03（2026-09-19）
- [x] M2-04（2026-09-19）
- [x] M2-05（2026-09-19）
- [x] M2-06（2026-09-19）
- [x] M2-07（2026-09-20）
- [x] M2-08（2026-09-20）
- [x] M2-09（2026-09-20）
- [x] M2-10（2026-09-20）
- [x] M2-11（2026-09-20）
