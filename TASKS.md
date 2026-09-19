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

## 已完成

- [x] 阶段 0 只读审计（2026-09-19）
- [x] 阶段 1 交付路线图（2026-09-19）
- [x] M1-01 / M1-02 / M1-03 / M1-04 / M1-05 / M1-06 / M1-09 / M1-10 / M1-11 / M1-12 / M1-13 / M1-14 / M1-15 / M1-16（2026-09-19）
- [x] M2-01（2026-09-19）
- [x] M2-02（2026-09-19）
- [x] M2-03（2026-09-19）
- [x] M2-04（2026-09-19）
- [x] M2-05（2026-09-19）
