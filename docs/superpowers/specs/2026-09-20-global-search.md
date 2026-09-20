# M2-09 全局搜索接真实接口 — 设计

> 任务：`TASKS.md` M2-09（P1），依赖 M2-04 ✅
> 接口：`RESTful-API.md` §8 STK-01 `GET /securities/search`
> 需求：PRD §7.3 QTE-01「全局股票搜索」
> 路线图：`docs/superpowers/plans/2026-09-19-mvp-delivery-roadmap.md` M2-09（文件 `components/GlobalSearch.vue`，验收「建议项可跳转真实个股页」）

## 1. 背景

`components/GlobalSearch.vue` 目前**没有发出过任何请求**：

| 现状 | 问题 |
| --- | --- |
| 3 条写死的建议（浦发银行 / 宁德时代 / 中芯国际） | 与证券主数据无关，是原型残留 |
| 每条固定显示 `+2.74%` | 编造的涨跌幅 |
| 点击任一条都 `router.push('/stocks/19876543210001')` | 该 ID 在主数据里**不存在** → 个股详情页 404。这是首页最显眼的入口，点谁都坏 |
| 无输入监听、无请求 | 输入框只是个装饰 |
| `<kbd>⌘ K</kbd>` | 纯装饰，没有任何快捷键监听 |
| 占位符「搜索股票、代码或板块」 | STK-01 **只搜证券**，不搜板块 |

后端 STK-01 早已就绪（M2-04），`domain.ts` 的 `SecuritySearchResult` / `SecuritySearchMatch` / `SecuritySummary` 类型也已补齐，缺的只是把两者接起来。

## 2. 目标

- 输入即查 `GET /securities/search`，**300ms 防抖**
- 结果项可点击跳转到 `/stocks/{securityId}`，`securityId` 来自响应而非写死
- **键盘可选**：`↑` / `↓` 移动、`Enter` 选中、`Esc` 关闭
- 命中片段用 `highlight` 原文高亮
- 停牌 / 退市 / ST 状态必须**标记**（PRD QTE-01 明文要求）
- 失败时**保留关键词**并提供重试（PRD QTE-01 明文要求）
- 输入为空时不发请求（契约要求 `q` 长度 1–50，空串必然 400）
- `⌘K` / `Ctrl+K` 真的能聚焦输入框，否则那个 `<kbd>` 是假的

## 3. 不在范围

| 项 | 归属 | 理由 |
| --- | --- | --- |
| 搜索建议里的**涨跌幅** | 见 §6.1 | 需要 `STK-05 POST /quotes/securities/batch-query`，**该接口后端未实现且 `TASKS.md` 未排期** |
| 板块搜索 | 后续 | STK-01 只搜证券；SEC-01 无 `keyword` 之外的搜索语义，且板块只有 39 个 |
| 热门 / 最近搜索建议 | 后续 | 无数据来源（没有"热搜"接口）；本地存最近搜索是另一个功能 |
| 搜索结果独立页 / 全量结果 | 后续 | STK-01 是**搜索建议**（`limit` 上限 20），不是结果页；STK-02 列表才是"管理型选择组件"的入口 |
| 拼音搜索可见性 | — | 主数据不填拼音（M2-04 决策），能力由后端单测用桩数据覆盖 |

## 4. 接口语义（照抄 M2-04 实现，不重新定义）

| 项 | 值 |
| --- | --- |
| 路径 | `GET /api/v1/securities/search`（PUBLIC） |
| 参数 | `q`（必填，裁剪后 1–50 字符）、`limit`（1–20，默认 10） |
| 返回 | `data.items[]{ security: SecuritySummary, matchedField, highlight }` |
| 排序 | 后端已按「`matchedField` 优先级 → `fullSymbol` 升序」排好，前端**不得重排** |
| `highlight` | 命中字段中的**原文子串**，不含任何标记 → 前端负责切分并包裹 |
| 错误 | `q` 长度非法 → 400 `INVALID_REQUEST`；筛选值不存在 → 200 + 空 `items` |

`SecuritySummary` 的 11 个字段里与本组件相关的：`securityId`（跳转用）、`securityCode` / `exchangeCode` / `fullSymbol`（展示）、`isSuspended` / `listingStatus` / `isSt`（状态标记）。

## 5. 组件设计

### 5.1 状态

```ts
keyword     // 输入原文
trimmed     // keyword.trim()，唯一驱动请求的值
open        // 面板是否展开（聚焦时开，Esc / 选中 / 点外部关）
activeIndex // 键盘高亮项，-1 表示未选中
```

请求三态复用 `composables/useRemoteData`（`data` / `loading` / `error` / `reload`）。
**它内部的请求序号守卫在这里是必需的**：防抖只降低并发概率，不消除——300ms 后发出请求 A，用户在 A 返回前继续输入又发出 B，若 A 比 B 慢，没有守卫时列表会回退成 A 的结果。

### 5.2 面板内容优先级

| 顺序 | 条件 | 展示 |
| --- | --- | --- |
| 1 | `error` | 失败文案 + `traceId` + 「重试」按钮（`keyword` 原样保留在输入框） |
| 2 | `!trimmed` | 提示「输入证券代码或名称开始搜索」 |
| 3 | `loading` 且无结果 | 「搜索中…」 |
| 4 | 有结果 | 结果列表 |
| 5 | 其余 | 「没有匹配「xxx」的证券」 |

`trimmed` 为空时**结果列表必须隐藏**：用户清空输入后不该继续看到上一次的结果，而 `useRemoteData` 不会主动清 `data`。

### 5.3 键盘

| 键 | 行为 |
| --- | --- |
| `↑` / `↓` | `activeIndex` 在 `[-1, items.length)` 之间环绕；面板未开时先打开 |
| `Enter` | 有 `activeIndex` 则选中它；否则选中第 1 条（无结果时不做任何事） |
| `Esc` | 关闭面板，**不清空关键词**（用户按 Esc 通常是想收起面板再看一眼输入） |
| `⌘K` / `Ctrl+K` | 聚焦输入框（`preventDefault`，避免浏览器默认行为） |

### 5.4 高亮切分

`highlight` 是命中字段的原文子串，但**没有说是哪个字段**（`matchedField` 只说类别）。
因此不按 `matchedField` 分支，而是对**代码与名称各试一次**：谁包含这个子串就切谁。
这样 `matchedField=CODE` 与 `NAME` 两种情况共用一条逻辑，也不会因为将来后端改了
`matchedField` 的取值而把高亮打到错误的字段上。

### 5.5 状态标记

`statusLabel(security)` 的优先级：`isSuspended` → `listingStatus` → `isSt`。
停牌要排在 ST 前面：一只停牌的 ST 股，用户更需要知道它停牌了。

## 6. 关键设计决策

### 6.1 不展示涨跌幅（PRD 要求了，但拿不到）

PRD QTE-01 的输出列写的是「最多 10 条匹配股票，含代码、名称、交易所、**状态和涨跌幅**」。
但 STK-01 的响应里**没有任何价格字段**——`SecuritySummary` 是主数据，`SecuritySearchMatch` 只有
`matchedField` 与 `highlight`。

拿到涨跌幅有两条路：

1. **对每条建议各调一次 STK-04** `/securities/{id}/quote`：10 条建议 = 11 次请求，
   与 PRD「输入后 500 毫秒内出现结果」直接冲突（每次请求都要走完整链路）。
2. **调一次 STK-05** `POST /quotes/securities/batch-query`（契约明确它的用途就是
   「批量查询自选、板块成分股等首屏行情」）：1 次请求拿回全部 10 条行情。

两条路都要求 STK-05 存在，而**STK-05 后端未实现**（`grep batch-query backend/ --include=*.java` 零命中），
`TASKS.md` 的 M2 任务清单里也没有它。

因此本轮**不展示涨跌幅**，与 M2-08 的处理一致：契约没有字段就降级，不保留"看起来还行"的占位数字。
建议后续补一个小任务实现 STK-05，一次请求即可补齐（M3-03 自选页的首屏行情也需要它）。

### 6.2 改占位符，而不是保留一句做不到的承诺

原占位符是「搜索股票、代码或板块」。STK-01 只搜证券，用户输入「银行」得到的是名字里含"银行"的
**股票**，不是板块。改为「输入代码或名称搜索证券」。

### 6.3 空输入不发请求

契约要求 `q` 裁剪后 1–50 字符，`"  "` 会被裁成空串并返回 400。
前端必须先在 `trimmed` 上判断，否则用户每敲一个空格都会产生一条 400 与一条错误日志。

### 6.4 `maxlength` 取 50 而不是 PRD 的 20

PRD 的输入列写「1-20 字符」，契约上限是 50。取 50：
- 取 20 会在用户**粘贴**长文本时静默截断输入，被截掉的部分用户看不见；
- 取 50 既不会送必然 400 的请求，也不修改用户的输入。

### 6.5 点外部关闭用 document 监听，不用 blur + 定时器

原型用 `@blur` + `setTimeout(120ms)` 再配合 `@mousedown.prevent` 来"让点击先于失焦生效"。
这是个靠时间窗赌顺序的写法：机器卡顿时 120ms 不够，点击就会丢。
改为在 document 上监听 `mousedown` 判断点击是否落在组件外，并在 `onBeforeUnmount` 里移除——
确定性行为，不依赖时序。

## 7. 改动清单

| 文件 | 说明 |
| --- | --- |
| `services/securityApi.ts` | 新增 `searchSecurities(q, limit)` |
| `components/GlobalSearch.vue` | 重写：真实请求 + 防抖 + 键盘 + 高亮 + 状态标记 + 失败重试 |
| `styles/shell.css` | 新增 `.is-active` 键盘高亮、`.global-search__status` 状态行、`mark` 高亮、`.global-search__badge` 状态徽标 |
| `components/GlobalSearch.test.ts` | 新增，13 项 |

### 7.1 实现时才补上的细节

- **`focused` 与 `open` 拆成两个状态**。原计划只用一个 `open`：但"点击组件外部"会让面板收起而输入框
  仍然握着光标，此时若用 `open` 控制聚焦样式，输入框会在有光标时看起来是失焦的。
  因此 `focused` 只管视觉（`@focus` / `@blur`），`open` 只管面板与 `aria-expanded`。
  两者互不干涉，也都不依赖定时器。
- **补 `role="combobox"` + `aria-activedescendant`**。焦点始终在输入框上（选项只是被"高亮"），
  因此读屏软件无法从 DOM 焦点得知当前选中哪一项，必须靠 `aria-activedescendant` 指向选项的 `id`。
  选项同时标 `role="option"` + `aria-selected`。
- **`<kbd>` 按平台显示 `⌘ K` 或 `Ctrl K`**，不再固定显示 Mac 的写法。

## 8. 验收方式

1. `npx vue-tsc --noEmit` 零错误
2. `npx vitest --configLoader runner --run` 全绿（实测 **18 文件 / 75 项**，其中本组件 13 项）
3. `npx vite build` 成功
4. 单测覆盖：
   - 输入 300ms 后才发请求，且 `q` 为裁剪后的值
   - 连续输入只在最后一次之后发一次请求
   - 空输入 / 纯空白不发请求
   - 结果项渲染名称、代码、交易所；停牌有标记，正常交易的证券不被误标
   - `highlight` 原文子串被标成 `mark`，其余原文保持原样
   - 点击建议跳转 `/stocks/<响应里的 securityId>`（**不是写死的 ID**）
   - `Enter` 选中第一条；`↓` 两次后 `Enter` 选中第二条，且 `aria-activedescendant` 跟着移动
   - `Esc` 收起面板但保留关键词
   - 失败时展示后端文案与 `traceId`，关键词仍在输入框，点「重试」重新请求
   - 无结果显示空状态文案
   - `⌘K` / `Ctrl+K` 聚焦输入框
   - 点击组件外部关闭面板
5. 手工核对（需后端在跑）：输入 `600000` 与 `浦发` 都能出现结果，点击后个股详情页有数据
   —— **本机未做**：Docker 守护进程未启动，MySQL/Redis 起不来，Spring Boot 无法启动。
   替代验证是后端 `SecurityControllerContractTest` 已断言 STK-01 的 JSON 路径
   （`$.data.items[].security.securityId`、`matchedField`、`highlight`）。

## 9. 已知取舍

| # | 取舍 | 理由与代价 |
| --- | --- | --- |
| 1 | 结果项不显示涨跌幅 | STK-01 无价格字段，STK-05 未实现（§6.1）。代价：与 PRD QTE-01 的输出列不完全一致，需补 STK-05 |
| 2 | 不搜板块 | STK-01 只搜证券；代价是用户输入板块名得不到板块结果，需走 `/sectors` 页 |
| 3 | 无热门 / 最近搜索建议 | 无数据来源，编造"热搜"比留空更糟；代价是空输入时只有一句提示 |
| 4 | 结果条数固定 10（契约默认值） | 契约上限 20，但 PRD 要求「最多 10 条」；代价是用户无法在建议里看到更多，需走列表页 |
| 5 | `maxlength` 取契约上限 50 而非 PRD 的 20 | 避免粘贴时静默截断（§6.4）；代价是允许输入 21–50 字符 |
| 6 | `Esc` 不清空关键词 | 用户按 Esc 通常是想收起面板；代价是与部分搜索框的习惯不同 |
| 7 | 复用 `useRemoteData` 而不是在组件里手写请求 | 直接获得请求序号守卫；代价是组件依赖一个 composable，且它不做缓存（每次输入都真打后端） |
