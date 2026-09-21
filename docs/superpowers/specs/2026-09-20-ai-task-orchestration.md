# M3-07 设计：AI 任务编排 + SSE 流式契约

> 里程碑：`M3-07`（P0，依赖 `M3-06`）。交付第 9 个模块 `stock-ai-worker`、
> 契约 AI-03~AI-08 六个接口、SSE 六类事件，以及 V6 六张表从零行到有真实数据。

## 1. 背景

### 1.1 为什么现在做

M3-06 把 AI 域推进到「接口可用、上下文可固化、引用可核对」，但交付的是**同步预览**：
AI-02 一次请求就把上下文取回来、当场返回。真正要跑一次分析还没有通路——V6 的九张表仍然零行，
契约 AI-03~AI-08 六个接口都不存在，前端 `/ai` 上的「生成分析」按钮仍是 `disabled`。

本里程碑交付**从「受理」到「出报告」的完整异步链路**：
创建任务（202）→ 投递队列 → Worker 执行 → SSE 中继 → 报告落库。

### 1.2 本轮在整条 AI 链路里的位置

| 环节 | 里程碑 | 本轮状态 |
| --- | --- | --- |
| 场景目录、上下文固化、LLM 端口 | M3-06 | ✅ 已完成，本轮**直接复用** |
| 任务编排（AI-03~AI-08）、SSE 中继 | **M3-07** | 本轮交付 |
| Worker 执行（消费、调用、校验、落报告） | **M3-07** | 本轮交付 |
| 证据落库 + 反馈 + 历史（HIS-01~09） | M3-08 | 不在本轮 |
| 配额与用量统计（`ai_usage`、ADM-AI-05） | M3-09 | 不在本轮 |
| 前端 `/ai` 工作台与 `/history` 接真实接口 | M3-10 | 不在本轮 |

### 1.3 数据来源

- **上下文**：完全复用 M3-06 的 `AiContextBuilder`（行情走 `QuoteBatch`、板块走 `SectorProvider`、
  资讯走 `NewsEvidenceProvider`）。**本轮不新增任何取数路径**——一旦新增，AI 看到的数字
  就会与页面上的分叉，而分叉不会报错。
- **LLM**：M3-06 的 `LlmProviderPort` + `SimulatedLlmProvider`（确定性六章节 + 引用约束 + 故障注入四态）。
  本轮是它第一次被真正消费。
- **任务与报告**：V6 的 `ai_session` / `ai_task` / `ai_task_target` / `ai_context_snapshot` /
  `ai_message` / `ai_report` 六张表（此前**零行零引用**）。

### 1.4 可直接复用的既有代码

| 复用对象 | 位置 | 说明 |
| --- | --- | --- |
| `IdempotencyGuard` / `IdempotencyStore` | `stock-system/idempotency` | 它的 javadoc 里本来就写着 AI-03/06/07/08 是消费方，M3-01 就按「8 个接口复用」设计的 |
| `AiContextBuilder` | `stock-ai/domain` | 固化上下文 + 证据候选 + `limitations` + `coreDataAvailable` |
| `AiSceneCatalog` / `AiSceneDefinition` | `stock-ai/domain` | 场景规则表（含 `questionMaxLength`） |
| `AiContextPreviewService` 的校验与解析 | `stock-ai/application` | **抽出来共用**，见 §3.3 |
| `SecurityIdentityProvider` / `SectorIdentityProvider` | `market/domain` | 对外标识 ↔ bigint 代理键，构词规则只有一处 |
| Redis 实现的先例 | `market/infrastructure`、`system/auth` | 直接用 `StringRedisTemplate`，端口 + 实现同模块 |

## 2. 目标与非目标

### 2.1 目标

1. **六个接口** AI-03~AI-08 全部可用，含 202 受理语义、`Idempotency-Key` 幂等、
   单用户并发上限、每日额度。
2. **SSE 六类事件**齐全（`snapshot` / `status` / `chunk` / `report` / `error` / `done`），
   支持 `Last-Event-ID` 补发。
3. **第 9 个模块 `stock-ai-worker`**：Consumer Group 消费 + 执行 + 心跳 + 超时 + 恢复扫描。
4. **V6 六张表从零行到有真实数据**，且数据由 worker 真实执行写入（非夹具）。
5. **端到端可验**：创建 → 状态推进 → SSE 收到 `chunk` 与 `report` → 库里能查到报告。

### 2.2 非目标（写明归属，不在本轮偷偷扩大范围）

| 不做的事 | 归属 |
| --- | --- |
| 证据落库（`ai_evidence`）与 HIS-07 | M3-08 |
| 反馈（`ai_feedback`）与 HIS-08 / HIS-09 | M3-08 |
| 历史会话接口 HIS-01~HIS-06 | M3-08 |
| 用量与成本（`ai_usage`）、ADM-AI-01~ADM-AI-06 | M3-09 / M3-11 |
| 全站限流（契约 §22.1 的「自选写 60/min」等） | 已知问题 #17，需整体排期，不按模块零散加 |
| 真实 LLM Provider | 真实数据源接入时替换实现即可（端口已就位） |
| 前端 `/ai` 工作台、`/history` 接真实接口 | M3-10 |
| `KLINE` / `BUSINESS` / `CALENDAR` / `RULE` 四类上下文 | 各自数据源就位后 |

## 3. 设计

### 3.1 为什么 worker 独立成第 9 个模块

架构 §3.2 把 `stock-ai-worker` 列为部署单元，§6 的模块职责表写明
`stock-backend`「**不写跨模块 SQL，不执行采集和 AI 长任务**」。三条理由：

1. **生命周期不同**：worker 是 Consumer Group 的至少一次消费 + 心跳 + 超时恢复；
   `stock-job` 是 cron 调度。混在一个进程里，一次 LLM 超时会占住定时采集的线程池，
   而两者的失败处理、重试语义、可观测指标完全不同。
2. **不暴露 HTTP**：架构要求 worker「不开放公开业务接口」。
   而 SSE 中继**必须**留在 `stock-api`——它需要 JWT 鉴权与 HTTP 长连接，
   所以 worker 只负责执行与写 Redis 事件流，由 `stock-api` 中继出去。
3. **成本可控**：`backend/Dockerfile` 已按 `APP_MODULE` 参数化，
   compose 只加一个服务块（复用 `&stock-environment` 锚点），不需要新的 Dockerfile。

### 3.2 任务状态机（纯函数）

```
CREATED ──▶ PREPARING ──▶ QUEUED ──▶ RUNNING ──▶ VALIDATING ──▶ COMPLETED
   │            │            │           │             │
   └────────────┴────────────┴───────────┴─────────────┴──▶ CANCELED / FAILED / TIMED_OUT
```

允许的迁移是**白名单**：

| 起点 | 允许的去向 |
| --- | --- |
| `CREATED` | `PREPARING`、`QUEUED`、`CANCELED`、`FAILED` |
| `PREPARING` | `QUEUED`、`RUNNING`、`FAILED`、`TIMED_OUT`、`CANCELED` |
| `QUEUED` | `PREPARING`（抢占执行权）、`RUNNING`、`FAILED`、`TIMED_OUT`、`CANCELED` |
| `RUNNING` | `QUEUED`（自动重试）、`VALIDATING`、`FAILED`、`TIMED_OUT`、`CANCELED` |
| `VALIDATING` | `QUEUED`（恢复扫描重跑）、`COMPLETED`、`FAILED`、`TIMED_OUT`、`CANCELED` |
| 终态（`COMPLETED` / `CANCELED` / `FAILED` / `TIMED_OUT`） | 无 |

三个要点：

- **终态不可回退**（契约 §13.5 原文）。
- **白名单而不是「非终态即可」**：worker 崩溃重启后，若允许 `RUNNING → COMPLETED`，
  就会跳过 `VALIDATING`——一份**没经过引用与安全校验**的文本被标成成功报告。
  这类缺陷不会报错，只会让用户看到一份带编造引用的"成功"报告。
- **`cancel_requested` 是意图，`status` 是事实**，两者分开存（V6 表就是这么设计的）：
  取消请求到达时任务可能正在调 LLM，无法立即中断，所以只能置位意图、由 worker 在检查点兑现。
- **取消一个已完成的任务**：`effectiveImmediately=false` 且 **`status` 不变**（契约 AI-06 原文），
  不能把 `COMPLETED` 改成 `CANCELED`——报告已经存在了。

### 3.3 校验与目标解析抽出来共用（不写第二份）

M3-06 把「场景校验 → 区间校验 → 目标解析」写成了 `AiContextPreviewService` 的四个私有方法。
AI-03 需要**完全相同**的一套：同一份请求，预览说合法、创建说非法（或反过来）是不可接受的。

复制一份的后果是「什么请求算合法」出现两份知识，而**口径分歧不会报错**——
这正是 M2-10 立下的规矩：同一个事实只允许一处实现。

抽出 `AiTaskRequestResolver`（`stock-ai/application`），把 `definitionOf` / `validateRange` /
`resolveTargets` / `resolveOne` 整体搬过去；`AiContextPreviewService` 改为委托。

**一致性写成测试**：同一份非法请求（未知场景 / 半截区间 / 目标数量不对 / 重复目标 /
无法解析的标识 / `CONTEXT` 角色），预览入口与创建入口必须报**同一个业务码与同一条消息**。

### 3.4 创建任务（AI-03）

四道闸门，顺序固定——顺序本身是接口的一部分，同一份请求必须稳定地报同一条错误：

1. **幂等**（`Idempotency-Key` 必填，缺失 → 400 `IDEMPOTENCY_KEY_MISSING`）
2. **目标与区间合法性**（复用 §3.3 的解析器 → 400）
3. **并发上限**（单用户 2 个活跃任务 → 429 `AI_CONCURRENCY_EXCEEDED`）
4. **每日额度**（→ 429 `AI_QUOTA_EXCEEDED`）
5. **核心行情**（`coreDataAvailable=false` → 503 `AI_CORE_DATA_MISSING`）

#### 幂等的两层，以及 `request_id` 怎么算

- **第一层**：`IdempotencyGuard`（Redis，键 = `(scope='ai-task:create', userId, key)`）——
  回放**第一次的完整响应**，重复提交不产生第二个任务。
- **第二层**：`ai_task.request_id` 唯一索引——契约 §3.7 明确
  「AI 任务由数据库 `request_id` 唯一约束兜底，重复创建不得重复消耗大模型额度」。

`request_id` 是 `char(36)`。**不能把客户端 key 直接写进去**：
`Idempotency-Key` 只在「同一用户」内唯一（契约 §3.7），两个用户传同一个 key
会撞上**全局**唯一索引，第二个用户拿到 `DuplicateKeyException`——而正确行为是两个人都该成功。

所以 `request_id` 由 `(userId, key)` **确定性派生**成 UUID 形状的字符串
（`UUID.nameUUIDFromBytes`，即 v3 形状）：
同一 `(user, key)` 稳定复现 → 唯一约束成为幂等的第二道防线；
不同用户同 key 得到不同值 → 不误伤。

#### 并发与额度的口径

- **活跃** = `status ∈ {CREATED, PREPARING, QUEUED, RUNNING, VALIDATING}`。
- **今日** = `Asia/Shanghai` 自然日（时间统计一律钉这个时区，CI 是 UTC）。
- `quota` 对象：`dailyLimit`（配置）、`used`（今日已创建任务数，**从 `ai_task` 现算**）、
  `remaining = max(0, dailyLimit - used)`。
- **每一个数字都是真的**：`used` 不是预留位，是 count 出来的。宁可它暂时不等于
  「今日实际 LLM 调用次数」（那需要 `ai_usage`），也不编一个看起来合理的数。

> ⚠️ **M3-09 必须收敛口径**：`ai_usage` 落库后，「今日用量」会有两个来源
> （`ai_task` 计数 vs `ai_usage` 聚合），而两者在**重试**时必然分叉
> （一次用户意图算一次，还是算两次？）。M3-07 的 `used` 语义明确为
> 「今日创建的**任务**数」；M3-09 引入成本口径时必须显式决定并改掉一处，
> 否则会出现「页面说还剩 3 次、创建时说不让创建」。已记入已知问题。

#### 为什么创建在核心行情缺失时报 503，而预览只返回 `canGenerate=false`

两个接口的用途不同：

- **预览**是「提交前告知」（契约 §13.1），报 400/503 会让用户看不到**为什么**不能生成，
  所以它返回 `canGenerate=false` + `limitations`。
- **创建**是「要么受理、要么说清为什么不受理」，此时用户已经点了按钮。
  一个 `202` + 一个永远不完成的报告是最坏的答案，所以**必须拒绝**。

HTTP 状态取 **503**（契约 §23.1：503 覆盖「核心行情…暂不可用」），
业务码取契约 §13.5 明列的 `AI_CORE_DATA_MISSING`。

### 3.5 Worker 执行

**消费语义**：`stream:ai:tasks` 的 Consumer Group `ai-worker`，至少一次消费。

**防重复执行的关键是「抢执行权」，而不是「读过就认为在做」**：

```sql
UPDATE ai_task SET status = 'PREPARING', attempt_no = attempt_no + 1, started_at = ?
WHERE id = ? AND status IN ('CREATED', 'QUEUED') AND cancel_requested = 0
```

条件更新拿到 1 行才算抢到；拿到 0 行说明别人在做、或任务已被取消/终态 → 直接 ACK 跳过。
**只有抢到执行权的实例才会调用 LLM**，这样「至少一次消费」不会变成「至少一次计费」。

**执行步骤**（每一步之间都检查 `cancel_requested` 与 `deadline_at`）：

1. 抢执行权 → `PREPARING`
2. 固化上下文（`AiContextBuilder`）→ 写 `ai_context_snapshot`（`snapshot_no` 从 1 连续）
3. `coreDataAvailable=false` → `FAILED` / `AI_CORE_DATA_MISSING` / `error_category=DATA`
4. → `QUEUED` → `RUNNING`（写 `started_at`、`heartbeat_at`）
5. 渲染 Prompt（六章节顺序由 `AiReportSection.inOrder()` 决定）→
   `LlmProviderPort.complete(request, onChunk)`
6. **每个 chunk 立即写 Redis 事件流**（`chunk` 事件），同时刷新 `heartbeat_at`、
   首次片段写 `first_chunk_at`（契约 §13.5 的「首段 P95 ≤ 5 秒」要有数据可测）
7. → `VALIDATING`：校验必填章节齐全、引用编号都落在**任务开始时固化的候选集合**内
8. 校验通过 → 写 `ai_message`(ASSISTANT) + `ai_report` → `COMPLETED` → 发 `report` + `done`
9. 校验不通过 → `FAILED` / `AI_OUTPUT_REJECTED` / `error_category=SAFETY`，发 `error` + `done`
10. `LlmProviderException`：按 `LlmErrorCategory.retryable()` 决定是否自动重试一次
    （`max_attempts=2`，V6 默认值）；不可重试或已用尽 → `FAILED`
11. 超过 `deadline_at`（`created_at + 60s`，契约 §13.5）→ `TIMED_OUT`
12. 任何终态都**释放并发额度**（额度是"活跃任务数"，终态自然不活跃，无需显式释放；
    但**必须**把 `completed_at` 写上，否则恢复扫描会一直捞到它）

**恢复扫描**：V6 专门建了 `idx_ai_task_recovery (status, heartbeat_at, deadline_at)`。
worker 内定时（每 30 秒）扫描两类任务：

- `status='QUEUED'` 且 `created_at` 早于 N 分钟 → 队列消息丢了（`XADD` 成功但进程在 ACK 前挂掉），
  **重新投递**；
- `status IN ('PREPARING','RUNNING','VALIDATING')` 且 `heartbeat_at` 早于 N 分钟 → 执行者死了，
  按 `attempt_no < max_attempts` 决定重投还是标 `TIMED_OUT`。

**临时片段不是最终报告**：契约 §13.4 明确「临时片段可能因最终结构、引用或安全校验失败
而不形成报告，前端必须以 `report` 事件或任务 `COMPLETED` 为成功依据」。
所以 chunk 只写 Redis，**不写 `ai_message.content`**；`ai_message` 只在最终定稿时写一次。

### 3.6 Redis Stream：队列与事件流

| Key | 类型 | 用途 | 保留 |
| --- | --- | --- | --- |
| `stream:ai:tasks` | Redis Stream | 待执行任务（Consumer Group `ai-worker`） | 按长度裁剪 |
| `stream:ai:chunk:{taskId}` | Redis Stream | 状态与临时片段事件 | 完成后 **30 分钟**（契约 §13.4） |

**为什么不用 Pub/Sub**：SSE 要支持 `Last-Event-ID` 补发（契约 §13.4），
而 Pub/Sub 没有历史——断线期间的片段就永久丢了，重连后前端会看到一份残缺的文本。

**事件 ID 直接复用 Redis Stream 的消息 ID**（`1689...-0` 这种单调递增形状）。
`Last-Event-ID` 原样作为 `XRANGE` 的起点，补发语义由 Redis 保证，不需要自己维护序号。
**这也让「`sequence` 字段」有了唯一定义**：它就是 Stream 消息 ID 的序号部分，
不是另起一个计数器——两个计数器必然分叉。

**端口抽象**（便于单测注入假实现，不必起 Redis）：

- `AiTaskQueue`：`enqueue(taskId)` / `receive(count)` / `ack(recordId)` / `requeue(taskId)`
- `AiTaskEventStream`：`append(taskId, event)` / `readAfter(taskId, lastEventId, count)`

实现放 `stock-ai/infrastructure`（照 `market/infrastructure/RedisMarketOverviewStore` 的先例），
端口放 `stock-ai/domain`。

### 3.7 SSE 中继（AI-05）

`stock-backend` 的 `AiTaskStreamController` 返回 `SseEmitter`：

1. 鉴权：任务必须属于当前用户，否则 **404**（契约 §23.1：为防水平越权而隐藏他人资源存在性）。
2. 建连后**先发 `snapshot`**（任务当前完整状态 + `lastSequence`，契约 §13.4 要求）。
3. 用**专用调度线程**每 200ms `readAfter(taskId, lastEventId, 100)`，把新事件推给 emitter。
4. 收到 `done` 事件后 `complete()`，连接关闭。
5. 空闲超时（如 5 分钟无新事件）→ `complete()`，由前端按契约「客户端断线后先查询任务状态，
   再决定重连 SSE 或读取最终报告」处理。

**为什么是轮询而不是 `XREAD BLOCK`**：Servlet 容器里每个 SSE 连接占一个线程，
阻塞读会把线程钉死；而契约要求首段 P95 ≤ 5 秒，200ms 的轮询间隔完全够用。
更重要的是——**轮询让「事件到达」变成可注入、可确定性测试的行为**，
而阻塞读只能靠真 Redis + 真时序来测。

**响应头**：`text/event-stream`；`Cache-Control: no-store`（契约 §3.8：SSE 不使用统一 JSON 返回体）。

### 3.8 取消 / 重试 / 追问（AI-06 / AI-07 / AI-08）

| 接口 | 语义要点 |
| --- | --- |
| AI-06 取消 | 置 `cancel_requested=1`；若任务已是终态 → `effectiveImmediately=false` 且 status 不变；否则 worker 在下一个检查点兑现为 `CANCELED` |
| AI-07 重试 | **仅** `FAILED` / `TIMED_OUT` 可重试；创建**新任务**并置 `retry_of_task_id`；**不覆盖旧任务与旧用量**；其他状态 → 409 `AI_TASK_NOT_CANCELABLE`（复用同一族的"状态不允许"语义） |
| AI-08 追问 | 会话必须属于本人且 `status='ACTIVE'`；`question` 必填且不超场景上限；**重新固化上下文**（架构 §8.3 原文：复用必要历史但重新固化最新数据上下文） |

三个接口都要 `Idempotency-Key`（契约原文），复用同一个 `IdempotencyGuard`。

**AI-06 的返回没有"任务对象"**：契约只要求 `taskId` / `status` / `cancelRequested` /
`effectiveImmediately`。不回整个 `AiTaskSummary` 是刻意的——取消是一个**意图**，
回完整任务对象会让前端以为状态已经变了。

### 3.9 最小报告落库

写 `ai_report` 一行（V6 的 `uk_ai_report_task` 保证一项任务最多一个报告）：

| 字段 | 来源 |
| --- | --- |
| 六章节（`core_conclusion` / `quote_evidence` / `comparison_analysis` / `event_clues` / `risk_and_uncertainty` / `disclaimer`） | `LlmCompletion.textOf(section)` |
| `rendered_markdown` | 按 `AiReportSection.inOrder()` 拼接（**渲染顺序只有一处定义**） |
| `is_limited` / `limited_reason` / `quality_status` | `limitations` 非空 → `1` / 拼起来的说明 / `LIMITED`；V6 的 `ck_ai_report_limit_state` 要求三者自洽 |
| `market_data_cutoff_at` | `AiContextBuildResult.dataCutoffs()` 里 `QUOTE` 那一项的截止时间（**NOT NULL**） |
| `news_data_cutoff_at` | 同上，`NEWS` 项；没有资讯时留 `NULL`（不是"用行情时间填充"） |
| `provider_code` / `model_code` / `prompt_version` / `content_schema_version` | 任务创建时固化的配置快照 |
| `content_hash` | 六章节文本的确定性哈希（复用 `AiContentHasher`） |
| `generated_at` | 注入的 `Clock` |

`ai_message`：任务创建时写一条 `USER`（有 `question` 才写），完成时写一条 `ASSISTANT`
（`content` = `rendered_markdown`），`sequence_no` 在会话内连续。
`ai_report.assistant_message_id` 指向后者。

**不在本轮**：`ai_evidence`（M3-08）。所以报告正文里的引用编号 `[1]` 在库里暂时**没有行可反查**。

### 3.10 Web 层

| 接口 | 方法 | 路径 | 状态码 |
| --- | --- | --- | --- |
| AI-03 | `POST` | `/api/v1/ai/tasks` | **202** + `taskId` / `sessionId` / `status` / `statusUrl` / `streamUrl` / `quota` |
| AI-04 | `GET` | `/api/v1/ai/tasks/{taskId}` | 200 `AiTaskSummary` |
| AI-05 | `GET` | `/api/v1/ai/tasks/{taskId}/stream` | `text/event-stream` |
| AI-06 | `POST` | `/api/v1/ai/tasks/{taskId}/cancel` | 200 |
| AI-07 | `POST` | `/api/v1/ai/tasks/{taskId}/retry` | **202** |
| AI-08 | `POST` | `/api/v1/ai/sessions/{sessionId}/follow-up-tasks` | **202** |

全部 `USER` 权限（`anyRequest().authenticated()` 已覆盖，无需改 `SecurityConfiguration` 的
permitAll 列表——但 `SecurityConfigurationTest` 要补一条「未登录访问六个接口都是 401」）。

新增业务码 → HTTP 的映射（`GlobalExceptionHandler`）：

| 业务码 | HTTP |
| --- | --- |
| `AI_TASK_NOT_FOUND` | 404 |
| `AI_TASK_NOT_CANCELABLE` / `AI_TASK_NOT_RETRYABLE` | 409 |
| `AI_QUOTA_EXCEEDED` / `AI_CONCURRENCY_EXCEEDED` | 429 |
| `AI_CORE_DATA_MISSING` / `AI_PROVIDER_UNAVAILABLE` | 503 |
| `AI_TARGET_INVALID` | 400 |

**契约测试**照抄 `MarketControllerContractTest` 的 `mvc()`（独立 `MockMvc` 的 Jackson
不关 `WRITE_DATES_AS_TIMESTAMPS`，时间断言会静默失真）。

### 3.11 装配与配置

`application.yml` 新增（`stock.ai.*` 命名空间已由 M3-06 建立）：

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `daily-task-limit` | 20 | 单用户每日任务上限（契约 §26.4 的 `dailyLimit`） |
| `max-concurrent-tasks` | 2 | 单用户并发上限（契约 §13.5） |
| `task-deadline-seconds` | 60 | 超时阈值（契约 §13.5） |
| `chunk-retention-minutes` | 30 | 事件流保留（契约 §13.4） |
| `prompt-version` | `p1` | M3-06 刻意没加——**现在有消费方了**（写进 `ai_report`） |
| `content-schema-version` | `v1` | 同上 |
| `max-output-tokens` | 2048 | 单次调用输出上限 |

**`@MapperScan` 必须在两个可启动模块上各声明一次**：`StockBackendApplication`（查询侧）
与新的 `StockAiWorkerApplication`（写入侧）。M3-04 已经踩过一次——漏了这一步的
运行期症状是 `No qualifying bean of type '...Mapper' available`，而
`ApplicationContextRunner` 写的配置测试**永远发现不了**（它不走扫描）。
**唯一有效的验证是直接读注解**：断言 `basePackages()` 含 `cn.zhishi.stock.ai`
且 `annotationClass() == Mapper.class`。

### 3.12 真库集成测试查出的三处缺陷（已修）

内存桩是**按对契约的理解手写**的，所以"SQL 里的列名 / 参数名写错""乐观锁版本拿反了"
这一类缺陷在单测里全绿。下面三处都是真库（Testcontainers MySQL 8.4）跑出来的，
每一处都在 `InfrastructureIntegrationTest` 里留下了对应断言：

1. **`ai_task` 表没有 `report_id` 列。** 而列清单与 `UPDATE` 都引用了它，于是所有走真库的
   `ai_task` 路径都报 `Unknown column 'report_id' in 'field list'`。
   修法是**删掉引用**，而不是给 `ai_task` 补一列：V6 把"任务的报告是哪一个"唯一地建模在
   `ai_report.task_id`（`uk_ai_report_task` 保证一项任务最多一个报告）上。
   契约 §4.4 的 `reportId` 改由 `AiReportStore.findByTask(taskId)` 解析——
   `AiTaskService.summaryOf` 是**唯一**投影路径，AI-03 的响应与 AI-04 的查询共用它。
2. **乐观锁的版本方向反了。** `AiTask` 的每个状态推进方法都预先 `version + 1`，
   而 SQL 又拿这个值去 `WHERE version = ?`——匹配的是一个**还不存在**的版本。
   后果不是报错，而是 `save` 永远返回"冲突"：心跳一次都写不进去，
   状态推进偶尔静默丢失（`advance` 看到 0 行就返回 `SKIPPED`）。
   修法是把语义钉成"`AiTask.version()` = 库里那一行的**当前**版本"，
   推进由数据库的 `SET version = version + 1` 负责；`AiTaskStore.save` 改为返回
   **写入后**的聚合（`Optional<AiTask>`），调用方必须换用它继续写。
3. **`AiReportRow` 缺 `limited` 属性。** `AiReportMapper` 的 INSERT 里写着 `#{limited}`，
   于是**每一次写报告都失败**（`There is no getter for property named 'limited'`）。
   补的访问器必须叫 `limited()`：MyBatis 对 record 是**以访问器方法名当属性名**
   （不按普通类的 `get` / `is` 前缀推导），写成 `isLimited()` 会被当成属性 `isLimited`，
   `#{limited}` 依然找不到。

三处的共同点：**它们都不会让任何单测变红**。这正是 §5.4 那条"真实端到端不可省"的理由，
也是本轮把 V6 六张表的持久化往返先写成集成测试、再往上搭 Worker 的原因。

## 4. 一致性约束（写成测试）

1. **状态机白名单**：任意两个状态之间，只有 §3.2 表里列出的迁移被允许；
   终态到任何状态都非法（含"终态到自身"）。
2. **两个入口的校验口径一致**：同一份非法请求，预览与创建报同一个业务码 + 同一条消息。
3. **`request_id` 派生确定**：同一 `(userId, key)` 两次派生结果相同；
   不同 userId + 同一 key 派生结果不同。
4. **幂等回放**：同一 `Idempotency-Key` 重复提交返回同一个 `taskId`，且库里只有一行。
5. **并发上限**：已有 2 个活跃任务时第 3 个返回 429；把其中一个置终态后可创建。
6. **`quota.used` 与库里一致**：返回的 `used` 等于当日该用户 `ai_task` 行数。
7. **状态与 `cancel_requested` 解耦**：取消已完成任务后 `status` 仍是 `COMPLETED`，
   只有 `effectiveImmediately=false`。
8. **重试不覆盖**：重试后原任务的 `status` 不变（若有报告，那份报告也不动），新任务的
   `retry_of_task_id` 指向原任务。
9. **报告与任务一一对应**：`ai_report.task_id` 唯一；报告章节拼接顺序
   与 `AiReportSection.inOrder()` 一致。
10. **引用校验**：`LlmCompletion` 里出现候选集合之外的引用编号 → 任务 `FAILED`
    且 `error_code=AI_OUTPUT_REJECTED`，**不落报告**。
11. **`ai_report` 的受限三字段自洽**：`is_limited=0` ⇔ `quality_status=VALID` ⇔ `limited_reason IS NULL`。
12. **`market_data_cutoff_at` 来自真实批次**：等于上下文里 `QUOTE` 项的 `dataCutoffAt`，
    不是 `now`。
13. **任务的报告只有一处答案**：`ai_task` 没有 `report_id` 列；任务摘要里的 `reportId`
    由 `ai_report.task_id` 解析。若某天有人"顺手"给 `ai_task` 加回这一列，
    这条约束就被破坏了——两处答案分歧不会报错，只会让"任务说已完成、报告查不到"悄悄存在。
14. **乐观锁写入返回推进后的聚合**：`save` 成功时返回的 `version` 比传入的大 1，
    且与库里读回来的值一致。内存桩必须同形实现，否则"拿旧版本继续写"会被掩盖。

## 5. 验证方式

### 5.1 单测（主要）

- 状态机纯函数逐条断言（含全部非法迁移）。
- 编排用例：幂等、并发、配额、取消、重试、追问，桩掉存储与队列。
- SSE 中继：用假 `AiTaskEventStream` 断言事件序列与 `Last-Event-ID` 起点。
- Worker 执行器：桩 `LlmProviderPort`（含四态故障注入）+ 桩存储，
  断言状态推进顺序、`ai_context_snapshot` 行数、报告内容、失败时**不落报告**。
- `@MapperScan` 注解测试（两个可启动模块）。

### 5.2 契约测试（`stock-backend`）

六个接口的状态码、响应字段、错误码；未登录 401；他人任务 404；
SSE 响应头 `text/event-stream`。

### 5.3 构建与回归

`mvn test` + `mvn verify`（CI 用的是 verify）+ `TZ=UTC` 复跑；前端 `npm run typecheck` + 单测。

### 5.4 Docker 可用时的真实端到端

`docker-compose up -d --build` 全栈（多一个 `stock-ai-worker` 容器），脚本
`frontend/e2e/ai-task.real.mjs`：

1. 登录 → `POST /ai/tasks`（带 `Idempotency-Key`）→ 断言 202 + `streamUrl`；
2. 同一 key 重发 → 断言**同一个 `taskId`**；
3. 轮询 `GET /ai/tasks/{id}` → 断言状态确实推进过（不是一步到位）；
4. 连 SSE → 断言收到 `snapshot` / `status` / `chunk` / `report` / `done`，且 `chunk` 的
   `section` 属于六章节；
5. 查库 → 断言 `ai_task` / `ai_task_target` / `ai_context_snapshot` / `ai_message` /
   `ai_report` **五张表都有真实行**（不是夹具）；
6. 并发上限：连续创建 3 个 → 第 3 个 429；
7. 取消语义：取消一个已完成任务 → `effectiveImmediately=false` 且状态不变。

## 6. 改动清单

（实现完成后回填）

## 7. 已知取舍

（实现完成后回填）

## 8. 验收结果

（实现完成后回填）
