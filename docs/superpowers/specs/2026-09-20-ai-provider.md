# M3-06 AI Provider 抽象 + 确定性模拟实现

> 里程碑：`TASKS.md` M3-06（P0，依赖 M3-04）
> 路线图验收口径：**接口与真实 LLM 同构，替换实现类即可切换**（验证方式：单测）
> 表结构：`sql/flyway/V6__create_ai_domain.sql`（8 张表，V1 起就绪、至今**零行零引用**）

---

## 1. 背景

### 1.1 为什么现在做

M3-06 是 AI 域的第一步。在此之前，仓库里**不存在任何 AI 代码**：
`stock-ai` 模块未创建，`V6` 的 8 张表（`ai_session` / `ai_task` / `ai_task_target` /
`ai_context_snapshot` / `ai_message` / `ai_report` / `ai_evidence` / `ai_feedback` / `ai_usage`）
自 M1 建好后没有任何 Java 类引用它们。

路线图把 M3-04 排在 M3-06 前面，理由写在路线图第 130 行：

> **M3-04 早于 M3-06**：AI 的证据链依赖资讯关联，先有资讯再有 AI，避免 AI 报告无据可引。

M3-04 已交付，资讯链路可用；M3-06 因此解锁。

### 1.2 本轮在整条 AI 链路里的位置

```
M3-06（本轮）              M3-07              M3-08              M3-10
端口 + 模拟实现 + 场景目录 → 任务状态机 + SSE → 报告/证据落库 → 前端工作台
上下文构建 + 预览          取消/重试/超时     反馈持久化
```

本轮**不落库**：V6 的 8 张表在 M3-07 / M3-08 才被写入。本轮产出的是
「LLM 调用端口 + 确定性模拟实现」与「任务开始时固化的上下文与证据候选」——
它们是 M3-07 编排器的输入。

### 1.3 数据来源

真实 LLM 源**未就位**（用户已确认，见路线图"暂不做什么"）。因此：

- 定义 `LlmProviderPort`（`stock-ai/domain`），与真实供应商适配器同构；
- 模拟实现 `SimulatedLlmProvider` 落在 `stock-integration/ai`（与 `SimulatedQuoteProvider` /
  `SimulatedNewsProvider` 同层），**确定性**：同一请求产出逐位相同的结果。

### 1.4 可直接复用的既有代码

| 复用对象 | 位置 | 复用方式 |
| --- | --- | --- |
| `QuoteSnapshotBatchProvider` / `QuoteBatch` | `stock-market/domain` | 行情上下文取数。整批端口**不接收筛选条件** → "任务内所有标的同一快照版本"由接口形状保证 |
| `SectorProvider` | `stock-market/domain` | 板块场景的主数据与成分关系 |
| `SecurityIdentityProvider` / `SectorIdentityProvider` | `stock-market/domain` | 对外字符串标识 ↔ bigint 代理键的**唯一**桥接（`ai_task_target.target_id` 是 bigint） |
| `TradingSessions` | `stock-market/domain` | 最近有效交易日，用于行情截止时间 |
| `NewsQueryService.visible()` 过滤链 | `stock-news/application` | AI 资讯证据复用同一份可见性判据（**不重写第二份**） |
| `NewsSource.usableOn` / `allowAiAnalysis` | `stock-news/domain` | 授权闸门 + AI 准入标记 |
| `SimulatedHashing`（SplitMix64） | `stock-integration/market` | 模拟 Provider 的确定性随机，**不新写第二份哈希** |
| `ApiResponse` / `PageData` | `stock-common` | 统一封套 |
| `IdempotencyGuard` | `stock-system/idempotency` | AI-01 / AI-02 都是读接口（AI-02 虽为 POST 但不产生副作用），本轮不需要 |

---

## 2. 目标与非目标

### 2.1 目标

1. **AI 域独立成模块 `stock-ai`**，含 `domain` / `application` 两层（本轮不建 `infrastructure`，不落库）。
2. **`LlmProviderPort`**：流式 LLM 调用端口，与真实供应商适配器同构。
3. **`SimulatedLlmProvider`**：确定性模拟实现，落在 `stock-integration`。
4. **AI 场景目录**（AI-01）：5 个场景的目标规则、问题长度上限、分析区间档位。
5. **上下文构建与预览**（AI-02）：目标校验 → 数据取数 → 上下文快照 + 证据候选固化 → 预览摘要。
6. **关闭 M3-04 的欠账**：`allow_ai_analysis` 的**消费侧**（M3-04 只落库了这个标记）。
7. **契约接口 AI-01 / AI-02**：`GET /ai/scenes`、`POST /ai/context-previews`。

### 2.2 非目标（写明归属，不在本轮偷偷扩大范围）

| 不做 | 归属 | 原因 |
| --- | --- | --- |
| AI 任务状态机、`ai_task` 落库、SSE | M3-07 | 本轮只做端口与上下文，没有任务可跑 |
| 报告 / 证据 / 反馈落库 | M3-08 | 同上 |
| 配额与用量统计 | M3-09 | 需要 Redis 计数与 `ai_usage` 表 |
| `stock-ai-worker` 独立进程 | M3-07 | 没有任务可领取时，多一个进程只是多一个空转容器 |
| AI-03~AI-08（任务创建 / 查询 / SSE / 取消 / 重试 / 追问） | M3-07 | 依赖状态机 |
| HIS-01~HIS-09（历史 / 报告 / 反馈） | M3-08 / M3-10 | 依赖落库 |
| K 线 / 主营业务 / 日历 / 规则四类上下文 | 后续里程碑 | 见 §3.4 的"可用数据类别"取舍 |
| 真实 LLM 适配器 | 用户已确认延后 | 密钥与供应商未就位 |
| Prompt 模板管理与版本化后台 | M3-11 | 本轮版本号是**常量**，不是可配置项 |
| 禁止表达 / 提示注入的完整安全校验器 | M3-07 | 本轮只保证「引用编号必须落在候选集合内」这条**结构性**规则 |

---

## 3. 设计

### 3.1 为什么 `stock-ai` 独立成模块

架构 §5.2 已把 `stock-ai` 列为独立模块，职责是「任务状态机、上下文构建、模板版本、
证据、报告、反馈、配额和安全规则」，禁止事项是「不在 Controller 线程中执行 LLM，
不信任模型生成的引用」。本轮先建 `domain` + `application`。

**依赖方向**（新增一条边，无环）：

```
stock-ai ──→ stock-news ──→ stock-market ──→ stock-common
    └──────────────────────────────┘
```

- `stock-ai` 显式声明 `stock-market`（行情端口 + 身份解析）与 `stock-news`（资讯证据端口）；
- **不引入 MyBatis**：本轮不落库，提前引入会让"这个模块到底写不写库"变得含糊；
- `stock-news` 已依赖 `stock-market`，但 `stock-ai` 仍显式声明——与 `stock-news` 显式声明
  `spring-boot-starter-json` 同一理由（不蹭传递依赖）。

### 3.2 AI 场景目录（AI-01）

`stock-ai/domain` 新增：

| 类型 | 说明 |
| --- | --- |
| `AiScene` | 枚举：`MARKET` / `SECTOR` / `STOCK` / `STOCK_RISK` / `COMPARE`（与 V6 `ck_ai_session_scene` 同集合） |
| `AiTargetType` | 枚举：`MARKET` / `SECTOR` / `SECURITY`（与 V6 `ck_ai_target_type` 同集合） |
| `AiTargetRole` | 枚举：`PRIMARY` / `COMPARISON` / `CONTEXT`（与 V6 `ck_ai_target_role` 同集合） |
| `AiAnalysisRange` | 记录：`presets` / `defaultPreset` / `maxCustomDays` |
| `AiSceneDefinition` | 记录：`scene` / `name` / `description` / `allowedTargetTypes` / `minTargets` / `maxTargets` / `defaultRange` / `questionMaxLength` |
| `AiSceneCatalog` | **具体类**（不是端口）：5 个场景的静态规则表 |

场景规则（依据契约 §13.1「场景目标规则」表，逐行对齐）：

| 场景 | `allowedTargetTypes` | `minTargets` | `maxTargets` | 依据 |
| --- | --- | --- | --- | --- |
| `MARKET` | `[MARKET]` | 1 | 1 | 契约：1 个 `MARKET` 主目标 |
| `SECTOR` | `[SECTOR]` | 1 | 1 | 契约：1 个 `SECTOR` 主目标 |
| `STOCK` | `[SECURITY]` | 1 | 1 | 契约：1 个 `SECURITY` 主目标 |
| `STOCK_RISK` | `[SECURITY]` | 1 | 1 | 契约：1 个 `SECURITY` 主目标 |
| `COMPARE` | `[SECURITY]` | 2 | 3 | 契约：2 至 3 个 `SECURITY` 目标，且仅 1 个 `PRIMARY` |

`questionMaxLength` 全部为 **500**：PRD 第 371 行「0-500 字问题」，且 V6 的
`ck_ai_task_question_length` 是 `CHAR_LENGTH(question) <= 500`。三处同口径。

**`defaultRange` 为什么是"档位"而不是"日期区间"**：

AI-01 是**静态场景目录**。若返回具体日期（如 `2026-09-13 ~ 2026-09-20`），
同一份"场景定义"会在两次请求间变化，且目录被迫依赖时钟——而 `GET /ai/scenes`
的语义是"有哪些场景"，不是"现在的默认区间是哪段"。因此返回档位：

```json
{
  "presets": ["LAST_1_TRADING_DAY", "LAST_5_TRADING_DAYS", "LAST_20_TRADING_DAYS"],
  "defaultPreset": "LAST_5_TRADING_DAYS",
  "maxCustomDays": 365
}
```

- `presets` 三档来自 PRD 第 371 行「1/5/20 个交易日或自定义范围」；
- `maxCustomDays = 365` 来自 PRD 第 371 行「自定义范围最长 1 年」；
- `defaultPreset = LAST_5_TRADING_DAYS` 是**产品判断**（中间档），契约未规定——
  见 §7「已知取舍」。

`AiSceneCatalog` 是具体类而非端口：它没有外部依赖、不需要替换实现，
与 `TradingSessions`（纯函数工具类）同一处理方式。**不为将来可能的配置化提前引入接口。**

### 3.3 LLM Provider 端口

`stock-ai/domain` 新增：

| 类型 | 说明 |
| --- | --- |
| `LlmProviderPort` | 接口：`LlmCompletion complete(LlmRequest, Consumer<LlmChunk>)` |
| `LlmRequest` | 记录：`providerCode` / `modelCode` / `promptVersion` / `contentSchemaVersion` / `systemPrompt` / `userPrompt` / `evidenceCandidates` / `maxOutputTokens` |
| `LlmEvidence` | 记录：`evidenceNo` / `evidenceType` / `sourceTitle` / `evidenceSummary` —— **模型能看到的证据视图** |
| `LlmChunk` | 记录：`section` / `delta` / `sequence` |
| `LlmUsage` | 记录：`promptTokens` / `completionTokens` / `cachedTokens` / `totalTokens` |
| `LlmCompletion` | 记录：`providerRequestId` / `modelCode` / `chunks` / `usage` / `firstChunkLatency` / `totalLatency` |
| `LlmErrorCategory` | 枚举：`TIMEOUT` / `RATE_LIMIT` / `DATA` / `SAFETY` / `PROVIDER` / `SYSTEM`（与 V6 `ck_ai_task_error_category` 同集合） |
| `LlmProviderException` | 运行时异常：携带 `category` + `retryable` |
| `AiReportSection` | 枚举：报告六个固定章节（见 §3.6） |

#### 3.3.1 流式用回调而不是 `Stream`

`complete(request, onChunk)` 逐段回调，返回汇总结果。理由：

- M3-07 的消费方是 SSE 中继——它拿到一段就要立刻推给浏览器，
  `Stream` 的惰性求值会让"什么时候真正发起调用"变得不可预测；
- 端口实现（真实 HTTP 客户端）天然是"读一段、回调一段"；
- 调用方不需要处理流关闭与异常传播的交互（`Stream` + 受检异常的常见陷阱）。

#### 3.3.2 `LlmEvidence` **刻意不含 URL**

契约 §13.5：「**模型生成的 URL 不直接作为证据；引用只能绑定任务开始时固化的证据候选**」。

本轮把它做成**结构性保证**而不是事后校验：模型能看到的证据视图里
**没有 `sourceUrl` 字段**。模型因此不可能"生成一个可信 URL"——
它连原始 URL 都没见过。URL 在 M3-08 落库时由**服务端持有的证据候选**提供，
模型全程不参与。

这条规则一旦只靠校验器实现，就总存在"校验器漏了一种 URL 形态"的窗口；
从端口形状上删掉这个字段，窗口不存在。

### 3.4 上下文构建与预览（AI-02）

`stock-ai/application` 新增：

| 类型 | 说明 |
| --- | --- |
| `AiContextPreviewService` | 用例：目标校验 → 取数 → 固化 → 预览 |
| `AiContextPreviewRequest` | 请求：`scene` / `targets` / `analysisStartAt` / `analysisEndAt` |
| `AiTargetRequest` | 请求元素：`targetType` / `targetId` / `targetRole` |
| `AiContextPreview` | 响应：`targets` / `dataCategories` / `newsCount` / `limitations` / `canGenerate` |
| `AiContextTarget` | 响应元素：`targetType` / `targetId` / `targetCode` / `targetName` / `targetRole` |
| `AiDataCutoff` | 响应元素：`category` / `dataCutoffAt` |
| `AiContextType` | 枚举：`QUOTE` / `KLINE` / `SECTOR` / `BUSINESS` / `NEWS` / `CALENDAR` / `RULE`（与 V6 `ck_ai_context_type` 同集合） |
| `InvalidAiTargetException` | → 400，业务码 `AI_TARGET_INVALID` |
| `InvalidAiContextQueryException` | → 400，参数校验失败 |

`stock-ai/domain` 新增：

| 类型 | 说明 |
| --- | --- |
| `AiContextSnapshot` | 记录：`snapshotNo` / `contextType` / `sourceObjectType` / `sourceObjectId` / `sourceKey` / `dataTime` / `dataCutoffAt` / `contentHash` / `contextData` / `isEvidenceCandidate` |
| `AiEvidenceCandidate` | 记录：`evidenceNo` / `evidenceType` / `sourceObjectType` / `sourceObjectId` / `sourceTitle` / `sourceUrl` / `evidenceSummary` / `sourcePublishedAt` / `dataTime` / `accessStatus` / `contentHash` |
| `AiContextBuilder` | 从行情/资讯端口取数并固化上下文与证据候选 |
| `AiContextBuildResult` | 记录：`snapshots` / `evidenceCandidates` / `limitations` / `coreDataAvailable` |
| `AiContentHasher` | 端口：`contentHash` 的算法（模拟实现用 `SimulatedHashing`） |

#### 3.4.1 可用数据类别只返回**实际接入了的**

V6 的 `ck_ai_context_type` 有 7 类。本轮只接入 **3 类**：

| 类别 | 本轮 | 数据来源 |
| --- | --- | --- |
| `QUOTE` | ✅ | `QuoteSnapshotBatchProvider` 整批快照 |
| `SECTOR` | ✅ | `SectorProvider` 主数据 + 成分关系（仅 `SECTOR` 场景） |
| `NEWS` | ✅ | `NewsEvidenceProvider`（§3.5） |
| `KLINE` | ❌ | 需要 `KlineProvider` 的区间序列，属后续里程碑 |
| `BUSINESS` | ❌ | 主营业务数据源未就位 |
| `CALENDAR` | ❌ | 交易日历**已存在**，但"事件日历"是另一回事，未就位 |
| `RULE` | ❌ | 限幅规则**已存在**，但作为 AI 上下文的价值待定 |

AI-02 的 `dataCategories` **只列出实际取到的类别及其 `dataCutoffAt`**。
为未接入的类别补一个"看起来合法的截止时间"就是编造——用户会以为
K 线参与了分析，而实际没有。

#### 3.4.2 `canGenerate` 与 `limitations`

| 情况 | `canGenerate` | `limitations` | 依据 |
| --- | --- | --- | --- |
| 核心行情齐全 | `true` | 可能非空（如"资讯不足"） | — |
| 核心行情缺失 | `false` | 含"核心行情缺失"说明 | 契约 §13.5：核心行情缺失时**拒绝创建**任务 |
| 资讯为空 | `true` | 含"资讯缺失，报告将为受限分析" | 契约 §13.5：新闻缺失可产生 `LIMITED` 报告 |
| 资讯来自不允许 AI 的来源 | `true` | 含"部分来源不允许进入 AI 上下文" | M3-04 的 `allow_ai_analysis` |

**AI-02 不抛异常而是返回 `canGenerate=false`**：预览的作用正是"提交前告知"。
抛 400 会让用户看不到"为什么不能生成"——而契约明确 AI-02 的用途是
「在正式消耗配额前展示将使用的数据摘要」。

`AI_CORE_DATA_MISSING` 这个业务码在 AI-03（创建任务）才用得上，属 M3-07。

#### 3.4.3 分析区间校验

- `analysisStartAt` / `analysisEndAt` 都可为空（用场景默认档位）；
- 只给一个端点 → 400（区间必须成对或都不给）；
- `endAt < startAt` → 400；
- 区间跨度 > `maxCustomDays`（365）→ 400。

#### 3.4.4 目标校验

按 §3.2 的场景规则逐个校验，任何一条不满足即 `AI_TARGET_INVALID`：

1. `targetType` 必须在场景的 `allowedTargetTypes` 内；
2. 目标数量在 `[minTargets, maxTargets]`；
3. `COMPARE` 场景**恰好 1 个 `PRIMARY`**；
4. `CONTEXT` 角色**只允许服务端生成**（契约 §13.1）→ 请求里出现即 400；
5. `targetId` 必须能解析到真实主数据（证券走 `SecurityIdentityProvider`，
   板块走 `SectorIdentityProvider`，市场代码走白名单）；
6. 同一 `(targetType, targetId)` 重复出现 → 400（V6 `uk_ai_task_target` 会撞）。

### 3.5 资讯证据的消费侧（关闭 M3-04 欠账）

`stock-news/domain` 新增：

| 类型 | 说明 |
| --- | --- |
| `NewsEvidenceProvider` | 端口：按目标取"可进入 AI 上下文"的资讯 |
| `NewsEvidence` | 记录：`newsId` / `newsType` / `title` / `summary` / `sourceName` / `publishedAt` / `originalUrl` / `originalAccessStatus` |

由 **`NewsQueryService` 实现**（它已持有 `visible()` 过滤链所需的全部依赖）。

```java
List<NewsEvidence> evidenceFor(
        NewsTargetType targetType, String targetId,
        OffsetDateTime startAt, OffsetDateTime endAt, int limit);
```

**为什么实现放在 `NewsQueryService` 而不是 `stock-ai`**：

契约 §11.2 的可见性判据有 5 条（内容状态、去重状态、来源授权、内容授权期、关联确认）。
在 `stock-ai` 里重写一遍，就出现**两处"哪些资讯可见"的知识**——
而口径分歧**不会报错**，只会让"资讯列表里看得到、AI 却说没有依据"。
M2-10 已确立过这条原则：同一个事实只允许一处实现。

`allowAiAnalysis` 的判定是 `NewsSource` 的属性，天然属于资讯域。
本轮在 `visible()` 之上**再叠一层** `allowAiAnalysis` 过滤：

```
visible()  （内容状态 / 去重 / 来源授权 / 内容授权期）
   └─ 关联已确认且匹配目标
        └─ 来源 allowAiAnalysis == true      ← 本轮新增
```

`limit` 为必需参数（默认 20，由配置提供）：把全库资讯塞进 prompt 既不现实也不必要。
**在过滤链之后截断**，而不是先截断再过滤——否则"最新 20 条里有 15 条不允许进 AI"
会静默地把可用证据压到 5 条。

### 3.6 确定性模拟 LLM Provider

`stock-integration/ai/SimulatedLlmProvider`。

**确定性**：同一 `LlmRequest` → 逐位相同的 `LlmCompletion`。
随机源只用 `SimulatedHashing`（SplitMix64），不用 `Math.random` / `UUID`。

**固定六个章节**（契约 §13.5「完整报告固定包含…」+ V6 `ai_report` 的列）：

| `section` | V6 列 | 是否必填 |
| --- | --- | --- |
| `CORE_CONCLUSION` | `core_conclusion` | 是 |
| `QUOTE_EVIDENCE` | `quote_evidence` | 是 |
| `COMPARISON_ANALYSIS` | `comparison_analysis` | 否（仅 `COMPARE`） |
| `EVENT_CLUES` | `event_clues` | 否（无资讯时） |
| `RISK_AND_UNCERTAINTY` | `risk_and_uncertainty` | 是 |
| `DISCLAIMER` | `disclaimer` | 是 |

**输出内容的口径**（见 §7「已知取舍」）：模拟实现产出**结构占位文本**——
结构完整、引用编号真实、**不编造数字事实**。每个章节的正文由
「固定模板句 + 引用的证据编号 + 被引证据摘要的前若干字」组成。
事实数字由证据摘要承载，不由模型生成。

**引用合法性**：引用编号**只从 `request.evidenceCandidates()` 里取**。
候选为空时，`EVENT_CLUES` 章节**不产生**（而不是产出"无资讯"之外的空话）。

**故障注入**：构造参数 `faultMode`（默认 `NONE`）支持：
`NONE` / `INVALID_CITATION`（产出候选集合外的编号）/ `TIMEOUT` / `RATE_LIMIT`。
默认路径产出**合法**结果，故障路径供 M3-07 的校验器与重试逻辑测试使用——
不把"故意产出非法引用"塞进默认路径，否则正常链路永远跑不通。

### 3.7 Web 层

`stock-backend/web/AiController`（新增）：

| 编号 | 方法 | 路径 | 权限 |
| --- | --- | --- | --- |
| AI-01 | `GET` | `/api/v1/ai/scenes` | `USER` |
| AI-02 | `POST` | `/api/v1/ai/context-previews` | `USER` |

- 两个端点都是 `USER` 权限。`SecurityConfiguration` 的 `anyRequest().authenticated()`
  已覆盖 `/api/v1/ai/**`（它不在任何 `permitAll` 白名单里），**无需改动安全配置**——
  但要有契约测试钉住"未登录返回 401"，避免将来有人顺手把 `/ai/**` 加进白名单。
- AI-02 的请求体用 DTO 承接，`targetType` / `targetRole` 声明为 `String` 并在用例层解析
  （同项目惯例：枚举绑定失败会被 Spring 转成 `MethodArgumentTypeMismatchException`，
  语义上把"取值不在白名单"混同为"参数格式错误"）。
- 响应**不含** `systemPrompt` / `userPrompt` / 完整内部 Prompt
  （契约 §13.1：「不返回完整内部 Prompt 或未授权正文」）。

### 3.8 装配

`BackendConfiguration` 新增 Bean：

| Bean | 实现 | 说明 |
| --- | --- | --- |
| `AiSceneCatalog` | 具体类，直接 `new` | 无依赖 |
| `NewsEvidenceProvider` | **复用已有的 `newsQueryService` Bean** | 不新声明计数型 Bean——与 `NewsCountProvider` 同一处理方式 |
| `AiContentHasher` | `SimulatedContentHasher`（integration） | 确定性哈希 |
| `AiContextBuilder` | 具体类 | 依赖行情/资讯端口 + hasher |
| `AiContextPreviewService` | 具体类 | 依赖 catalog + builder + 身份解析 |
| `LlmProviderPort` | `SimulatedLlmProvider` | 依赖 hasher + 配置 |

**`NewsEvidenceProvider` 不单独声明 Bean**：`NewsQueryService` 已实现它，
直接复用同一个实例。声明第二个 Bean 会让"哪些资讯可见"出现两份实现。

### 3.9 配置

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `stock.ai.provider-code` | `SIMULATED` | 与 V6 `ai_task.provider_code` 对齐 |
| `stock.ai.model-code` | `sim-analyst-v1` | 模拟模型标识 |
| `stock.ai.prompt-version` | `prompt-v1` | 模板版本。**本轮不写入 `application.yml`**：消费方在 M3-07（见 §7 第 8 条） |
| `stock.ai.content-schema-version` | `report-schema-v1` | 输出结构版本。**同上，本轮不落地** |
| `stock.ai.news-evidence-limit` | `20` | 单任务资讯证据条数上限 |

---

## 4. 一致性约束

1. **`LlmEvidence` 不含 URL**（§3.3.2）——结构性保证，不靠校验器。
2. **资讯可见性判据只有一份**：`NewsQueryService.visible()`；AI 侧在其上叠 `allowAiAnalysis`（§3.5）。
3. **`dataCategories` 只列实际取到的类别**，不为未接入类别补截止时间（§3.4.1）。
4. **核心行情缺失时 `canGenerate=false` 并给出说明**，不抛异常、不静默通过（§3.4.2）。
5. **模拟 Provider 确定性**：同一请求逐位相同；随机源只用 `SimulatedHashing`（§3.6）。
6. **引用编号必须落在候选集合内**：模拟实现的默认路径即满足（§3.6）。
7. **枚举集合与 V6 的 CHECK 约束逐字对齐**（`AiScene` / `AiTargetType` / `AiTargetRole` /
   `AiContextType` / `LlmErrorCategory` / `AiReportSection` 对应的列）。
8. **对外标识一律是字符串**：`AiContextTarget.targetId` 返回 `sim-600519` / `sim-bk0001` / `CN`，
   不返回 bigint（M2-06 / M2-11 已各踩过一次）。
9. **比例用小数、价格用定点字符串**：本项目既有口径，不在 AI 域另立一套。
10. **时间渲染钉 `Asia/Shanghai`**：`Clock` 由 `BackendConfiguration` 注入，测试与 CI（UTC）一致。
11. **`snapshotNo` 从 1 连续递增**（V6 `ck_ai_context_snapshot_no`），
    **`evidenceNo` 从 1 连续递增**（V6 `ck_ai_evidence_no`）。
12. **`targetRole = CONTEXT` 不接受客户端传入**（契约 §13.1）。

---

## 5. 验证方式

### 5.1 单测（主要）

| 测试类 | 模块 | 覆盖 |
| --- | --- | --- |
| `AiSceneCatalogTest` | `stock-ai` | 5 个场景规则、`questionMaxLength=500`、档位、未知场景返回空 |
| `AiContextPreviewServiceTest` | `stock-ai` | 目标规则矩阵（含 `COMPARE` 的 PRIMARY 唯一性）、区间校验、`canGenerate` 四态、`dataCategories` 不含未接入类别、资讯条数只算允许 AI 的来源 |
| `AiContextBuilderTest` | `stock-ai` | 快照与证据候选构建、`snapshotNo`/`evidenceNo` 连续性、`contentHash` 确定性、降级保留 `limitations` |
| `LlmRequestTest` | `stock-ai` | `LlmEvidence` 无 URL 字段；`LlmRequest` 只携带证据视图 |
| `SimulatedLlmProviderTest` | `stock-integration` | 确定性（两次调用逐位相同）、六章节齐全与顺序、引用全在候选集合内、空候选不产 `EVENT_CLUES`、故障注入四态 |
| `NewsQueryServiceTest`（追加） | `stock-news` | `evidenceFor` 的过滤：`allowAiAnalysis=false` 排除、非 CONFIRMED 排除、授权失效来源排除、区间过滤、`limit` 在过滤后生效 |

### 5.2 契约测试（`stock-backend`）

| 用例 | 断言 |
| --- | --- |
| AI-01 未登录 | 401 |
| AI-01 登录后 | 200，5 个场景，字段齐全，`questionMaxLength=500` |
| AI-02 未登录 | 401 |
| AI-02 合法请求 | 200，`canGenerate=true`，`dataCategories` 含 `QUOTE`/`NEWS` |
| AI-02 非法目标 | 400，`code=AI_TARGET_INVALID` |
| AI-02 响应不含内部 Prompt | 响应体不含 `systemPrompt` / `userPrompt` 字段 |

### 5.3 构建与回归

```
mvn -q -pl stock-ai -am test
mvn -q test
TZ=UTC mvn -q test          # CI 是 UTC，时间相关断言必须复跑
```

### 5.4 Docker 可用时的真实端到端

真实栈起好后用 curl 打 AI-01 / AI-02，核对：
- 场景目录与契约一致；
- `dataCutoffAt` 来自真实行情批次与资讯同步状态；
- 资讯条数与 `/news` 列表实际条数自洽；
- 未登录访问返回 401。

---

## 6. 改动清单

### 新增模块 `stock-ai`（后端第 8 个模块）

| 文件 | 说明 |
| --- | --- |
| `backend/stock-ai/pom.xml` | 依赖 `stock-common` / `stock-market` / `stock-news` / `spring-boot-starter-json`；**不引入 MyBatis**（本轮不落库） |
| `domain/`（25 个类型） | 8 个枚举（`AiScene` / `AiTargetType` / `AiTargetRole` / `AiContextType` / `AiEvidenceType` / `AiEvidenceAccessStatus` / `AiReportSection` / `LlmErrorCategory`）；2 个端口（`AiContentHasher` / `LlmProviderPort`）；值类型（`LlmRequest` / `LlmChunk` / `LlmCompletion` / `LlmUsage` / `LlmEvidence` / `LlmProviderException` / `AiAnalysisRange` / `AiSceneDefinition` / `AiDataCutoff` / `AiContextSnapshot` / `AiEvidenceCandidate` / `AiContextTarget` / `AiContextBuildResult`）；`AiSceneCatalog`（静态规则表）、`AiContextBuilder`（取数与固化） |
| `application/`（6 个类型） | `AiTargetRequest` / `AiContextPreviewRequest` / `AiContextPreview` / `AiContextPreviewService`（AI-02 用例）/ `InvalidAiTargetException` / `InvalidAiContextQueryException` |
| 测试（4 个） | `AiFixtures`（夹具）、`AiSceneCatalogTest` **12 项**、`AiContextBuilderTest` **21 项**、`AiContextPreviewServiceTest` **38 项** |

### `stock-news`（关闭 M3-04 的 `allow_ai_analysis` 欠账）

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `domain/NewsEvidence.java` | 新增 | 可进入 AI 上下文的资讯视图（含 `originalUrl` / `originalAccessStatus`） |
| `domain/NewsEvidenceProvider.java` | 新增 | 端口：`evidenceFor(targetType, targetId, startAt, endAt, limit)` |
| `application/NewsQueryService.java` | 修改 | 实现 `NewsEvidenceProvider`：复用既有 `visible()` 过滤链，在其上叠 `source().allowAiAnalysis()`；`limit` 在**过滤之后**截断；顺带把 `targetFilterOf` 里的目标解析抽成 `resolveTarget`，与列表接口共用一处 |
| `test/.../NewsEvidenceQueryTest.java` | 新增 | **14 项** |
| `test/.../NewsFixtures.java` | 修改 | 新增 `sourceWithoutAi(...)` 工厂与 9 参 `article(...)` 重载（可指定 `publishedAt`，原 8 参重载的第 8 参是 `rightsExpireAt`） |

### `stock-integration`

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `ai/SimulatedContentHasher.java` | 新增 | `contentHash` 的确定性实现（`SimulatedHashing.mix` 四次派生；`hashOf(Map)` 用 `TreeMap` 保证与迭代顺序无关） |
| `ai/SimulatedLlmProvider.java` | 新增 | 确定性模拟 LLM：六章节按序切片产出、引用只取候选集合内的编号、用量按字符数估算；故障注入 `NONE` / `INVALID_CITATION` / `TIMEOUT` / `RATE_LIMIT` |
| `test/.../SimulatedLlmProviderTest.java` | 新增 | **16 项** |
| `pom.xml` | 修改 | 新增 `stock-ai` 依赖（架构 §11.3：端口在 `stock-ai`、实现在本模块） |

### `stock-backend`

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `web/AiController.java` | 新增 | AI-01 `GET /api/v1/ai/scenes`、AI-02 `POST /api/v1/ai/context-previews`，均 `USER` 权限 |
| `web/GlobalExceptionHandler.java` | 修改 | 新增 `InvalidAiTargetException` → 400 `AI_TARGET_INVALID`；`InvalidAiContextQueryException` → 400 `INVALID_REQUEST` |
| `config/BackendConfiguration.java` | 修改 | 新增 5 个 Bean：`AiSceneCatalog` / `AiContentHasher` / `AiContextBuilder` / `AiContextPreviewService` / `LlmProviderPort`；`NewsEvidenceProvider` **复用已有的 `newsQueryService` Bean**，不新声明 |
| `resources/application.yml` | 修改 | 新增 `stock.ai.provider-code` / `model-code` / `news-evidence-limit` **三项**（见下方"已知取舍 8"） |
| `test/.../AiControllerContractTest.java` | 新增 | **8 项** |
| `test/.../SecurityConfigurationTest.java` | 修改 | 新增 1 项：游客访问 AI-01 / AI-02 均 401 |
| `pom.xml` | 修改 | 新增 `stock-ai` 依赖 |

### 根与文档

| 文件 | 动作 |
| --- | --- |
| `backend/pom.xml` | 修改：`<modules>` 新增 `stock-ai`（位于 `stock-news` 之后） |
| `docs/superpowers/specs/2026-09-20-ai-provider.md` | 新增（本文件） |
| `TASKS.md` / `PROJECT_STATUS.md` / `CHANGELOG.md` | 修改：同步交付详情、状态与已知问题 |

---

## 7. 已知取舍

1. **`defaultPreset = LAST_5_TRADING_DAYS` 是产品判断**，契约只规定了档位集合
   （「1/5/20 个交易日或自定义范围」）与上限（1 年），未规定默认选中哪一档。
   选中间档的理由：1 日太短（量价特征不成立）、20 日太长（对"最近怎么样"响应迟钝）。
   **记入 `PROJECT_STATUS.md` 已知问题**，待产品确认。

2. **模拟 LLM 输出是结构占位文本**，不是真实分析。它的价值在于让链路可跑、
   结构可校验、引用可核对——**不假装是分析结论**。真实 LLM 接入后替换实现类即可。
   事实数字由证据摘要承载，模型不生成数字。

3. **`dataCategories` 只有 3 类**（`QUOTE` / `SECTOR` / `NEWS`），V6 的 CHECK 有 7 类。
   未接入的 4 类不返回，而不是返回"未知"——见 §3.4.1。

4. **`contentHash` 用模拟哈希**（`SimulatedHashing`）。真实环境应改用 SHA-256，
   端口（`AiContentHasher`）已就位，替换实现即可。**当前值不是密码学安全的**。

5. **不落库**：本轮的上下文快照与证据候选只存在于内存中，M3-07 调用一次即丢弃。
   V6 的 8 张表仍为零行。这是刻意的——落库需要任务 ID，而任务在 M3-07 才有。

6. **`SimulatedLlmProvider` 的 token 计数是估算**（按字符数折算），不是真实 tokenizer。
   M3-09 的用量统计据此计算成本时会偏高或偏低；真实 Provider 会返回真实计数。

7. **AI-02 的 POST 无副作用但不做幂等**：它不创建资源，重复调用结果相同，
   因此不需要 `Idempotency-Key`。契约 §13.2 也没给它这个 header。

8. **`application.yml` 只写 3 项 `stock.ai.*`，而 §3.9 的表列了 5 项**。
   `prompt-version` 与 `content-schema-version` 的消费方在 M3-07（任务编排）——
   它们要写进 `ai_task.prompt_version` 与报告的结构版本列。本轮写进配置也没有任何代码读它，
   而**一个没有消费方的配置项，读的人无法判断它到底影响什么**。
   与"没有数据来源的字段降级为尚未实现"同一条原则：宁可留空也不写。

---

## 8. 验收结果

### 8.1 单元与契约测试（本机实测）

| 项目 | 结果 |
| --- | --- |
| `mvn test`（默认时区） | 9 模块全绿，**后端 749 项**：common 1 / market 156 / news 103 / system 89 / **ai 71** / integration 127 / backend 190 / job 12 |
| `TZ=UTC mvn test` | 同样全绿（CI 是 UTC） |
| `InfrastructureIntegrationTest` | **本轮实测通过**（本机 Docker 可用，Testcontainers 真实 MySQL 8.4 + Redis） |

本里程碑新增的测试：`AiSceneCatalogTest` 12、`AiContextBuilderTest` 21、
`AiContextPreviewServiceTest` 38、`NewsEvidenceQueryTest` 14、`SimulatedLlmProviderTest` 16、
`AiControllerContractTest` 8、`SecurityConfigurationTest` +1。

### 8.2 真实端到端（Docker 全栈 + `frontend/e2e/ai.real.mjs`）

栈：`mysql` / `redis` / `flyway` / `stock-api` / `stock-job` / `frontend`，空库 Flyway V1→V8。

| 核对项 | 实测结果 |
| --- | --- |
| 未登录访问 AI-01 / AI-02 | 均 **401** |
| AI-01 | 200，5 个场景，`scene` 顺序为 `MARKET` / `SECTOR` / `STOCK` / `STOCK_RISK` / `COMPARE`；每个场景 `questionMaxLength=500`、`defaultRange.presets` 3 档、`defaultPreset=LAST_5_TRADING_DAYS`、`maxCustomDays=365`；响应体不含 `systemPrompt` / `userPrompt` / `promptTemplate` |
| AI-02（`STOCK` + `sim-600519`） | 200，`canGenerate=true`，目标摘要取自主数据（`600519 模拟证券600519`），响应不含 `storageId` / `contentHash` / 内部 Prompt |
| 行情截止时间 | `QUOTE` 的 `dataCutoffAt = 2026-09-18T15:00:00+08:00`——来自**真实行情批次的数据时间**，不是「现在」 |
| `dataCategories` | 只出现 `QUOTE`（该标的当时无资讯）；`KLINE` / `BUSINESS` / `CALENDAR` / `RULE` 四类未接入，**不出现** |
| AI-02（`COMPARE` 两标的） | 200，角色顺序 `PRIMARY` / `COMPARISON`；两个标的的 `QUOTE` 截止时间与单标的场景**一致**（同一批次） |
| AI-02（`MARKET` + `CN`） | 200，`canGenerate=true` |
| 非法请求 7 种 | 未知场景 / 只给区间起点 / 跨度超 365 天 → 400 `INVALID_REQUEST`；目标类型与场景不匹配 / `COMPARE` 只有一个目标 / 客户端传 `CONTEXT` / 证券标识不存在 → 400 `AI_TARGET_INVALID` |

### 8.3 `allow_ai_analysis` 的受控实验（真实链路）

库里 `SIM_MEDIA_A` 有 1 条与 `sim-002343` 的 `CONFIRMED` 关联。对同一标的、同一时刻做三步观测：

| 步骤 | AI-02 `newsCount` | AI-02 `dataCategories` | `GET /news?securityId=sim-002343` |
| --- | --- | --- | --- |
| 基线（`allow_ai_analysis=1`） | **1** | `[QUOTE, NEWS]` | `total=1` |
| 临时置 `allow_ai_analysis=0` | **0** | `[QUOTE]` | `total=1`（**不变**） |
| 恢复 `allow_ai_analysis=1` | **1** | `[QUOTE, NEWS]` | `total=1` |

结论：**同一条资讯在列表里可见、在 AI 上下文里不可见**——这正是 M3-04 落下的
`news_source.allow_ai_analysis` 标记的预期语义。并且 `NEWS` 类别会随实际取数结果
**从 `dataCategories` 里消失**，而不是返回一个「条数为 0 的 NEWS 类别」。
