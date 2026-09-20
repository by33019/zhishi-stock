# M3-02 自选项 CRUD + 排序 + 行情概览（WAT-06~WAT-12）

- 日期：2026-09-20
- 依赖：M3-01（WAT-01~WAT-05）已完成
- 契约：`docs/RESTful-API.md` §12.2 WAT-06~WAT-12、§12.3 业务规则与异常、§3.7 幂等与并发
- 关闭已知问题：**#15**（WAT-01 的 `includeItems=true` 本轮显式 400）

---

## 1. 背景

M3-01 让 `user_watchlist_group` 第一次被代码引用，但 `user_watchlist_item` 仍然是"表结构就绪、零代码引用"：

```
$ grep -rn "user_watchlist_item" backend --include=*.java | grep -v target
backend/stock-system/.../WatchlistGroupMapper.java   # 只有"整组搬迁/合并"两条语句，服务分组删除
```

自选项本身的增删改排序、首屏行情概览、以及"已自选"状态回显都还没有。本轮补完 §12.2 的七个接口。

### 1.1 契约摘要（§12.2）

| 编号 | 方法 | URL | 关键入参 | 关键出参 |
| --- | --- | --- | --- | --- |
| WAT-06 | GET | `/watchlist-groups/{groupId}/items` | `includeQuote`、分页 | 分页：`itemId`、`groupId`、`security`、`sortNo`、`version`、`createdAt`、可选 `quote`、`latestNewsCount` |
| WAT-07 | POST | `/watchlist-groups/{groupId}/items` | `Idempotency-Key`；`securityId` | `itemId`、`groupId`、`security`、`sortNo`、`version`、`createdAt` |
| WAT-08 | DELETE | `/watchlist-groups/{groupId}/items/{itemId}` | 路径 | `deleted` |
| WAT-09 | PATCH | `/watchlist-groups/{groupId}/items/{itemId}` | `If-Match`；`targetGroupId` | `itemId`、`groupId`、`sortNo`、`version`（合并时 `merged=true`） |
| WAT-10 | PUT | `/watchlist-groups/{groupId}/items/order` | `itemIds`（全量、不重复） | 更新后的项顺序与版本 |
| WAT-11 | GET | `/watchlists/overview` | `groupId`、`newsSince` | 分组摘要、自选行情数组、市场状态、`snapshotVersion`、数据状态 |
| WAT-12 | GET | `/watchlists/membership` | `securityIds`（1~50） | Map：`securityId` → `groupId`、`groupName`、`itemId` |

### 1.2 可直接复用的既有代码

| 能力 | 位置 | 复用方式 |
| --- | --- | --- |
| 幂等键 | `stock-system/idempotency/IdempotencyGuard` | WAT-07 显式调用，`scope = watchlist-item:add` |
| `If-Match` 解析 | `stock-backend/web/IfMatch` | WAT-09 直接调用（包内可见） |
| 错误码 → HTTP | `WatchlistErrorCode` + `GlobalExceptionHandler` | 全部沿用，**不新增错误码** |
| 分组归属校验 | `WatchlistGroupRepository.findActive(userId, groupId)` | 所有 `{groupId}` 路径参数 |
| 整批快照 | `QuoteSnapshotBatchProvider` + `QuoteBatch` | WAT-06 `includeQuote=true`、WAT-11 |
| 市场状态 | `MarketStatusQueryService` | WAT-11，在**组合根**（controller）注入 |
| 分页外壳 | `stock-common` 的 `PageData.slice` | WAT-06 |

---

## 2. 目标与非目标

### 2.1 目标

1. WAT-06~WAT-12 七个接口落地，字段集与契约逐字对齐。
2. 关闭已知问题 #15：WAT-01 的 `includeItems=true` 返回真实自选项。
3. `security` 投影（`SecuritySummary`）在自选链路里只有**一处**实现。
4. 补完 `user_watchlist_item` 的真实 MySQL 集成测试。

### 2.2 非目标

| 不做 | 归属 |
| --- | --- |
| 每只证券的最新资讯数（`latestNewsCount` 恒 `null`） | M3-04 资讯 Provider |
| `newsSince` 过滤（显式 400） | M3-04 |
| 前端 `/watchlist` 页面接入 | M3-03 |
| SSE `watchlist` 频道（§20.2） | 未排期 |
| 自选价格 / 资讯通知 | PRD 明确 MVP 不做 |

---

## 3. 设计

### 3.1 分层与新增文件

```
stock-market/domain/            SecurityIdentity.java          ← 新增（记录）
                                SecurityIdentityProvider.java  ← 新增（端口）
stock-integration/market/       SimulatedSecurityIdentityProvider.java ← 新增（模拟实现）

stock-system/watchlist/         WatchlistItem.java             ← 新增（记录）
                                WatchlistItemRow.java          ← 新增（行映射）
                                WatchlistItemRepository.java   ← 新增（端口）
                                WatchlistItemMapper.java       ← 新增（SQL）
                                MyBatisWatchlistItemRepository.java ← 新增
                                WatchlistItemService.java      ← 新增（WAT-06~WAT-10、WAT-12）
                                WatchlistOverview.java         ← 新增（WAT-11 的自选侧结果）
                                WatchlistEntry.java            ← 新增（item + security + quote）
                                WatchlistMembership.java       ← 新增（WAT-12 的值）
                                MovedItem.java                 ← 新增（WAT-09 结果 + merged）

stock-backend/web/              WatchlistItemController.java   ← 新增（WAT-06~WAT-10）
                                WatchlistOverviewController.java ← 新增（WAT-11~WAT-12）
```

### 3.2 存储代理键：`security_id` 是 bigint，而契约的 `securityId` 是字符串

这是本轮**唯一一个必须新开的抽象**，先说清楚。

现状：

- 契约与前端全程用字符串 `securityId`；模拟行情源给的是 `"sim-" + securityCode`（如 `sim-600519`），
  `SimulatedSecurityQuoteProvider` 与 `SimulatedSectorProvider` 都按这个格式产出，前端测试里也是 `sim-600000`。
- `user_watchlist_item.security_id` 是 `bigint NOT NULL`（V5，M1 就定下了，不改表结构）。

两者之间必须有一次映射，而且**只能有一处**：

```java
public interface SecurityIdentityProvider {
    Optional<SecurityIdentity> resolve(String securityId);
    Map<String, SecurityIdentity> resolveAll(Collection<String> securityIds);
    Map<Long, SecurityIdentity> findByStorageIds(Collection<Long> storageIds);
}
public record SecurityIdentity(long storageId, SecuritySummary summary) {}
```

- 端口放在 `stock-market/domain`：**证券身份是行情域的事实**，不是自选域的事实。
  自选域只应"拿到一个能存进 bigint 的代理键 + 一个可回显的摘要"，不该知道 `sim-` 前缀。
- 实现在 `stock-integration`：`SimulatedSecurityIdentityProvider` 从 `SecurityMasterProvider.findAll("CN")`
  建索引，`storageId = Long.parseLong(securityCode)`，反向 `"sim-" + 左补零到 6 位`。
  前缀与宽度是**同一个类的两个常量**，正反两个方向共用，不可能分叉。
- 加一条**全量往返测试**：遍历 5149 只证券断言 `findByStorageIds(resolveAll(x)).resolve == x`，
  把"代理键是有损的"这种风险变成红灯。

**为什么用证券代码本身当代理键**：它是主数据里唯一且稳定的自然键，可读、可调试，
且不引入任何新的生成逻辑（M2-04 的取舍是"证券主数据不落库"，因此没有 Snowflake 主键可用）。

**已知问题（新增）**：真实主数据接入时，`stock_security.id` 才是应有的代理键，
届时需要一次数据迁移，并只替换 `SimulatedSecurityIdentityProvider` 这一个实现。

### 3.3 `createdAt` 必须能无损回读（与 M3-01 的取舍相反）

M3-01 的 `user_watchlist_group.created_at` 交给列默认值，且**从不读回**——
因为 `DEFAULT CURRENT_TIMESTAMP(3)` 的墙上时间取决于 MySQL 会话时区，
读回来就必须猜一个时区，猜错 8 小时且没有测试会红。

WAT-06 / WAT-07 的契约里 `createdAt` 是必返字段，所以本轮必须能回读。做法：

- **写入由应用负责**：`insert` 显式带 `created_at`，值取 `LocalDateTime.now(clock)`。
- **回读按同一个 `Clock` 的时区解释**：`createdAt.atZone(clock.getZone()).toOffsetDateTime()`。
  生产 `Clock` 是 `Clock.system(Asia/Shanghai)`（`BackendConfiguration.applicationClock`），
  于是库里的墙上时间是北京时间，回读也按北京时间，往返无损，且**不依赖 JVM 默认时区**——
  这正是 `TZ=UTC` 复跑要证明的。
- 列默认值保留（手工插入的行仍可用），但它不再参与应用路径。

`WatchlistItem` 因此持有 `LocalDateTime createdAt`，时区转换只在 `WatchlistItemService` 一处发生。

### 3.4 WAT-06：分页在应用层切

`user_watchlist_item` 的一个分组只有几十条，取全量后在应用层切页：

- 与 `PageData.slice` 的既有用法一致（STK-02、QTE-01 都是"整批取数 + 应用层筛/排/分页"）；
- 避免 `LIMIT/OFFSET` 与 `COUNT(*)` 两条 SQL 的排序口径分叉；
- 排序口径只有一处：`ORDER BY sort_no, id`（与 `idx_watchlist_item_user_group_sort` 一致）。

`page` 默认 1、`size` 默认 20、上限 100，与 STK-02 同口径；越界页码返回空页（`PageData` 既有语义）。

### 3.5 WAT-07：同组同证券是**成功幂等**，不是 409

契约原文："同组同证券保持幂等，允许同一证券存在于不同分组"；PRD WAT-01 的验收口径是
"重复添加按成功幂等返回 / 重复点击不生成重复记录"。因此：

1. 先 `insert`；撞 `uk_watchlist_item_group_security` → 读回已存在的那条，返回 **200**。
2. 不用"先查再插"：并发下会双写（唯一索引是唯一权威）。
3. 幂等键仍然保留：它解决的是"响应丢了、客户端重试"，与唯一索引解决的是两件事。

**`WATCHLIST_ITEM_EXISTS` 本轮不实现**。契约 §12.3 把它列在"常见异常"里，
但 WAT-07 明确要求幂等成功、WAT-09 明确要求合并，没有任何接口需要抛它。
不为了让枚举更全而编一条永远不触发的分支——记入已知问题。

**不存在的证券**：`resolve` 返回空 → `WATCHLIST_RESOURCE_NOT_FOUND`（404）。
契约没有专门的"证券不存在"码，复用 §12.3 已有的这个，避免自造业务码（记入已知问题）。

**退市 / 停牌提醒**：契约要求"新增时需返回状态提醒"，而 WAT-07 的字段表里没有提醒字段。
返回的 `security` 已含 `listingStatus`、`isSuspended`、`isSt`，提醒由它承担，**不额外加字段**。

### 3.6 WAT-08：硬删除 + 按 (group, item) 幂等

`user_watchlist_item` 没有 `deleted_at`（V5 就只有硬删除），因此"重复删除"与"itemId 不存在"
在库层面无法区分。契约要求重复删除幂等成功，于是：

1. `{groupId}` 是路径资源 → 必须归属本人且有效，否则 `WATCHLIST_RESOURCE_NOT_FOUND`（404）。
2. `DELETE ... WHERE id = ? AND user_id = ? AND group_id = ?` → `deleted = (影响行数 > 0)`。
3. 返回 200。**`deleted` 如实反映是否真的删掉了一行**，不为了让客户端高兴而恒返回 `true`。
4. 他人 itemId 命中 0 行 → 200 + `deleted=false`，且**不会删到别人的行**（`user_id` 在 WHERE 里）。
   §12.3 的"他人资源返回 404"由 groupId 这一层承担；对 itemId 而言 200/false 与"已删过"不可区分，
   不构成存在性泄露。集成测试显式断言"删别人的 item 不会删掉它"。

### 3.7 WAT-09：移动 + 合并

`If-Match` 是 **item 的 version**（不是分组的）。顺序固定为"先校验引用、再条件写"：

| 步 | 检查 | 失败 |
| --- | --- | --- |
| 1 | 源分组归属本人且有效 | 404 `WATCHLIST_RESOURCE_NOT_FOUND` |
| 2 | item 在源分组内且属于本人 | 404 `WATCHLIST_RESOURCE_NOT_FOUND` |
| 3 | `targetGroupId == groupId` | 409 `WATCHLIST_TARGET_GROUP_CONFLICT` |
| 4 | 目标分组归属本人且有效 | 404 `WATCHLIST_GROUP_NOT_FOUND`（请求体引用） |
| 5 | 目标组已有同证券 → 条件删源项；否则条件改 `group_id` + `sort_no` | 0 行 → 409 `WATCHLIST_VERSION_CONFLICT` |

- 合并时**返回存活的那条**（目标组原有项）的 `itemId` / `sortNo` / `version`，`merged=true`；
  源项已不存在，返回它的 id 会指向一个不存在的资源。
- 合并是"先探测目标、再二选一"，不是一条语句里赌：
  一条语句两种结果会让 0 行同时意味着"版本冲突"和"该走合并"，无法给出正确的错误码。
- 落到目标组的 `sort_no` 取 `nextItemSortNo(userId, targetGroupId)`（追加到末尾）。

### 3.8 WAT-10：原子重排，集合不匹配是 409

| 情况 | 结果 |
| --- | --- |
| `itemIds` 为 `null` | 400 `INVALID_REQUEST` |
| `itemIds` 有重复 | 400 `INVALID_REQUEST`（请求体格式错误） |
| 与组内当前项集合不一致（缺项 / 多出 / 含他人项） | **409 `WATCHLIST_VERSION_CONFLICT`**（客户端快照已过期，刷新后重试） |
| 空组 + 空数组 | 200，空结果（合法的 no-op） |
| 一致 | 每条 `sort_no = 下标`、`version + 1` |

"原子"由两件事共同保证：整个方法在一个事务里；每条 `UPDATE` 都带 `WHERE version = 当前版本`。
若读到之后、写之前有人改了组内项，条件更新命中 0 行 → 抛异常 → 整个事务回滚，
不会出现"改了一半的顺序"。

**与 WAT-05 的措辞差异是刻意的**：WAT-05 只说"缺项、重复或包含他人分组均拒绝"（→400），
WAT-10 明说"并发变化时返回 409 并要求刷新"。两个接口的集合语义相同、错误码不同，
按契约逐字实现，不擅自统一。

### 3.9 WAT-11：概览不得因单只证券缺失而全页失败

- `groupId` 缺省 → **返回全部有效分组的自选项**（与"分组摘要返回全部分组"口径一致；
  每条 item 自带 `groupId`，前端切分组无需二次请求）。
- item 视图**复用 WAT-06 的同一个记录**（含 `createdAt`、`quote`、`latestNewsCount`）：
  两处各定义一份 item 视图，就会在字段增删时各自演化且没有测试会红。
- 单只证券的降级：

| 缺失 | 表现 |
| --- | --- |
| 证券不在主数据（悬空自选） | `security = null`，条目保留（"不得自动移除"），计入 `limitations` |
| 该证券无行情快照 | `quote = null`，条目保留，计入 `limitations` |
| 资讯数 | `latestNewsCount = null`（M3-04），计入 `limitations` |

- `snapshotVersion` 取 `QuoteBatch.version()`（整批共用的版本；批次为空时是空串，不编造版本号）；
  `dataStatus` / `dataTime` 同样来自 `QuoteBatch`。
- `limitations` 是契约"数据状态字段"的落点：**人可读的降级说明数组**，
  逐条说明"少了什么、为什么、归属哪个任务"。空数组表示没有任何降级。
- `newsSince` → **400 `INVALID_REQUEST`**，消息点名 M3-04。
  理由与 `SecurityQueryService` 拒绝白名单外排序字段一致：静默忽略一个过滤条件，
  调用方会拿到"看起来正常"的响应，极难排查。`includeItems=true` 的 400 也是同一条原则。
- `marketStatus` 由 controller 从 `MarketStatusQueryService` 取，作为参数传给视图装配：
  这样 `stock-system` 只依赖 `stock-market` 的 **domain**，不依赖它的用例层。

### 3.10 WAT-12：membership 的 Map 是契约给的单值形状

- `securityIds` 逗号分隔，去重后必须 1~50 个，否则 400 `INVALID_REQUEST`。
- 返回 `Map<String, {groupId, groupName, itemId}>`，**只在自选里存在的 securityId 才出现在 Map 里**；
  不存在的 securityId 静默缺席（查询语义下"不在自选里"与"证券不存在"无需区分，也不编造占位）。
- 同一证券可以同时在多个分组，而 Map 每键只能有一个值 → 取
  **分组 `sort_no`、`group_id` 升序的第一条**（与 WAT-01 的分组顺序同源）。
  默认分组 `sort_no = 0`，因此自然优先。契约单值形状带来的信息损失记入已知问题。
- 组名从 `WatchlistGroupRepository` 取，**不在 item 表上冗余**（组名改了要立刻反映）。

### 3.11 关闭已知问题 #15：WAT-01 的 `includeItems=true`

`GroupView` 增加 `List<ItemView> items`，标 `@JsonInclude(NON_NULL)`：

- `includeItems=false`（默认）→ 字段不出现，**与 M3-01 的响应逐字节一致**；
- `includeItems=true` → 每组带上真实自选项（无行情，WAT-01 没有 `includeQuote` 参数）。

这不再是"编造空数组"：字段有真实来源，且只有被请求时才出现。

### 3.12 `stock-system` 新增对 `stock-market` 的编译依赖

`stock-system/pom.xml` 增加 `stock-market`（`compile`）。范围刻意最小：

- 只用 `stock-market` 的 **`domain`** 包（`SecuritySummary`、`SecurityIdentity*`、
  `QuoteSnapshot`、`QuoteBatch`、`QuoteSnapshotBatchProvider`），**不碰 `application`**；
- 无循环：`stock-market` 不依赖 `stock-system`（现有依赖只有 `stock-common` + Spring 起步依赖）；
- 方向正确：自选引用证券，证券目录属于行情域，端口定义在**提供方**的 domain 里。

### 3.13 装配与接线

- `BackendConfiguration` 新增三个 Bean：
  `watchlistItemRepository(WatchlistItemMapper)`、`watchlistItemService(...)`、
  `securityIdentityProvider(SecurityMasterProvider)`。
- `@MapperScan` 已放宽到 `cn.zhishi.stock.system`（M3-01 改的），新增的 `WatchlistItemMapper` 自动被扫到。
- `BackendConfigurationTest` 用 `ApplicationContextRunner`、**不走 `@MapperScan`**，
  必须为 `WatchlistItemMapper` 手动补 `withBean(... mock)`。
- `SecurityConfigurationTest` 的 401 断言要覆盖五个新端点。
- `DevelopmentAccountSeeder` **不加自选项**：它的语义是"让 dev 数据与注册之后一致"，
  而注册不会产生自选项。

---

## 4. 验证

### 4.1 红灯计划

1. 先落**类型声明 + 抛 `UnsupportedOperationException("尚未实现")` 的空壳**，让测试能编译；
2. 跑一遍，确认红灯来自**断言失败**而不是编译错误；
3. 再逐个实现。

### 4.2 测试清单

| 文件 | 模块 | 覆盖 |
| --- | --- | --- |
| `SimulatedSecurityIdentityProviderTest` | integration | 5149 只全量往返；未知格式 / 不存在返回空；批量结果与单个一致 |
| `WatchlistItemServiceTest` | system | WAT-06（分组不存在、分页、includeQuote、悬空证券）、WAT-07（幂等、撞唯一索引、证券不存在、sortNo 递增）、WAT-08（真删 / 重复删 / 他人 item）、WAT-09（合并与非合并、版本冲突、目标组冲突、跨用户）、WAT-10（缺项 / 重复 / 空组 / 版本冲突回滚）、WAT-12（过滤、排序取首条、范围校验） |
| `MyBatisWatchlistItemRepositoryTest` | system | 行映射、`createdAt` 往返、条件删除/移动返回布尔、插入参数（含显式 `created_at`）、排序下标 |
| `WatchlistItemControllerContractTest` | backend | 五个端点的字段集、幂等键复用与冲突、`If-Match` 的 4 种非法输入、错误码与 HTTP 状态 |
| `WatchlistOverviewControllerContractTest` | backend | WAT-11 字段集与 `limitations`、`newsSince` → 400、WAT-12 的 Map 形状与 1~50 校验 |
| `InfrastructureIntegrationTest#WatchlistItems` | backend | 真实 MySQL：唯一索引、硬删除、条件更新、合并、排序、`created_at` 往返 |
| `SecurityConfigurationTest` | backend | 五个新端点未登录 401 |
| `BackendConfigurationTest` | backend | 三个新 Bean 单例 |
| `WatchlistGroupControllerContractTest` | backend | `includeItems=true` 返回真实自选项（#15 关闭） |

时间相关断言必须 `TZ=UTC` 复跑。

---

## 5. 变更清单

- 新增：§3.1 的 15 个类 + 1 个 spec + 6 个测试文件（含 1 个 `@Nested` 块）
- 修改：`stock-system/pom.xml`、`BackendConfiguration`、`WatchlistGroupController`、
  `BackendConfigurationTest`、`SecurityConfigurationTest`、`InfrastructureIntegrationTest`、
  `TASKS.md`、`PROJECT_STATUS.md`、`CHANGELOG.md`

---

## 6. 验收结果

### 6.1 测试数

| 模块 | 本轮新增 | 该模块总数 |
| --- | --- | --- |
| `stock-integration` | 5（`SimulatedSecurityIdentityProviderTest`） | 111 |
| `stock-system` | 38（`WatchlistItemServiceTest` 32 + `MyBatisWatchlistItemRepositoryTest` 6） | 83 |
| `stock-backend` | 38（`WatchlistItemControllerContractTest` 19 + `WatchlistOverviewControllerContractTest` 10 + `InfrastructureIntegrationTest#WatchlistItems` 9） | 155 |
| **合计** | **81** | **507**（market 156 / system 83 / integration 111 / backend 155 / job 2） |

后端 507 + 前端 101（19 文件）= **608**。默认时区与 `TZ=UTC` 下各跑一遍，均 0 失败 0 错误。

### 6.2 红灯记录

按 §4.1 的两步走：先把 15 个新类落成抛 `UnsupportedOperationException("尚未实现")` 的空壳，
跑一遍得到 **stock-system 38 个失败 + stock-integration 5 个失败**，全部是**断言失败**而不是编译错误
——证明测试真的在测行为，而不是"编译不过所以红灯"。

### 6.3 真实 MySQL 8.4 的实测发现

1. **唯一索引 `uk_watchlist_item_group_security` 只含 `(group_id, security_id)`，不含 `user_id`。**
   因为 `group_id` 本身已按用户隔离，"同组同证券"的判定天然就是组内唯一的；
   应用层因此**不需要**再拼 `user_id` 到唯一性判断里（`user_id` 仍写在所有 `WHERE` 中做越权隔离，
   两者职责不同，这点在实现前容易被误当成"索引漏了一列"）。
2. **`user_watchlist_item` 没有 `deleted_at`**，WAT-08 的"硬删除"是表的既有形态，
   不需要应用层额外做什么；`delete` 的 `deleted` 字段直接反映真实影响行数即可。
3. **`created_at` 由应用写入后能被无损读回**（`DATETIME(3)`，毫秒精度往返相等）。
   §3.3 对 M3-01 取舍的推翻是成立的：列默认值 `CURRENT_TIMESTAMP(3)` 的时区歧义只对**默认值**成立，
   显式写入即可绕开。集成测试特意写入 `2020-01-02T03:04:05.123` 来证明读回的不是"当前时间"。
4. **合并路径的不变量**：WAT-09 合并时被删掉的是**源行**，目标行原样保留（`version` 不变）。
   这比"两行都改"更符合用户直觉——他没动过的那条自选项不应该因为别人搬进来而版本跳变。

### 6.4 spec 最初漏掉、实现时才暴露的东西

1. **`stock-system` 需要新增对 `stock-market` 的编译依赖。** §3.2 决定把 `SecurityIdentityProvider`
   放在 `market.domain` 时没有意识到 `stock-system` 当前不依赖 `stock-market`，
   结果是 16 个"程序包不存在"的编译错误。已按 §3.12 补上依赖并加注释说明无环。
2. **独立 `MockMvc` 会把 `OffsetDateTime` 序列化成 epoch 数字。** `WRITE_DATES_AS_TIMESTAMPS`
   是 Spring Boot 自动配置关掉的，`Jackson2ObjectMapperBuilder` 自己不关。这是已知问题 #5 的同类，
   但 #5 只记了 `LocalDate` → 数组，没记 `OffsetDateTime` → 数字。**§4 的"验证"一节应该显式要求
   新契约测试沿用 `MarketControllerContractTest` 的 helper**，否则时间字段断言会静默失真。
3. **WAT-10 的排序响应必须回显请求里的 `itemId`。** 测试桩一开始返回固定 `itemId`，
   于是"请求 `["2","1"]`、响应却是同一个 id"这个真实缺陷被桩掩盖了。契约测试的桩要能区分入参。

### 6.5 遗留问题

- **新增已知问题 #18**：契约 §12.3 列出的 `WATCHLIST_ITEM_EXISTS` 在本轮**没有任何端点会抛出**——
  WAT-07 要求"同组同证券"幂等成功（返回已存在的那条），WAT-09 要求撞车时合并，
  两条路都通不到"报错"分支。按 §3.5 的决定**不实现**，保留在契约文档里，
  等真实数据源接入后若产品口径变化再评估。
- STK-05 批量行情接口（已知问题 #10）仍未实现；WAT-11 的 `latestNewsCount` 恒为 `null`
  （等 M3-04 资讯 Provider）。两者都不影响 WAT-06~WAT-12 的契约正确性。

