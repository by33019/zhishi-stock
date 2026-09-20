# M3-01 自选分组 CRUD — 设计

> 对应契约：`docs/RESTful-API.md` §12.1 WAT-01~WAT-05、§12.3 业务规则、§3.7 幂等与并发。
> 依赖：M2-04（已 ✅）。表结构 `sql/flyway/V5__create_watchlist_domain.sql` 已就绪。

## 1. 背景

### 1.1 现状

V5 迁移早在 M1 就写好了 `user_watchlist_group` 与 `user_watchlist_item`，但**至今没有任何 Java 代码引用这两张表**：

```
$ grep -rn "user_watchlist" backend --include=*.java
（无输出）
```

也就是说，自选中心的表结构、唯一约束、软删除字段、乐观锁列都已经落库，缺的只是应用层。
M3 的第一个任务就是把 WAT-01~WAT-05 接上去。

### 1.2 契约要点（原文摘要）

| 编号 | 方法 | URL | 关键约定 |
| --- | --- | --- | --- |
| WAT-01 | `GET` | `/watchlist-groups` | Query `includeItems=false`；返回 `groupId`/`groupName`/`sortNo`/`isDefault`/`itemCount`/`version`；按 `sortNo`、`groupId` 排序 |
| WAT-02 | `POST` | `/watchlist-groups` | Header `Idempotency-Key`；Body `groupName`；返回 `groupId`/`groupName`/`sortNo`/`isDefault=false`/`version`/`createdAt`；同用户活动分组名唯一 |
| WAT-03 | `PATCH` | `/watchlist-groups/{groupId}` | Header `If-Match`；Body `groupName`；默认分组允许改显示名但仍保持默认属性 |
| WAT-04 | `DELETE` | `/watchlist-groups/{groupId}` | Header `If-Match`；非空组必填 Query `moveItemsToGroupId`；返回 `deleted`/`movedItemCount`；默认分组不可删除 |
| WAT-05 | `PUT` | `/watchlist-groups/order` | Body `groupIds` 必须包含本人全部有效分组且不重复；缺项/重复/含他人分组均拒绝；原子更新 |

§12.3 的三条硬规则：

1. 注册完成后创建且仅创建一个默认分组；数据库唯一约束保证每个用户只有一个有效默认组。
2. 分组名称去除首尾空白后长度 1 至 20 个字符，同一用户有效分组名不可重复。
3. **所有路径资源都必须校验 `user_id`，对他人资源返回 `WATCHLIST_RESOURCE_NOT_FOUND`，避免泄露存在性。**

§3.7 的两条：

- `Idempotency-Key` 在同一用户和业务范围内唯一；相同键 + 相同请求体重复提交返回第一次业务结果；
  相同键 + 不同请求体返回 `IDEMPOTENCY_KEY_CONFLICT`。
- 含 `version` 的资源更新使用乐观锁，版本不匹配返回 HTTP 409。

### 1.3 已有代码里能直接复用的东西

| 需要的能力 | 现有实现 | 位置 |
| --- | --- | --- |
| `USER` 鉴权取 `userId` | `AccessTokenPrincipal principal = (AccessTokenPrincipal) authentication.getPrincipal()` | `CurrentUserController` |
| 统一返回壳 | `ApiResponse.success(data, TraceIdFilter.current(request), OffsetDateTime.now(clock))` | `CurrentUserController#success` |
| 用户域端口 + MyBatis 实现 | `UserAccountRepository` / `MyBatisUserAccountRepository` / `SysUserMapper` | `stock-system/auth` |
| Redis 存储 | `RedisRefreshSessionStore`（`StringRedisTemplate`） | `stock-system/auth` |
| 主键生成 | `LongSupplier databaseIdGenerator`（`AtomicLong(currentTimeMillis() << 12)` 递增） | `BackendConfiguration` |
| 异常 → 业务码映射 | `@RestControllerAdvice` 一异常一方法 | `GlobalExceptionHandler` |
| 数据表 | V5 两张表（含两个生成列 + 唯一索引 + CHECK） | `sql/flyway/V5__…sql` |

**结论**：M3-01 不需要新模块、不需要新表、不需要新的鉴权机制。

## 2. 目标与非目标

### 2.1 目标

1. WAT-01~WAT-05 五个接口全部可用，响应字段与契约逐字对齐。
2. 契约里的每一条硬规则都有一条会变红的测试（不是"写进注释"）。
3. 乐观锁（`If-Match` → 409）与幂等（`Idempotency-Key`）真正生效，不是接收后丢掉。
4. 幂等组件做成**可复用**的，WAT-07（M3-02）与 AI-03（M3-07）直接复用同一份实现。

### 2.2 非目标

| 不做的事 | 归属 |
| --- | --- |
| WAT-06~WAT-12（自选项、概览、membership） | M3-02 |
| WAT-01 的 `includeItems=true` | M3-02（见 §3.9） |
| 前端 watchlist 接真实接口 | M3-03 |
| AUTH-03 注册流程本身 | 不在 M3 清单内；本任务只提供它要调用的 `createDefaultGroup` |
| 自选写操作 60/min 限流（§22.1） | 全站限流基础设施，当前**任何**接口都没有；见 §6 |
| SSE `watchlist` 频道推送（§20.2） | 契约未在 WAT-01~WAT-05 里要求 |

## 3. 设计

### 3.1 分层与包

沿用 `stock-system/auth` 已经验证过的形态（端口 + MyBatis 实现 + `@Mapper` 接口同模块）：

```
backend/stock-system/src/main/java/cn/zhishi/stock/system/
  watchlist/
    WatchlistGroup.java                     # 领域记录
    WatchlistGroupName.java                 # 值对象：分组名的唯一合法定义
    WatchlistGroupRepository.java           # 端口
    MyBatisWatchlistGroupRepository.java    # 实现
    WatchlistGroupMapper.java               # @Mapper（SQL 全在这里）
    WatchlistGroupService.java              # 用例层：校验 + 事务 + 乐观锁
    DeleteResult.java                       # WAT-04 的返回值
    WatchlistErrorCode.java                 # 业务码枚举
    WatchlistException.java                 # 业务异常基类（携带 code）
  idempotency/
    IdempotencyStore.java                   # 端口
    IdempotencyRecord.java
    RedisIdempotencyStore.java              # 实现（24h 窗口）
    IdempotencyGuard.java                   # 显式包裹一次写操作

backend/stock-backend/src/main/java/cn/zhishi/stock/backend/web/
  WatchlistGroupController.java             # 5 个接口 + 请求/响应 DTO
```

**为什么自选放 `stock-system` 而不是 `stock-market`**：自选是**用户域**资源，归属由 `user_id` 决定，
与行情域无关；`stock-system` 里已经有"端口 + MyBatis 实现"的完整先例（`UserAccountRepository`）。
放 `stock-market` 反而会让"用户态资源"和"市场数据"两个域混在一起。

**为什么用例层不用 `@Service`**：与 `AuthenticationService` / `CurrentUserController` 一致——
Bean 统一在 `BackendConfiguration` 里声明，依赖关系一眼可见。

**事务**：`WatchlistGroupService` 的写方法标 `@Transactional`（Spring Boot 默认开启事务管理）。
WAT-04（搬移 + 软删）与 WAT-05（N 条排序）必须落在同一事务里，否则会出现"搬了没删"或"改了一半顺序"。

### 3.2 表结构能替我们兜住什么

V5 已经用生成列把两条业务不变量写进了数据库，**应用层不能重复实现，只能依赖它**：

```sql
active_group_name varchar(40) GENERATED ALWAYS AS (
  CASE WHEN deleted_at IS NULL THEN group_name ELSE NULL END) STORED,
UNIQUE INDEX uk_watchlist_group_user_name (user_id, active_group_name),

default_owner_id bigint GENERATED ALWAYS AS (
  CASE WHEN deleted_at IS NULL AND is_default = 1 THEN user_id ELSE NULL END) STORED,
UNIQUE INDEX uk_watchlist_group_default_owner (default_owner_id),

CONSTRAINT ck_watchlist_group_name CHECK (CHAR_LENGTH(TRIM(group_name)) BETWEEN 1 AND 20)
```

- 软删除后 `active_group_name` 变 `NULL`，`NULL` 在唯一索引里互不冲突 →
  **同一个名字可以重建**，不需要应用层"查是否被软删过"。
- 每个用户只能有一个 `deleted_at IS NULL AND is_default = 1` 的行 → 默认分组唯一性由索引兜底。

**但唯一索引不能替代应用层预检查**：索引报的是 `DuplicateKeyException`，无法区分
"分组名重复"和"默认分组重复"，也没有可读消息。所以：

- **预检查**（`findActiveByName`）负责给出 `WATCHLIST_GROUP_NAME_EXISTS` 这种干净的业务码；
- **`DuplicateKeyException` 兜底**（并发下的竞态）同样翻译成 `WATCHLIST_GROUP_NAME_EXISTS`。

两者都要有，缺一个就会在并发下漏出 500。

**collation 提醒**：表是 `utf8mb4_general_ci`，**大小写不敏感**。
所以 `findActiveByName("abc")` 会命中已存在的 `"ABC"`，与唯一索引行为一致——这是对的，
但它意味着"分组名唯一"是**大小写不敏感的唯一**，必须有一条测试把这个行为钉住（见 §4.2）。

### 3.3 分组名：`WatchlistGroupName`

"什么是一个合法的分组名"只能有一处定义，否则服务端校验和数据库 CHECK 会分叉：

```java
public record WatchlistGroupName(String value) {

    public static final int MAX_LENGTH = 20;

    public static WatchlistGroupName of(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < 1 || length > MAX_LENGTH) {
            throw new WatchlistException(
                    WatchlistErrorCode.GROUP_NAME_INVALID,
                    "分组名称去除首尾空白后需为 1 至 20 个字符");
        }
        return new WatchlistGroupName(trimmed);
    }
}
```

两个刻意的选择：

- **`trim()` 而不是 `strip()`**：MySQL 的 `TRIM()` 默认只去空格，Java 的 `trim()` 只去 `<= U+0020`。
  两者对空格的行为一致；用 `strip()`（去 Unicode 空白）会比数据库更宽，出现
  "应用层放行、数据库 CHECK 拒绝"的 500。
- **`codePointCount` 而不是 `length()`**：数据库用 `CHAR_LENGTH()` 数字符，
  Java 的 `length()` 数 UTF-16 码元。一个 emoji 在 Java 里算 2、在 MySQL 里算 1，
  用 `length()` 会出现"20 个字符的合法名字被数据库拒绝"。测试里钉一条（见 §4.2）。

值对象**无法被构造成非法状态**，因此校验不需要在服务里重复第二次。

### 3.4 乐观锁：`If-Match` 怎么解析、怎么生效

**解析**：契约 §3.5 的示例值是 `"3"`（带引号，HTTP ETag 风格）。实现接受两种写法：

```java
// "3" / 3 / W/"3" 之外的一律 400 INVALID_REQUEST
static int parseIfMatch(String header) { … }   // 去首尾引号后按 int 解析
```

- 缺头 → 400 `INVALID_REQUEST`，消息"缺少 If-Match 请求头"。
- `*` → 400 `INVALID_REQUEST`（契约没有为 WAT-03/04 定义"匹配任意版本"的语义，
  静默当成"任意"会让并发保护形同虚设）。
- 非数字 → 400 `INVALID_REQUEST`。

**生效**：条件写在 `WHERE` 里，**不是**先查后比再写：

```sql
UPDATE user_watchlist_group
   SET group_name = #{groupName}, version = version + 1
 WHERE id = #{groupId} AND user_id = #{userId}
   AND deleted_at IS NULL AND version = #{expectedVersion}
```

流程：同一事务内先 `findActive(userId, groupId)` 拿到行（拿不到 → 404 `WATCHLIST_RESOURCE_NOT_FOUND`），
再执行上面的条件 UPDATE，**影响行数为 0 → 409 `WATCHLIST_VERSION_CONFLICT`**。

为什么必须先查：只有"查不到"和"版本不对"分开，才能把 404 和 409 分开；
先查之后 UPDATE 的 `WHERE` 依然是唯一权威（MySQL 在 REPEATABLE READ 下 UPDATE 走当前读），
所以并发下不会出现"查到的是旧版本、写进去的是新版本"。

### 3.5 幂等：可复用的 `IdempotencyGuard`

契约里有 **8 个**接口要求 `Idempotency-Key`（AUTH-02/03/07、WAT-02/07、EXP-01、AI-03/06/07/08）。
所以它必须是一份可复用实现，不能塞进 WAT-02。

**端口**（`stock-system/idempotency`）：

```java
public interface IdempotencyStore {
    Optional<IdempotencyRecord> find(String scope, long userId, String key);
    void save(String scope, long userId, String key, IdempotencyRecord record);
}

public record IdempotencyRecord(String requestBody, String responseJson) {}
```

`RedisIdempotencyStore`：key = `idempotency:{scope}:{userId}:{key}`，
value = 记录的 JSON，TTL = 24h（契约 §3.7"有效窗口默认 24 小时"）。

**守卫**（显式调用，不做 AOP / 不做 ResponseBody 缓冲的 Filter）：

```java
public <T> T execute(String scope, long userId, String key, Object requestBody,
                     Class<T> responseType, Supplier<T> action) {
    IdempotencyRecord existing = store.find(scope, userId, key).orElse(null);
    if (existing != null) {
        if (!existing.requestBody().equals(canonical(requestBody))) {
            throw new WatchlistException(IDEMPOTENCY_KEY_CONFLICT, "幂等键已被不同请求体使用");
        }
        return objectMapper.readValue(existing.responseJson(), responseType);
    }
    T result = action.get();
    store.save(scope, userId, key, new IdempotencyRecord(canonical(requestBody),
            objectMapper.writeValueAsString(result)));
    return result;
}
```

**为什么显式而不是 Filter**：Filter 要缓冲整个响应体才能回放，还得自己处理 SSE / 二进制；
而契约里要求幂等的接口全部是"小请求体 + 小 JSON 响应"的写操作。
显式调用让"这个接口有幂等保护"在控制器里一眼可见，且**可以用一个 mock store 单测**，不需要起 Redis。

**先存后返回的顺序**：先执行 `action` 再 `save`。如果 `save` 失败（Redis 抖动），
接口会返回 500 但业务已成功——这是"幂等键丢失"而不是"重复创建"，比反过来
（先占键、业务失败后键还在，导致用户重试被回放成一个不存在的成功结果）安全。
这条取舍写进 §6。

### 3.6 默认分组：`createDefaultGroup` 由谁调用

契约 §12.3 说"注册完成后创建且仅创建一个默认分组"。**AUTH-03 注册尚未实现**，
所以本轮：

- `WatchlistGroupService.createDefaultGroup(long userId)` 作为**公开用例**实现，
  名字写死 `默认分组`（契约没有规定显示名，实现里只此一处定义）；
- `DevelopmentAccountSeeder` 调用它，让 dev / test 环境的数据与"注册之后"的状态一致；
- 在 §6 记一条：AUTH-03 落地时必须调用同一个方法。

不采用"首次访问时懒创建"：那会让 WAT-05 的"必须包含本人全部有效分组"在一个空账号上
出现"0 个分组"的合法状态，与契约"注册后必然有 1 个默认分组"矛盾。

`createDefaultGroup` 用 `INSERT … ON DUPLICATE KEY UPDATE` 不行——我们要的是**幂等**：
若该用户已有有效默认分组（`default_owner_id` 唯一索引会拒绝），直接返回现有那一个。

### 3.7 错误码与 HTTP 状态映射

`WatchlistException` 携带 `WatchlistErrorCode`，`GlobalExceptionHandler` 加**一个**处理方法
（与 `SectorNotFoundException` 同样的"业务码由异常自身携带"写法）：

| 场景 | 业务码 | HTTP |
| --- | --- | --- |
| 分组名去除首尾空白后不在 1~20 字符 | `VALIDATION_FAILED`（`fieldErrors.groupName`） | 400 |
| 缺 `If-Match` / `If-Match` 非法 / `groupIds` 缺项或重复 | `INVALID_REQUEST` | 400 |
| `includeItems=true`（本轮未实现） | `INVALID_REQUEST` | 400 |
| 路径 `{groupId}` 不是本人有效分组（含不存在、含他人） | `WATCHLIST_RESOURCE_NOT_FOUND` | 404 |
| `moveItemsToGroupId` 不是本人有效分组 | `WATCHLIST_GROUP_NOT_FOUND` | 404 |
| 分组名重复（含大小写不同） | `WATCHLIST_GROUP_NAME_EXISTS` | 409 |
| 删除默认分组 | `DEFAULT_GROUP_CANNOT_DELETE` | 409 |
| `version` 不匹配 | `WATCHLIST_VERSION_CONFLICT` | 409 |
| 非空组删除未传 `moveItemsToGroupId` | `WATCHLIST_TARGET_GROUP_REQUIRED` | 400 |
| `moveItemsToGroupId` 等于被删分组 | `WATCHLIST_TARGET_GROUP_CONFLICT` | 409 |
| 幂等键已被不同请求体使用 | `IDEMPOTENCY_KEY_CONFLICT` | 409 |

三点说明：

- **为什么路径资源用 `WATCHLIST_RESOURCE_NOT_FOUND` 而不是 `WATCHLIST_GROUP_NOT_FOUND`**：
  §12.3 明确要求"对他人资源返回 `WATCHLIST_RESOURCE_NOT_FOUND`，避免泄露存在性"。
  两者用同一个码、同一状态，调用方无法区分"不存在"与"是别人的"。
  `WATCHLIST_GROUP_NOT_FOUND` 留给**请求体/Query 里**引用的分组（WAT-04 的 `moveItemsToGroupId`）。
- **为什么 `groupIds` 含他人分组是 400 而不是 404**：§12.3 的反泄露规则针对**路径资源**；
  WAT-05 的 ID 在请求体里，判定标准是"是否恰好等于本人全部有效分组集合"，
  含他人 ID 与含不存在的 ID 表现完全一致（都只是"集合不相等"），因此 400 `INVALID_REQUEST`
  且消息只说"缺少本人分组"，不区分是哪一种。
- **`WATCHLIST_TARGET_GROUP_CONFLICT` 在本轮只用于"目标组 = 源组"**。
  WAT-09 的"目标组已存在同证券"在契约里是**合并**（`merged=true`）而不是冲突，所以不属于这里。

### 3.8 WAT-04 的 `moveItemsToGroupId` 语义

按顺序判定：

1. `{groupId}` 不是本人有效分组 → 404 `WATCHLIST_RESOURCE_NOT_FOUND`
2. 是默认分组 → 409 `DEFAULT_GROUP_CANNOT_DELETE`
3. `version` 不匹配 → 409 `WATCHLIST_VERSION_CONFLICT`
4. 组内 `itemCount == 0` → 直接软删，`movedItemCount = 0`（**忽略**传入的 `moveItemsToGroupId`）
5. `itemCount > 0` 且未传 `moveItemsToGroupId` → 400 `WATCHLIST_TARGET_GROUP_REQUIRED`
6. `moveItemsToGroupId` 等于 `{groupId}` → 409 `WATCHLIST_TARGET_GROUP_CONFLICT`
7. `moveItemsToGroupId` 不是本人有效分组 → 404 `WATCHLIST_GROUP_NOT_FOUND`
8. 搬移 → 软删，返回 `movedItemCount = 实际搬到目标组的条数`

第 4 步"忽略"是刻意的：契约只规定"删除非空组时必填"，空组传了也不该报错
（客户端无法可靠知道组是否为空，报错会让"删空组"变成一个需要先查询的两步操作）。

**搬移要先处理目标组的同证券重复**：`uk_watchlist_item_group_security(group_id, security_id)`
会让直接 `UPDATE group_id` 在"目标组已有同一只证券"时撞唯一索引。所以搬移是两条 SQL：

```sql
-- 1) 目标组已有的同证券：源组里的那条直接删掉（等价于合并）
DELETE src FROM user_watchlist_item src
  JOIN user_watchlist_item dst
    ON dst.user_id = src.user_id
   AND dst.group_id = #{targetGroupId}
   AND dst.security_id = src.security_id
 WHERE src.user_id = #{userId} AND src.group_id = #{sourceGroupId};

-- 2) 剩下的整体搬到目标组
UPDATE user_watchlist_item
   SET group_id = #{targetGroupId}, version = version + 1, updated_at = NOW(3)
 WHERE user_id = #{userId} AND group_id = #{sourceGroupId};
```

`movedItemCount` 取第 2 条的影响行数，即**真正搬过去的条数**（被合并掉的不算）。
契约没写这一层，因此这个定义要在 spec 与测试里都写明，不能留给读者猜。

`user_watchlist_item` **没有 `deleted_at` 列**（V5 里只有两张表，item 表没有软删），
所以合并走的是硬删——与 WAT-08"重复删除按幂等成功处理"一致。

### 3.9 WAT-01 的 `includeItems`

契约把 `items` 标为"可选"。本轮**只实现 `includeItems=false`（默认）**。

`includeItems=true` 返回 **400 `INVALID_REQUEST`**，消息写明"分组内自选项由 WAT-06 提供（M3-02）"。

**为什么不是返回 `items: []`**：`items: []` 是一个"看起来合法"的答案，它会告诉前端
"这个分组里没有股票"——如果分组里其实有，这就是**编造数据**。
这与 M2-08 定下的"没有数据来源的字段降级为尚未实现，不保留编造值"是同一条原则，
也与"可点但无反应的按钮比 disabled 更糟"同源：**宁可响亮地失败，也不要安静地给错答案**。

### 3.10 WAT-05 的原子性与版本

`PUT /watchlist-groups/order` 没有 `If-Match`，因此不做版本预检，而是：

1. 读本人全部有效分组（`findActiveByUser`），得到集合 `A`；
2. 校验 `groupIds` 是 `A` 的一个**排列**（长度相同、去重后元素相同）；
   不满足 → 400 `INVALID_REQUEST`；
3. 在同一事务里按顺序把第 i 个分组的 `sort_no` 置为 `i`，并 `version = version + 1`。

**每个被提交的分组都 +1 版本**，包括 `sortNo` 没变的：如果只对变化的行 +1，
"顺序没变"的一次提交就不会推进版本，客户端手里的旧 `version` 仍然可用，
并发保护会出现一个"顺序未变所以版本也不变"的漏洞。统一 +1 的代价只是客户端多刷新一次。

### 3.11 响应字段集：一个接口一个形状

契约给 WAT-01 与 WAT-02 列的字段集并不相同（WAT-01 有 `itemCount` 没有 `createdAt`，WAT-02 反之），
因此控制器里有两个 record，而不是一个"通用分组视图"：

| 视图 | 字段 | 用在 |
| --- | --- | --- |
| `GroupView` | `groupId` / `groupName` / `sortNo` / `isDefault` / `itemCount` / `version` | WAT-01、WAT-03、WAT-05 |
| `CreatedGroupView` | `groupId` / `groupName` / `sortNo` / `isDefault` / `version` / `createdAt` | WAT-02 |

多给字段不是"没坏处"：一个 `itemCount` 恒为 0 的新建分组会让前端以为"这个分组已经查过了"，
而列表里多出 `createdAt` 会诱导前端拿它当排序依据。契约写了什么就返回什么。

`groupId` 一律是字符串（Snowflake 主键超出 JS 安全整数范围）；请求体里的 `groupIds`
声明为 `List<Long>`，Jackson 会把 `"7001"` 强制成 `7001`，非数字直接 400。

### 3.12 集成点

`WatchlistGroupMapper` 能成为 Bean，靠的是 `StockBackendApplication` 上的 `@MapperScan`。
它原来写死 `cn.zhishi.stock.system.auth`，本轮放宽为 `cn.zhishi.stock.system`
（仍限定 `annotationClass = Mapper.class`，因此放宽范围不会顺带注册无关接口）。

`BackendConfigurationTest` 用 `ApplicationContextRunner` 单独装配 `BackendConfiguration`、
不走 `@MapperScan`，因此那里必须显式补一个 `WatchlistGroupMapper` 的 mock——
这也让"这个配置类依赖哪些 Mapper"变成测试里看得见的事实。

## 4. 验证

### 4.1 红灯优先

按 `AGENTS.md` 的 TDD 要求，先让测试变红再写实现。本轮的红灯是**真实红灯**：
先写 `WatchlistGroupServiceTest`（纯单测，mock 端口），此时 `WatchlistGroupService` 类还不存在 →
**编译失败不算断言失败**，因此按 skill `zhishi-milestone-delivery` 的两步法：
先建出空壳（方法体 `throw new UnsupportedOperationException()`）→ 跑到断言失败 → 再实现。

### 4.2 测试清单

**`WatchlistGroupNameTest`**（纯函数，最便宜也最该先写）

| 用例 | 断言 |
| --- | --- |
| `"  我的自选  "` | `value() == "我的自选"` |
| `"   "` / `null` / `""` | `WatchlistException`，码 `GROUP_NAME_INVALID` |
| 21 个 ASCII 字符 | 抛异常 |
| 20 个 ASCII 字符 | 通过 |
| 20 个 emoji（Java `length()` = 40） | **通过**（`codePointCount` 与 MySQL `CHAR_LENGTH` 对齐） |

**`WatchlistGroupServiceTest`**（mock `WatchlistGroupRepository` + 固定 `LongSupplier`）

| 用例 | 断言 |
| --- | --- |
| WAT-01 列表 | 委托 `findActiveByUser`，顺序原样返回 |
| WAT-02 新建 | 名字先 trim；`sortNo = 现有最大 + 1`；`isDefault = false`；`version = 0` |
| WAT-02 重名 | 抛 `WATCHLIST_GROUP_NAME_EXISTS` |
| WAT-02 仓库抛 `DuplicateKeyException` | **也**翻译成 `WATCHLIST_GROUP_NAME_EXISTS`（并发兜底） |
| WAT-03 改名 | 名字先 trim；`expectedVersion` 原样传给端口 |
| WAT-03 目标不存在 | 抛 `WATCHLIST_RESOURCE_NOT_FOUND` |
| WAT-03 端口返回 false（版本冲突） | 抛 `WATCHLIST_VERSION_CONFLICT` |
| WAT-03 默认分组改名 | 允许（不因 `isDefault` 拒绝） |
| WAT-04 默认分组 | 抛 `DEFAULT_GROUP_CANNOT_DELETE` |
| WAT-04 非空未传目标 | 抛 `WATCHLIST_TARGET_GROUP_REQUIRED` |
| WAT-04 目标 = 源 | 抛 `WATCHLIST_TARGET_GROUP_CONFLICT` |
| WAT-04 目标不存在 | 抛 `WATCHLIST_GROUP_NOT_FOUND` |
| WAT-04 空组传了目标 | 正常删除，`movedItemCount = 0`，**不校验**目标 |
| WAT-04 正常 | `movedItemCount` = 端口返回值；调用软删 |
| WAT-05 集合不完整 / 有重复 / 含他人 | 抛 `INVALID_REQUEST` |
| WAT-05 正常 | 端口收到 `[id1, id2, id3]` 顺序；返回重新读取的列表 |
| `createDefaultGroup` 已有默认组 | 返回现有组，不新建 |

**`IdempotencyGuardTest`**（mock `IdempotencyStore`）

| 用例 | 断言 |
| --- | --- |
| 首次 | 执行 action；把请求体与响应写入 store |
| 相同键 + 相同请求体 | **不**执行 action；回放第一次的响应 |
| 相同键 + 不同请求体 | 抛 `IDEMPOTENCY_KEY_CONFLICT`；不执行 action |
| 不同键 | 各自执行 |

**`WatchlistGroupControllerContractTest`**（`MockMvcBuilders.standaloneSetup`，mock 服务）

| 用例 | 断言 |
| --- | --- |
| WAT-01 | `$.data[0].groupId` 是**字符串**、`$.data[0].itemCount` 是数字、字段名逐字对齐 |
| WAT-02 | `$.data.isDefault == false`；`createdAt` 存在；`Idempotency-Key` 缺失 → 400 |
| WAT-03 | 缺 `If-Match` → 400 `INVALID_REQUEST`；`If-Match: "3"` 与 `If-Match: 3` 都能解析出 3 |
| WAT-04 | `$.data.deleted == true`、`$.data.movedItemCount` |
| WAT-05 | 缺项 → 400 `INVALID_REQUEST` |
| 未登录 | 由 `SecurityConfigurationTest` 覆盖 |

**`WatchlistGroupRepositoryIntegrationTest`**（Testcontainers + 真实 MySQL 8.4）
——本机 Docker 可用，所以这一层是**真跑**的，不是"CI 覆盖"：

| 用例 | 断言 |
| --- | --- |
| `insert` + `findActiveByUser` | 往返一致；`itemCount` 为 0 |
| 同名再插 | `DuplicateKeyException` |
| 大小写不同的同名再插 | 同样 `DuplicateKeyException`（钉住 CI collation 行为） |
| 软删后同名重建 | 成功（生成列变 `NULL` 的作用） |
| 两个有效默认分组 | `DuplicateKeyException`（`uk_watchlist_group_default_owner`） |
| `rename` 错误版本 | 影响行数 0 |
| `rename` 正确版本 | 影响行数 1 且 `version + 1` |
| `reorder` | `sortNo` 按传入顺序落库，`version` 全部 +1 |
| `moveItems`（目标组已有同证券） | 重复项被合并掉，`movedItemCount` = 真正搬移数 |
| CHECK 约束 | 直接 `INSERT` 一个 21 字符的名字 → 数据库拒绝（证明应用层校验与 DB 一致） |

**`SecurityConfigurationTest`** 增加一条：游客访问 `/api/v1/watchlist-groups` → 401。
（当前 `anyRequest().authenticated()` 已经覆盖，但**断言写下来**才能防止将来有人顺手加宽前缀。）

### 4.3 回归与文档

- 后端全量 `mvn test`；前端 `npm run typecheck` + `vitest --run`（本轮不动前端，但改动会进 CI）。
- 同步 `TASKS.md` / `PROJECT_STATUS.md` / `CHANGELOG.md`。
- 推送后核对 CI 四个作业。

## 5. 变更清单

**新增（后端）**

- `stock-system/…/watchlist/`：`WatchlistGroup`、`WatchlistGroupName`、`WatchlistGroupRepository`、
  `MyBatisWatchlistGroupRepository`、`WatchlistGroupMapper`、`WatchlistGroupService`、
  `DeleteResult`、`WatchlistErrorCode`、`WatchlistException`
- `stock-system/…/idempotency/`：`IdempotencyStore`、`IdempotencyRecord`、
  `RedisIdempotencyStore`、`IdempotencyGuard`
- `stock-backend/…/web/WatchlistGroupController`
- 测试：`WatchlistGroupNameTest`、`WatchlistGroupServiceTest`、`IdempotencyGuardTest`、
  `MyBatisWatchlistGroupRepositoryTest`、`WatchlistGroupControllerContractTest`、
  `WatchlistGroupRepositoryIntegrationTest`

**修改（后端）**

- `BackendConfiguration`：新增 watchlist 与 idempotency 的 Bean
- `GlobalExceptionHandler`：新增 `WatchlistException` 一个处理方法
- `DevelopmentAccountSeeder`：调用 `createDefaultGroup`
- `SecurityConfigurationTest`：新增游客 401 断言
- `InfrastructureIntegrationTest`：不修改（新集成测试独立成类）

## 6. 不在本轮范围

| 项 | 原因 / 归属 |
| --- | --- |
| WAT-01 `includeItems=true` | 需要 item 读取与 `security` 投影，属 WAT-06（M3-02）；本轮显式 400 |
| WAT-06~WAT-12 | M3-02 |
| 前端 watchlist 页面 | M3-03 |
| 自选写操作 60/min 限流（§22.1） | 全站限流基础设施尚未存在，**任何接口都没有**；单为自选加一套会是"一处实现、八处复制" |
| SSE `watchlist` 频道（§20.2） | WAT-01~WAT-05 未要求；M3-03 前端接入时一并评估 |
| AUTH-03 注册调用 `createDefaultGroup` | AUTH-03 不在 M3 清单；本轮已提供方法并在 seeder 中调用 |
| 幂等键的"同一键并发提交"严格互斥 | 当前实现是"读-执行-写"，两个并发同键请求可能都执行；契约只要求"重复提交返回第一次结果"，真正互斥需要 Redis 锁。记入 `PROJECT_STATUS.md` 已知问题 |
| `Idempotency-Key` 的 `save` 失败导致键丢失 | 取舍见 §3.5；键丢失只会退化为"可能重复创建"，不会伪造成功结果 |

## 7. 验收结果

### 7.1 测试

| 模块 | 用例数 | 说明 |
| --- | --- | --- |
| stock-common | 1 | 未改动 |
| stock-system | **45**（原 8） | 新增 37：分组名 6 + 用例层 20 + 幂等守卫 5 + 仓储映射 6 |
| stock-market | 156 | 未改动 |
| stock-integration | 106 | 未改动 |
| stock-backend | **116**（原 83） | 新增 33：接口契约 18 + 真实 MySQL 仓储 10 + 游客鉴权 1，另 4 为 `BackendConfigurationTest` 的新增装配断言等 |
| stock-job | 2 | 未改动 |
| **后端合计** | **426** | |

前端本轮未改动，仍复跑确认：`npm run typecheck` 0 错误；`vitest --configLoader runner --run` 通过；
`vite build --configLoader runner` 成功。

> **surefire 对 `@Nested` 的报数怪癖**：加了内层类之后，控制台会同时出现
> `InfrastructureIntegrationTest: Tests run: 0` 与
> `InfrastructureIntegrationTest$WatchlistGroups: Tests run: 15` 两行，看上去像外层 5 个用例没跑。
> 以 `target/surefire-reports/TEST-*.xml` 里的 `<testcase>` 为准：5（外层）+ 10（内层）= 15，一个都没少。
> **统计总数不要用控制台的分行数字相加。**

### 7.2 红灯记录

| 阶段 | 红灯内容 | 性质 |
| --- | --- | --- |
| 分组名 / 用例层 / 幂等守卫 | 31 个用例、30 个失败，全部 `UnsupportedOperationException: 尚未实现` | 真实断言失败（不是编译失败） |
| 仓储的合并顺序 | `movingItemsMergesTheTargetGroupsDuplicatesFirst` → `Wanted but not invoked: deleteItemsAlreadyInTarget` | 真实 mock 校验失败 |
| 真实 MySQL | 合并用例在补上"先删重复再搬"之前会撞 `uk_watchlist_item_group_security` | 真实数据库约束 |

### 7.3 真实 MySQL 8.4 实测结论

本机 Docker 可用（`mysql:8.4` / `redis:8.2-alpine` 已在本地镜像里），
因此下面这些不是"CI 会覆盖"，而是**本轮实测通过**：

| 结论 | 用例 |
| --- | --- |
| 软删后同名可重建（生成列 `active_group_name` 在软删后变 `NULL`） | `allowsReusingTheNameOfASoftDeletedGroup` |
| 唯一索引大小写不敏感（`utf8mb4_general_ci`）：`"My Group"` 与 `"my group"` 冲突 | `rejectsADuplicateActiveNameEvenWhenOnlyTheCasingDiffers` |
| 每个用户只能有一个有效默认分组（生成列 `default_owner_id`） | `rejectsASecondActiveDefaultGroupForTheSameUser` |
| 条件更新真的只影响一行；换成别人的 `user_id` 影响 0 行 | `conditionalRenameOnlyAppliesForTheCurrentVersionAndTheRightOwner`、`softDeleteIsAlsoVersionGuarded` |
| 重排把 `sortNo` 写成 0..n-1，且每个分组都 +1 版本 | `reorderRewritesSortNumbersAndBumpsEveryVersion` |
| 搬移时目标组已有的同证券被合并掉，`movedItemCount` 只数真正搬过去的 | `movingItemsMergesTheOnesAlreadyPresentInTheTarget` |
| 数据库 CHECK 与应用层 `trim()` + `codePointCount` 判定一致（21 字符、全空白都被拒） | `theDatabaseItselfRejectsNamesTheApplicationWouldAlsoReject` |
| seeder 跑完之后 demo 账号恰好一个默认分组 | `theSeededDemoAccountStartsWithExactlyOneDefaultGroup` |

### 7.4 实施中发现、spec 初稿没写到的三件事

1. **`@MapperScan` 原本写死 `cn.zhishi.stock.system.auth`**，`WatchlistGroupMapper` 不会成为 Bean，
   整个应用上下文起不来。已放宽为 `cn.zhishi.stock.system` + `annotationClass = Mapper.class`（见 §3.12）。
2. **`stock-system` 原本没有 Jackson**，`IdempotencyGuard` 编译不过。
   按 `stock-market` 已有的先例显式声明 `spring-boot-starter-json`，而不是蹭别处的传递依赖。
3. **`created_at` 不读回应用层**：`DEFAULT CURRENT_TIMESTAMP(3)` 的字面值取决于 MySQL 会话时区
   （compose 里是 `Asia/Shanghai`，Testcontainers 里是容器默认值），读回来再换算**必然有一处是错的**。
   因此 WAT-02 的 `createdAt` 取自应用 `Clock`，表里的列只作审计用途。
   这一条从设计上排除了整类"差 8 小时"的缺陷，也省掉了一次 `OffsetDateTime` 与
   Connector/J 之间不确定的类型往返。

### 7.5 未关闭的已知问题

- 幂等键的"同一键并发提交"不是严格互斥（读—执行—写的窗口内两个请求可能都执行）。
  契约只要求"重复提交返回第一次结果"，真正互斥需要额外的 Redis 锁。
- `Idempotency-Key` 落键失败（Redis 抖动）时接口返回 500 而业务已成功，退化为"可能重复创建"。
  取舍理由见 §3.5。

