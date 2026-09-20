# M3-03 前端 `/watchlist` 接入真实接口

> 任务：`TASKS.md` **M3-03** P0「前端 watchlist 接真实 API」，依赖 M3-01 / M3-02（均已 ✅）。
> 契约：`docs/RESTful-API.md` §12.1（WAT-01~05）、§12.2（WAT-06~12）、§12.3（错误码）。
> 后端现状：M3-02 交付后 **WAT-01~WAT-12 共 12 个接口全部可用**。

---

## 1. 背景

`/watchlist` 是目前**唯一一个既没有真实数据源、又已经有完整后端**的页面。它的现状是：

```vue
const activeGroup = ref('重点观察')
const groups = [{ name: '重点观察', count: 5 }, ...]        // 写死的分组
const watchlist = computed(() => activeGroup.value === '重点观察'
  ? rankingRows : rankingRows.slice(0, 2))                  // 复用 mockApi 的榜单行
```

也就是说：**分组名、分组数量、卡片里的股票**全部来自 `mockApi.rankingRows`（榜单 mock）。
页面上的"5 只标的中 3 只上涨，浦发银行涨幅与成交额同步居首；消费标的表现偏弱"是写死的句子，
而它引用的 `rankingRows` 里根本没有"消费"标的。这与 M2-10 修掉的
「总览页写死『较昨日 +8.69%』、而同一份响应算出来是 +7.25%」是同一类缺陷：
**页面上的数字与它自己引用的数据矛盾，比空白更糟**。

同时页面上有四个"可点但无反应"的元素：`添加股票`、分组栏的 `+`、每张卡片的 `⋯` 与 `移出自选`。
M2-08 定下的规矩是**未接入的操作一律 `disabled` + `title` 写明归属任务**，
本轮它们全部有了真实接口，应当真正可用。

### 1.1 后端接口现状（本轮的数据源）

| 编号 | 方法 | 路径 | 本轮用途 |
| --- | --- | --- | --- |
| WAT-02 | `POST` | `/watchlist-groups` | 新建分组（`Idempotency-Key`） |
| WAT-03 | `PATCH` | `/watchlist-groups/{groupId}` | 重命名（`If-Match`） |
| WAT-04 | `DELETE` | `/watchlist-groups/{groupId}` | 删除（`If-Match`；非空组必带 `moveItemsToGroupId`） |
| WAT-05 | `PUT` | `/watchlist-groups/order` | 分组重排（`groupIds` 必须是本人全部有效分组的排列） |
| WAT-07 | `POST` | `/watchlist-groups/{groupId}/items` | 添加自选（`Idempotency-Key`） |
| WAT-08 | `DELETE` | `/watchlist-groups/{groupId}/items/{itemId}` | 移出自选 |
| WAT-09 | `PATCH` | `/watchlist-groups/{groupId}/items/{itemId}` | 移动到其他分组（`If-Match`，可能 `merged`） |
| WAT-10 | `PUT` | `/watchlist-groups/{groupId}/items/order` | 组内重排（`itemIds` 必须是组内全部项的排列） |
| WAT-11 | `GET` | `/watchlists/overview` | **首屏一次取全**：分组摘要 + 自选行情 + 市场状态 + 数据状态 + `limitations` |

WAT-11 的响应形状（后端 `WatchlistOverviewController.OverviewView`）：

```
groups[]        : groupId, groupName, sortNo, isDefault, itemCount, version   （不带 items）
items[]         : itemId, groupId, security, sortNo, version, createdAt, quote, latestNewsCount
marketStatus    : MarketStatus（MKT-02 的同一形状）
snapshotVersion : string（整批为空时为 ""）
dataStatus      : DataStatus（整批为空时为 UNAVAILABLE）
dataTime        : OffsetDateTime | null
limitations[]   : string[]（人可读的降级说明）
```

`security` 与 `quote` 都可能为 `null`（悬空证券 / 该证券当前无快照），**条目仍然保留**。

### 1.2 可直接复用的既有代码

| 已有 | 复用方式 |
| --- | --- |
| `services/apiClient.ts` | `apiRequest` + `toQueryString`；401 单飞刷新已在里面，页面**不再处理 401** |
| `composables/useRemoteData` | 首屏三态 + 请求序号守卫；页面**不写** `try/catch/finally` |
| `services/securityApi.searchSecurities` | STK-01 搜索建议，用于"添加股票"的选股面板 |
| `utils/format` | `formatChangeRate` / `formatMoney` / `formatDateTime` / `trendClass`（已钉 `Asia/Shanghai`） |
| `components/GlobalSearch.vue` | 只作**写法参照**（300ms 防抖、键盘选择、`highlight` 切片）；不复用组件本身（它绑定全局路由跳转） |

---

## 2. 目标与非目标

### 2.1 目标

1. `/watchlist` 首屏的每一个数字都来自 WAT-11，页面不再引用 `mockApi`。
2. 页面上现有的四个"可点但无反应"元素全部接上真实接口并真正生效。
3. 分组管理（新建 / 重命名 / 删除 / 重排）与自选项管理（添加 / 移出 / 移动到其他分组 / 组内重排）
   全部可用，且**错误来自后端**（`message` + `traceId`）而不是前端猜。
4. 页面上没有数据来源的字段（分时 sparkline、`latestNewsCount`）**不渲染**，
   并把 WAT-11 的 `limitations` 如实展示出来。

### 2.2 非目标（写明归属，不在本轮偷偷扩大范围）

| 不做的事 | 归属 | 原因 |
| --- | --- | --- |
| 消费 WAT-06（分组内自选项分页） | 独立增量 | 首屏用 WAT-11 一次取全即可；WAT-06 的分页只在"单组自选项超过一屏"时才有意义，而自选规模是几十条。**加一个没有调用方的 service 函数是死代码** |
| 消费 WAT-12（`/watchlists/membership`） | 独立增量 | 契约明写它的消费者是**榜单页 / 板块页 / 个股页**（批量回显"已自选"），不是本页。本页自己知道哪些已自选 |
| 分时 sparkline（原型卡片里的折线） | 见已知问题 | 契约里**没有**任何批量 K 线接口：`QuoteSnapshot` 不含序列，STK-07 是单只接口，N 张卡片就是 N 次请求。画一条真线要新增接口，画一条假线就是编造 |
| `latestNewsCount` | M3-04 | 资讯 Provider 未就位，WAT-11 恒返回 `null`（后端已在 `limitations` 里说明） |
| 卡片上的 `AI 解读` | M3-10 | AI 编排未就位，保持 `disabled` + `title` |
| SSE `watchlist` 频道（§20.2） | 见 §7 | WAT-01~WAT-12 都没有要求推送；本轮评估结论是**不需要**，理由见 §7 |
| 自选写操作 60/min 限流（§22.1） | 已知问题 #17 | 全站限流基础设施不存在，单为自选做一套是"一处实现、八处复制" |

---

## 3. 设计

### 3.1 新增 / 修改文件

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `frontend/src/types/domain.ts` | 改 | 新增 `WatchlistGroup` / `WatchlistItem` / `WatchlistOverview` / `WatchlistMembership` 与各请求体类型 |
| `frontend/src/services/watchlistApi.ts` | 新增 | WAT-02/03/04/05/07/08/09/10/11 共 9 个函数；只拼参 + `apiRequest` |
| `frontend/src/pages/WatchlistPage.vue` | 重写 | 首屏接 WAT-11，写操作接其余 8 个接口 |
| `frontend/src/pages/WatchlistPage.test.ts` | 重写 | 打桩 `watchlistApi`，断言"服务端参数拼得对" |
| `frontend/src/pages/WatchlistPage.css` 或 `<style>` | 改 | 侧栏分组菜单、选股面板、降级提示条的样式 |
| `frontend/src/services/mockApi.ts` | 改 | **移除** `rankingRows`（`/watchlist` 是它最后一个消费者） |

### 3.2 首屏：一次 WAT-11，不用 WAT-01 + WAT-06

WAT-11 一次就返回分组摘要 + 自选行情 + 市场状态 + 数据状态，因此首屏只发**一个**请求。
若改用 `WAT-01`（分组）+ `WAT-06`（自选项）+ `MKT-02`（市场状态），就是三个请求且三者
**没有共享的快照版本保证**——市场状态可能来自另一个时刻，而页面会把它们并排展示。
WAT-11 的响应里 `snapshotVersion` / `dataTime` / `dataStatus` 是**整批自选行情**的那一份，
页面据此标注数据截止时间，不存在"两个时刻混在一屏"的可能。

### 3.3 切换分组在**完整响应内**选择，不额外请求

`WAT-11` 有可选的 `groupId`。但本页**不传**它，切换分组是客户端在已拿到的 `items` 上按
`groupId` 选择。这是一个**刻意偏离**"筛选条件一律作为服务端参数"的地方，理由有三条：

1. **响应本来就是完整的**。不传 `groupId` 时 WAT-11 返回本人**全部有效分组的全部自选项**
   （`WatchlistItemService.overview` 在 `groupId == null` 时走 `orderedItems(userId, activeGroups)`），
   而不是"某一页"。所以客户端选择不是"在残缺数据上过滤"——后者才是那条规矩要防的事
   （M2-06 的榜单：服务端已按 `size` 分页，客户端再筛就会让"第 8 名"顶着 08 的排名号）。
2. **传 `groupId` 会导致首屏两次请求且中间闪一屏全量**。首屏时页面还不知道有哪些分组
   （分组清单就来自这次响应），只能先不带 `groupId` 请求一次，拿到默认分组后再带 `groupId`
   请求第二次。中间那一帧会把**所有分组的股票**画出来，然后才收敛到当前组——
   用户看到的是"卡片闪了一下"。
3. **一致性有测试守着**：侧栏的 `itemCount` 与服务端同源（同一快照的同一批 `items`），
   因此"侧栏写 12、列表 3 条"这类矛盾在这里不会发生。§4 把
   「渲染行数 === 该组 `itemCount`」写成断言，而不是靠约定。

WAT-11 的 `groupId` 参数因此**本页不消费**（契约完整性由 `watchlistApi.test.ts` 覆盖）。
若将来出现"单组自选项超过一屏"，正确的做法是改用 WAT-06 分页，而不是给本页加 `groupId`。

### 3.4 写操作成功后**重新拉取**，不做本地乐观更新

九个写接口里有六个会改动服务端状态，其中两个的本地推断**必然出错**：

- `WAT-09` 在目标组已有同证券时会**合并**：源行被删、目标行 `version` 不变（M3-02 决策）。
  前端从响应只能拿到幸存行，无从知道源行的去向。
- `WAT-10` / `WAT-05` 会**重写整组序号并给每一行 +1 版本**：本地只知道自己传了什么，
  不知道服务端最终落成的序号。

因此统一策略：**写成功 → 重新请求 WAT-11**。代价是多一次请求，收益是"页面显示的一定是服务端状态"。
不做本地乐观更新，也不做"局部 patch 缓存"——那需要在前端重建一份 `sortNo` / `version` 的推导规则，
而这份规则**只会在服务端改动时静默过期**（M2-10 的教训：口径分歧不报错，只让数字互相矛盾）。

### 3.5 幂等键与乐观锁

- **`Idempotency-Key`**（WAT-02 / WAT-07）：每次**用户意图**生成一个新的 `crypto.randomUUID()`，
  并在该次意图的重试中**复用同一个键**。组件里保存 `pendingIntent`（含 key 与 body），
  失败重试时若 body 未变就复用，body 变了就换新键——否则会撞上后端
  `IDEMPOTENCY_KEY_CONFLICT`（同一键不同体 → 409）。
- **`If-Match`**（WAT-03 / WAT-04 / WAT-09）：值取**响应里该行的 `version`**，
  不取任何本地计数。版本冲突（409 `WATCHLIST_VERSION_CONFLICT`）时提示
  "数据已变化，已刷新"并重新拉取——这是并发下的正确行为，不是错误。

### 3.6 分组重排与组内重排

两个接口都要求提交**完整排列**：

- `WAT-05`：`groupIds` 必须是本人全部有效分组的排列（缺项 / 重复 / 含他人分组 → 400）。
  因此从 `overview.groups`（已按 `sortNo` 升序）的全量顺序推导新顺序，**不只看侧栏渲染了什么**。
- `WAT-10`：`itemIds` 必须是组内全部当前项的排列（集合不匹配 → 409）。

排序交互用 **HTML5 拖放**（原型页脚已承诺"拖动可调整分组顺序"，不实现它就是假交互）。
被拖动的下标存在**组件状态**里（`dragIndex`），不依赖 `dataTransfer`：
`dataTransfer` 在 jsdom 里不存在，靠它传递数据会让排序**无法被测试**，
而"无法被测试的交互"正是最容易静默坏掉的那一类。

### 3.7 选股面板：显式提交，不是输入即搜

"添加股票"的面板用**输入 + 提交（Enter / 按钮）**触发 STK-01，不做 300ms 防抖的输入即搜。

与顶栏 `GlobalSearch.vue` 的差别是刻意的：顶栏是"我要去某个页面"的随手检索，
输入即搜更顺手；本面板是"我要往这个分组里加一只股票"的**明确动作**，
用户会先想清楚加哪只。输入即搜的代价是打 6 个字符发 6 次请求，
并且把组件绑死在定时器状态上（测试必须引入假定时器，而假定时器与 `flushPromises` 的
交互是本项目已经踩过的坑）。显式提交同时让"空输入"可以被明确拒绝，而不是静默不发请求。

### 3.8 降级与"不编造"

| 情况 | 展示 |
| --- | --- |
| `items[].security === null` | 卡片保留，名称位置显示"证券主数据缺失"，**不可点击跳转** |
| `items[].quote === null` | 价格区显示 `--`（`formatChangeRate(null)` / `formatMoney(null)` 已返回 `--`） |
| `dataStatus !== 'REALTIME'` **且当前分组有卡片** | 页面级提示条："当前展示最近有效快照（数据截止 …）" |
| `dataStatus === 'UNAVAILABLE'`（自选为空，后端不发起整批取数） | **不提示时效**。没有快照却说"最近有效快照（数据截止 --）"就是编造（§8.4 实测） |
| `snapshotVersion` | **不渲染**。数据截止时间已表达新鲜度；本页所有卡片来自同一次响应，不存在"两个批次混排"，版本号对用户没有增量信息 |
| `limitations[]` 非空 | 页面级提示条逐条展示（资讯域未就位时必然非空） |
| `latestNewsCount` | **不渲染**。契约里它是 `null`（M3-04 才有值），渲染 `0` 就是编造 |
| 分时 sparkline | **移除**。见 §2.2 |

**导语由真实数据算出**（不再写死）：上涨 / 下跌 / 平盘只数取自当前 `items` 的 `quote.changeRate`，
涨幅居首与成交额居首从同一批 `items` 里取。停牌单独计数，且**不计入涨跌分母**——
否则"5 只中 3 只上涨"在含停牌时会失真。

停牌的判据是 `security.isSuspended`，**不是**"`quote` 为空"：实测 `sim-300750` 就是
`isSuspended: true` 而 `quote` **非空**、`changeRate` 为 `"0.0000"`（最新价等于昨收）。
只按数值判断会把它算成"平盘"——数字看起来对，结论其实错（§8.4）。因此
「有可用于涨跌统计的行情」= `quote !== null && security?.isSuspended !== true`，
三类（停牌 / 缺快照 / 快照里没有涨跌幅）合并成"无有效行情"一档单独说明。
涨幅居首与成交额居首同样只在有有效行情的标的里选：停牌股的成交额来自上一个交易日。

### 3.9 状态管理与请求时序

- 首屏 `useRemoteData(() => getWatchlistOverview(groupId))`；`queryKey = groupId` 驱动 `reload`，
  用**一个** `computed` 键而不是给每个条件各挂一个 `watch`（M2-06 的既有做法）。
- 写操作自己持有 `mutating` 标志（`ref<boolean>`）与 `actionError`（`ref<ApiError | undefined>`），
  **不复用** `useRemoteData` 的 `loading`：写操作与读请求的失败语义不同
  （读失败整页替换成错误页，写失败只提示、页面数据保留）。
- 写操作进行中禁用相关按钮，避免连点产生两个不同幂等键的等价请求。

---

## 4. 一致性约束（逐条写成测试）

| 约束 | 为什么 |
| --- | --- |
| 首屏只发一次 WAT-11（不调 WAT-01 / WAT-06 / MKT-02） | 防止"两个时刻混在一屏" |
| 切换分组**不**产生新请求，且渲染行数 === 该组 `itemCount` | 服务端 `itemCount` 与列表条数必须自洽（原型现在写着 5 却渲染 2 条） |
| 添加自选：请求体是 `{securityId}`、带非空 `Idempotency-Key`、成功后 WAT-11 被再次调用 | 幂等键缺失后端会 400 |
| 移出自选：路径含 `groupId` 与 `itemId`、成功后重新拉取 | |
| 移动到其他分组：带 `If-Match` 且值等于**该行的 `version`** | 用错版本必然 409 |
| 组内重排：`itemIds` 的顺序等于拖拽后的顺序、且是**组内全部项**的排列 | 集合不匹配后端 409 |
| 分组重排：`groupIds` 是 `overview.groups` 全部 id 的新排列 | 缺项 / 重复后端 400 |
| 重命名 / 删除分组：带 `If-Match` 且值等于该组 `version` | |
| 删除非空分组：带 `moveItemsToGroupId`，且该值不等于被删分组自身 | 否则后端 400 / 409 |
| 页面**不渲染** `.watch-sparkline` | 没有数据源，画出来就是编造 |
| 页面**不出现** `latestNewsCount` 的任何数字形态 | 同上 |
| 导语的涨 / 跌 / 平只数与 `items` 的 `changeRate` 一致（停牌不计入分母） | 页面数字必须与它引用的数据自洽 |
| `limitations` 逐条渲染 | 后端给降级说明就是给用户看的 |
| `AI 解读` 按钮 `disabled` 且 `title` 含 `M3-10` | 未接入的功能不能看起来可用 |
| 写操作失败时页面数据**保留**，只显示后端 `message` + `traceId` | 一次写失败不该把整页数据清空 |

---

## 5. 验证方式

- `npm run typecheck`（**不是** `npx vue-tsc --noEmit`，后者检查 0 个文件、永远返回 0）
- `npx vitest --configLoader runner --run`，并带 `TZ=UTC` 再跑一遍
  （页面渲染北京时间，`formatDateTime` 已钉 `Asia/Shanghai`，必须证明与运行环境时区解耦）
- `npx vite build --configLoader runner`
- 后端不动，但复跑一次确认没有意外影响

测试清单：

| 文件 | 覆盖 |
| --- | --- |
| `WatchlistPage.test.ts` | §4 的全部约束 + 三态（加载 / 错误页 / 正常）+ 写操作失败保留数据 |
| `watchlistApi.test.ts` | 9 个端点的 method / 路径 / `Idempotency-Key` / `If-Match` / body 形状 |

**另外做了一次真实端到端联调**（本机 Docker 可用，2026-09-20）：空库跑 Flyway V1→V8，
起 `stock-backend`（dev profile，`DevelopmentAccountSeeder` 播种 `demo`），用 `curl` 按
**前端将要发出的完全相同的请求形状**走完 WAT-01/02/03/04/07/09/10/11，逐字段核对响应。
这一步不是可选项——它查出了两个单测发现不了的问题（§8.4），因为单测的夹具是我按对契约的
理解手写的，而真实数据里有我没预料到的组合（停牌股带快照、空自选返回 `UNAVAILABLE`）。

---

## 6. 改动清单

- 新增：`services/watchlistApi.ts`、`services/watchlistApi.test.ts`、spec 本文档
- 修改：`types/domain.ts`、`pages/WatchlistPage.vue`、`pages/WatchlistPage.test.ts`、
  `styles/business.css`（分组菜单 / 卡片操作 / 提示条；删掉 `.watch-sparkline`）
- 修改：`services/mockApi.ts` + `services/mockApi.test.ts`——移除 `rankingRows`（`/watchlist`
  是它最后一个消费者）。顺带移除同文件里已无消费者的 `getMarketOverview()`：它的最后一个
  调用方是 M2-08 把总览页接上 MKT-01 时消失的，此后只剩自己的测试在读。留着会让后来者
  以为总览页还在读它。**文件现在只剩 `getNews`，归 M3-05 整体删除。**
- 文档：`TASKS.md`、`PROJECT_STATUS.md`、`CHANGELOG.md`

---

## 7. 已知取舍

| 取舍 | 代价 | 为什么仍然这样做 |
| --- | --- | --- |
| 写操作后重新拉取整个 WAT-11 | 每次写多一次网络往返，列表会闪一下 `loading` | 本地推断 `sortNo` / `version` / 合并结果必然在服务端改动时静默出错（§3.4）。用一次请求换"显示的一定是真值" |
| 切换分组不传 `groupId`、在完整响应内选择 | 自选项总量很大时首屏响应偏大；偏离"筛选一律走服务端"的既有规矩 | 传 `groupId` 需要首屏两次请求且中间闪一屏全量（§3.3）。响应本身是完整的，一致性由测试守着 |
| 首屏不做分页 | 自选项很多时首屏响应变大 | 自选规模是几十条；引入 WAT-06 分页要额外维护"当前页"状态，且 WAT-11 本来就不分页 |
| 拖放不用 `dataTransfer` | 无法与其它页面/应用互拖 | 用组件状态传下标让排序**可被测试**；跨应用拖入自选不在需求内 |
| 不消费 WAT-12 | 榜单 / 板块 / 个股页暂时不显示"已自选" | 它的消费者不是本页；塞进本页只会多一个没有调用方的函数 |
| 移除卡片 sparkline | 卡片视觉变朴素 | 契约没有批量 K 线接口，画一条真线要新增接口、画假线是编造。**留空比编造安全** |
| SSE `watchlist` 频道不做 | 多端同时改自选时本页不会自动刷新 | 契约 §20.2 的推送频道对 WAT-01~12 都不是必需；当前每次写操作后本页自己重新拉取，多端一致性属于独立增量。**本轮评估结论：不需要**，记入已知问题 |
| 错误只展示后端 `message` | 文案不如前端定制友好 | 后端的 `message` 已经是中文可读文案（如"分组名去除首尾空白后需为 1~20 个字符"），且带 `traceId` 可对日志；前端另写一套只会与服务端规则分叉 |
| 不渲染 `snapshotVersion` | 排查"这是哪一批数据"时少一个线索 | 数据截止时间已表达新鲜度，且本页所有卡片来自同一次响应，不存在批次混排；版本号是开发者向的信息，放页面上是噪音。真正需要关联时用 `traceId`（错误态已展示） |
| 页面不展示 `marketStatus` | WAT-11 返回的这个字段在本页没有被使用 | 顶栏 `useMarketStatus`（MKT-02 + 60s 轮询 + 页面不可见时暂停）已经全局展示"休市 / 交易中"，页面里再放一份是重复信息。字段仍保留在类型里——它是响应的一部分，不是每个字段都必须被渲染 |

---

## 8. 验收结果

### 8.1 测试数与验证

| 项 | 结果 |
| --- | --- |
| `npm run typecheck` | 通过（首跑报 `'Search' is declared but its value is never read`，删掉遗留 import 后通过） |
| `npx vitest --configLoader runner --run` | **133 passed / 20 files**（M3-03 前是 101，净增 32） |
| `TZ=UTC` 复跑 | 133 passed，与本地一致 |
| `npx vite build --configLoader runner` | 通过（`WatchlistPage` chunk 14.11 kB / gzip 5.14 kB） |
| 后端 | 未改动；e2e 期间用 `-DskipTests package` 出的 jar 起服务，未触碰主代码 |

净增 32 的构成：`watchlistApi.test.ts` 9 个 + `WatchlistPage.test.ts` 25 个
（原 19 个用例重写，其中 2 个断言写错已改、另新增 6 个）− `mockApi.test.ts` 删除 2 个。

### 8.2 红灯记录（真实红灯，不是编译错误）

`WatchlistPage.test.ts` 首跑 **18 failed / 1 passed**——页面当时还是旧实现（读 `mockApi` 的榜单行、
分组写死），失败形态是 `Cannot read properties of undefined (reading 'trigger')` 与
`expected ... to contain '暂时无法连接服务'`。写完新实现后 22 passed；
再补 3 个用例（空自选不提示时效、非实时提示条、停牌单独计数）后 25 passed。

### 8.3 契约字段核对（逐项）

`WatchlistGroup` **不带** `items`——`GroupView` 上有 `@JsonInclude(NON_NULL)`，WAT-11 的
`groups[]` 走单参 `from(group)`（`items = null`）因此该键**根本不出现**。类型里不声明它，
否则调用方会写出 `group.items?.length ?? group.itemCount` 而两处口径分叉。
其余逐项对齐：`OverviewView` 的字段名是 `items`（不是 `WatchlistOverview` record 里的 `entries`）、
`CreatedGroupView` 无 `itemCount`、`DeleteView = {deleted, movedItemCount}`、
`MovedItemView` 带 `merged`、`ItemOrderView = {itemId, groupId, sortNo, version}`。
写请求体的 ID 是**字符串**（`ReorderItemsRequest(List<Long>)` 等靠 Jackson 标量强制转换接受
`"7001"`），路径与 query 参数同样用字符串——e2e 实测全部 200。

### 8.4 e2e 查出的两个问题（单测发现不了）

1. **空自选时提示条在编造一次不存在的快照。** 后端在 `stored.isEmpty()` 时**不发起整批取数**，
   于是批次为 `null` → `dataStatus = 'UNAVAILABLE'`、`snapshotVersion = ''`、`dataTime = null`
   （`WatchlistItemService.overview`）。旧逻辑 `dataStatus !== 'REALTIME'` 为真，于是空列表上挂着
   "当前展示最近有效快照（数据截止 --）"。**新注册用户的第一屏就是这个**。
   修法：提示条只在"当前分组真的有卡片"时才出现（有卡片 ⟹ 批次存在 ⟹ `dataTime` 非空）。
2. **停牌股被算成"平盘"。** 真实数据里 `sim-300750` 是 `isSuspended: true` 但 `quote` 非空、
   `changeRate = "0.0000"`。按数值算它落进"平盘"，而它今天根本没有价格发现。
   修法：判据改为 `security.isSuspended`（§3.8），并把"快照里没有涨跌幅"一并归入
   "无有效行情"一档，使 `共 N = 上涨 + 下跌 + 平盘 + 无有效行情` 恒成立。

两个问题的共同点：**单测夹具是我按对契约的理解手写的，而真实数据里有我没预料到的组合。**
这条已经写进 skill。

### 8.5 e2e 顺带确认的行为（与设计一致，无需改动）

- WAT-02 用同一 `Idempotency-Key` 重放 → 返回**同一条** `itemId`（幂等回放生效）。
- WAT-10 重排后 `version` 从 0 变 1——印证 §3.4"写后重拉、不本地推断版本"是对的。
- WAT-09 移动后 `version` 1→2、`merged: false`。
- **WAT-09 用过期 `version` 返回 404 而不是 409**：第一次移动已把源行删掉，按
  `(user, group, itemId)` 查不到行。前端的通用错误路径照常显示 `message` + `traceId`，
  不需要为 404/409 分别处理——但这条值得记住：**"版本不对"不一定表现为 409**。
- WAT-04 非空分组不给目标 → 400 `WATCHLIST_TARGET_GROUP_REQUIRED`（页面本地先拦，文案与后端一致）；
  带目标 → `{deleted: true, movedItemCount: 1}`；删默认分组 → 409 `DEFAULT_GROUP_CANNOT_DELETE`
  （对应页面把"删除"置灰）。
- 各组 `itemCount` 与列表实际条数逐组相等（§4 的核心约束在真实数据上成立）。

### 8.6 遗留问题

- 新增已知问题 #19：`dataStatus === 'REALTIME'` 但 `dataTime` 是上一个交易日
  （`sim-2026-09-18T15:00:00+08:00`，而当天是 2026-09-20 周日）。模拟 Provider 把"最近一个
  已完成交易日"当作实时批次，因此页面头部会显示"数据截止 09/18 15:00"而没有任何时效提示条。
  真实行情源接入后此语义自然修正；在那之前**不要**在前端按"日期不是今天"自行降级——
  那会与后端的 `dataStatus` 打架，且周末必然误报。
- WAT-12（`/watchlists/membership`）仍未消费，见 §7。
