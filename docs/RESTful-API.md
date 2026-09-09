# AI 智能股票分析平台 RESTful API 设计

## 1. 文档信息

| 项目 | 内容 |
| --- | --- |
| 项目名称 | AI 智能股票分析平台 |
| 文档版本 | V1.0 |
| API 版本 | V1 |
| 适用范围 | 桌面 Web、后台管理端、行情 WebSocket、AI SSE |
| 服务端 | Spring Boot、Spring Security、MyBatis-Plus、MySQL、Redis |
| 客户端 | Vue3、TypeScript |
| 业务时区 | `Asia/Shanghai` |
| 依据 | 产品 PRD、系统架构文档、Flyway V1-V7 数据库结构 |

本文用于产品、前端、后端和测试团队进行接口评审、联调与测试用例拆分。接口覆盖游客、注册用户和系统管理员三类角色。MVP 聚焦 A 股，国内外指数仅作为市场环境参考。

## 2. 设计原则

1. API 使用资源名词和 HTTP 方法表达语义，基础前缀统一为 `/api/v1`。
2. 对取消、重试、健康检查、人工触发任务等无法自然表达为 CRUD 的行为，使用显式动作端点。
3. 行情页面先通过 REST 获取完整快照，再通过 WebSocket 接收增量变化。
4. AI 分析先创建异步任务，再通过 SSE 接收状态与文本片段；最终报告以 MySQL 记录为准。
5. 面向用户的查询接口不得直接调用第三方行情或新闻 API，第三方数据由后台任务采集、标准化后进入 Redis/MySQL。
6. 用户自选、AI 会话、报告和反馈必须同时校验登录身份与资源所有权，禁止仅依赖前端隐藏。
7. 所有行情、新闻和 AI 结果均返回数据时间或数据截止时间，不将缓存数据无条件标记为实时。
8. AI 只提供研究辅助内容，不提供确定性买卖指令、收益承诺或自动交易能力。

## 3. 全局协议

### 3.1 基础地址与内容类型

| 环境 | 示例地址 |
| --- | --- |
| 本地开发 | `http://localhost/api/v1` |
| 测试环境 | `https://test.example.com/api/v1` |
| 生产环境 | `https://example.com/api/v1` |

- JSON 请求使用 `Content-Type: application/json; charset=UTF-8`。
- JSON 响应使用 `Content-Type: application/json; charset=UTF-8`。
- AI 流式响应使用 `Content-Type: text/event-stream; charset=UTF-8`。
- Excel 下载使用 `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`。
- WebSocket 入口使用 `/ws/v1/market`，不属于 `/api/v1` JSON 响应体系。

### 3.2 认证与权限

| 访问级别 | 说明 |
| --- | --- |
| `PUBLIC` | 游客可访问，按 IP 限流；登录用户携带 Token 后可附带个人状态 |
| `USER` | 必须携带有效 Access JWT |
| `ADMIN` | 必须携带有效 Access JWT，并通过对应 `perms` 权限校验 |

登录接口在 JSON 中返回短期 Access JWT；Refresh Token 仅作为不可读随机令牌，通过 `HttpOnly + Secure + SameSite=Strict` Cookie 下发，其会话保存在 Redis。Access JWT 放入请求头：

```http
Authorization: Bearer <accessToken>
```

安全规则：

- 禁止通过 URL Query 传递 Access JWT、Refresh Token 或第三方密钥。
- 前端只把 Access Token 保存在内存，不写入 `localStorage`；前端 JavaScript 无法读取 Refresh Token Cookie。
- Access Token 默认有效期 2 小时，Refresh Session 最长 7 天。
- Refresh、Logout 等使用 Cookie 的认证端点必须校验受信任 Origin；Refresh Cookie 的 Path 限定为 `/api/v1/auth`。
- 用户停用、密码变更、角色权限变更后，通过 `tokenVersion` 使旧会话失效。
- 前台接口不返回密码密文、验证码、Token 哈希、Provider 密钥引用、完整 AI 上下文或未脱敏日志参数。

### 3.3 通用请求头

| 请求头 | 必填 | 示例 | 说明 |
| --- | --- | --- | --- |
| `Authorization` | 条件必填 | `Bearer eyJ...` | `USER`、`ADMIN` 接口必填 |
| `X-Request-Id` | 建议 | UUID | 客户端请求标识，用于追踪；缺失时服务端生成 |
| `Idempotency-Key` | 条件必填 | UUID | 注册、AI 任务、导出任务、人工调度等防重复接口必填 |
| `Accept-Language` | 否 | `zh-CN` | MVP 仅返回中文 |
| `If-Match` | 条件必填 | `"3"` | 对含 `version` 的并发更新资源提交当前版本号 |

### 3.4 字段与数据类型约定

| 类型 | API 约定 |
| --- | --- |
| ID | MySQL `BIGINT`/Snowflake ID 在 JSON 中统一返回字符串，例如 `"19876543210001"` |
| 日期时间 | ISO 8601，带上海时区偏移，例如 `2026-09-08T14:30:00.123+08:00` |
| 日期 | `yyyy-MM-dd` |
| 金额、价格、比例 | 十进制定点数字符串，避免浮点误差，例如 `"12.35"`、`"0.102300"` |
| 成交量 | 字符串，例如 `"125009800"`，单位由字段或 `unit` 明确 |
| 布尔值 | JSON `true`/`false` |
| 枚举 | 大写英文枚举，未知值不得自动映射为其他业务状态 |
| 空值 | 不存在或不可用返回 `null`，不使用 `0`、空字符串伪装有效数据 |

所有比例字段使用小数比例，`"0.10"` 表示 `10%`。成交量统一以“股”为单位，成交金额统一以“人民币元”为单位，指数接口可按具体市场返回 `currencyCode`。

### 3.5 分页、排序与筛选

通用分页请求参数：

| 参数 | 类型 | 必填 | 默认值 | 规则 |
| --- | --- | --- | --- | --- |
| `page` | integer | 否 | `1` | 从 1 开始，最小值 1 |
| `size` | integer | 否 | `20` | 普通接口最大 100；榜单最大 200 |
| `sort` | string | 否 | 由接口定义 | 格式 `field,asc` 或 `field,desc`，仅允许白名单字段 |

分页响应数据 `PageData<T>`：

```json
{
  "items": [],
  "page": 1,
  "size": 20,
  "total": 125,
  "totalPages": 7,
  "hasNext": true
}
```

新闻、日志、AI 历史等时间序列列表必须使用“业务时间倒序 + ID 倒序”保证翻页稳定。客户端不得传递原始 SQL 字段或 SQL 排序片段。

### 3.6 数据状态

行情类响应统一包含：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `dataTime` | datetime | 该行情本身对应的时间 |
| `serverTime` | datetime | API 响应生成时间 |
| `sequence` | string | 当前主题/快照序列号 |
| `dataStatus` | enum | `REALTIME`、`DELAYED`、`STALE`、`UNAVAILABLE` |
| `sourceCode` | string/null | 内部数据来源编码，不包含密钥 |
| `delaySeconds` | integer/null | 服务端时间与数据时间的差值 |

交易时段核心行情超过 60 秒后不得继续返回 `REALTIME`。资讯响应必须区分 `publishedAt`、`collectedAt` 和平台最近成功同步时间。

### 3.7 幂等与并发

- `Idempotency-Key` 在同一用户和业务范围内唯一，建议使用 UUID，有效窗口默认 24 小时。
- 相同幂等键与相同请求体重复提交时，返回第一次业务结果；相同幂等键对应不同请求体时返回 `IDEMPOTENCY_KEY_CONFLICT`。
- 自选分组、用户、角色、权限等包含 `version` 的资源更新使用乐观锁。版本不匹配返回 HTTP 409。
- AI 任务由数据库 `request_id` 唯一约束兜底，重复创建不得重复消耗大模型额度。
- 批量排序请求必须一次提交完整目标顺序，由服务端在一个本地事务内完成。

### 3.8 缓存与条件请求

- 证券主数据、历史 K 线和新闻详情可返回 `ETag`，客户端可通过 `If-None-Match` 获取 `304 Not Modified`。
- 实时行情接口不得使用浏览器长期缓存，可返回 `Cache-Control: no-store`。
- 公共证券搜索、板块元数据可使用短期私有缓存，但权限相关响应禁止公共缓存。
- `304`、SSE、WebSocket 和二进制下载不使用统一 JSON 返回体。

## 4. 统一业务对象

### 4.1 `SecuritySummary`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `securityId` | string | 证券主键 |
| `fullSymbol` | string | 全局代码，例如 `SH.600000` |
| `securityCode` | string | 交易所内代码 |
| `securityName` | string | 证券名称 |
| `exchangeCode` | string | `SH`、`SZ`、`BJ` |
| `securityType` | enum | `STOCK`、`ETF`、`INDEX` 等 |
| `boardCode` | string/null | 主板、科创板、创业板等规则板块 |
| `listingStatus` | enum | `LISTED`、`SUSPENDED`、`DELISTED`、`PRELISTED` |
| `isSt` | boolean | 是否 ST |
| `isSuspended` | boolean | 是否停牌 |
| `priceScale` | integer | 价格展示精度 |

### 4.2 `QuoteSnapshot`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `security` | `SecuritySummary` | 证券摘要 |
| `previousClosePrice` | decimal-string/null | 前收价 |
| `openPrice` | decimal-string/null | 开盘价 |
| `latestPrice` | decimal-string/null | 最新价 |
| `highPrice` | decimal-string/null | 最高价 |
| `lowPrice` | decimal-string/null | 最低价 |
| `changeAmount` | decimal-string/null | 涨跌额 |
| `changeRate` | decimal-string/null | 涨跌幅，小数比例 |
| `tradeVolume` | integer-string/null | 成交量，股 |
| `tradeAmount` | decimal-string/null | 成交额，元 |
| `turnoverRate` | decimal-string/null | 换手率 |
| `dataTime` | datetime/null | 行情时间 |
| `serverTime` | datetime | 服务时间 |
| `sequence` | string | 快照序列号 |
| `dataStatus` | enum | 数据状态 |
| `delaySeconds` | integer/null | 延迟秒数 |

### 4.3 `NewsSummary`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `newsId` | string | 资讯主键，重复稿返回主记录 ID |
| `newsType` | enum | `NEWS`、`ANNOUNCEMENT`、`RESEARCH`、`OTHER` |
| `title` | string | 标题 |
| `summary` | string/null | 授权范围内摘要 |
| `sourceName` | string | 实际内容来源 |
| `authorName` | string/null | 作者 |
| `publishedAt` | datetime | 来源发布时间 |
| `collectedAt` | datetime | 平台采集时间 |
| `originalUrl` | string | 经协议和安全校验的原文地址 |
| `originalAccessStatus` | enum | `AVAILABLE`、`UNAVAILABLE`、`UNKNOWN` |
| `relations` | array | 确认的证券、板块或市场关联摘要 |

### 4.4 `AiTaskSummary`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `taskId` | string | AI 任务 ID |
| `sessionId` | string | 会话 ID |
| `scene` | enum | `MARKET`、`SECTOR`、`STOCK`、`STOCK_RISK`、`COMPARE` |
| `status` | enum | `CREATED`、`PREPARING`、`QUEUED`、`RUNNING`、`VALIDATING`、`COMPLETED`、`CANCELED`、`FAILED`、`TIMED_OUT` |
| `targets` | array | 任务目标摘要，包含目标类型、ID、代码、名称和角色 |
| `question` | string/null | 用户问题 |
| `progressStage` | string | 面向用户的阶段说明 |
| `createdAt` | datetime | 创建时间 |
| `firstChunkAt` | datetime/null | 首段产生时间 |
| `completedAt` | datetime/null | 完成时间 |
| `reportId` | string/null | 成功完成后的报告 ID |
| `error` | object/null | 失败类别、可展示错误码和重试建议 |

## 5. 用户认证模块

### 5.1 接口清单

| 编号 | 请求方式 | URL | 权限 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| AUTH-01 | `POST` | `/auth/captchas` | `PUBLIC` | Body：`purpose`，取值 `LOGIN`、`REGISTER`、`RESET_PASSWORD` | `captchaId`、`imageBase64`、`expiresInSeconds` | 创建一次性图形验证码。响应和日志均不返回验证码答案 |
| AUTH-02 | `POST` | `/auth/verification-codes` | `PUBLIC` | Header：`Idempotency-Key`；Body：`purpose`、`email`、`captchaId`、`captchaAnswer` | `verificationId`、`maskedDestination`、`expiresInSeconds`、`resendAfterSeconds` | 发送注册或找回密码邮件验证码；验证码哈希保存在 Redis |
| AUTH-03 | `POST` | `/auth/register` | `PUBLIC` | Header：`Idempotency-Key`；Body：`username`、`password`、`email`、`verificationId`、`verificationCode`、`agreementAccepted` | `userId`、`username`、`createdAt`；可选 `accessToken` 和有效期；Refresh Token 通过 `Set-Cookie` 返回 | 用户自助注册；创建默认自选分组；账号、有效邮箱均须唯一 |
| AUTH-04 | `POST` | `/auth/login` | `PUBLIC` | Body：`account`、`password`、条件触发的 `captchaId`、`captchaAnswer`、`deviceName` | `accessToken`、`accessExpiresInSeconds`、`refreshExpiresInSeconds`、`user`、`permissions`；Refresh Token 通过 `Set-Cookie` 返回 | 支持用户名或邮箱登录；失败次数达到阈值后要求验证码或临时锁定 |
| AUTH-05 | `POST` | `/auth/token/refresh` | `PUBLIC` | Cookie：`refresh_token`；Body：可选 `deviceName` | 新 `accessToken`、两个有效期；轮换后的 Refresh Token 通过 `Set-Cookie` 返回 | 轮换 Refresh Token；旧令牌使用后立即失效，检测重放时撤销对应会话族 |
| AUTH-06 | `POST` | `/auth/logout` | `USER` | Cookie：`refresh_token`；Body：`allDevices=false` | `loggedOut`、`revokedSessionCount`；响应清除 Refresh Cookie | 注销当前刷新会话；Access Token JTI 在剩余有效期内加入 Redis 黑名单 |
| AUTH-07 | `POST` | `/auth/password/reset` | `PUBLIC` | Header：`Idempotency-Key`；Body：`email`、`verificationId`、`verificationCode`、`newPassword` | `reset`、`revokedSessionCount` | 找回并重置密码；成功后递增 `tokenVersion` 并撤销全部刷新会话 |
| AUTH-08 | `GET` | `/auth/session-status` | `USER` | 无 | `authenticated`、`userId`、`tokenExpiresAt`、`tokenVersionValid` | 前端恢复页面时验证当前 Access Token，不延长令牌有效期 |

### 5.2 业务规则与异常

- 用户名长度 4 至 50，仅允许字母、数字、下划线；邮箱按标准格式校验并规范化为小写。
- 密码长度 8 至 64，必须至少包含字母和数字，服务端使用 BCrypt 保存密文。
- 验证码有效期 5 至 10 分钟，验证成功后立即删除，不允许重复使用。
- 为防止账号枚举，发送验证码和找回密码时，无论邮箱是否存在，外部提示保持一致。
- 常见异常：`CAPTCHA_INVALID`、`VERIFICATION_CODE_INVALID`、`VERIFICATION_CODE_EXPIRED`、`ACCOUNT_ALREADY_EXISTS`、`ACCOUNT_LOCKED`、`CREDENTIALS_INVALID`、`REFRESH_TOKEN_INVALID`、`REFRESH_TOKEN_REUSED`。

## 6. 当前用户与个人资料模块

| 编号 | 请求方式 | URL | 权限 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| USER-01 | `GET` | `/users/me` | `USER` | 无 | `userId`、`username`、`maskedPhone`、`realName`、`nickName`、`maskedEmail`、`sex`、`status`、`createdAt`、`lastLoginTime`、`version` | 获取当前用户资料；敏感联系方式默认脱敏 |
| USER-02 | `PATCH` | `/users/me` | `USER` | Header：`If-Match`；Body：可选 `nickName`、`realName`、`sex` | 更新后的个人资料与新 `version` | 局部修改资料；空字符串按字段规则规范化为 `null` |
| USER-03 | `PUT` | `/users/me/password` | `USER` | Body：`currentPassword`、`newPassword` | `changed`、`revokedSessionCount` | 修改密码；成功后撤销除本次响应外的所有会话，并要求重新登录 |
| USER-04 | `GET` | `/users/me/permissions` | `USER` | 无 | `roles`、`permissionCodes`、`menus`、`tokenVersion` | 返回当前用户路由、菜单和按钮权限；后端仍独立鉴权 |
| USER-05 | `GET` | `/users/me/sessions` | `USER` | 无 | 会话数组：`sessionId`、`deviceName`、`createdAt`、`lastSeenAt`、`expiresAt`、`current` | 查看 Redis 中仍有效的刷新会话，不返回 Refresh Token |
| USER-06 | `DELETE` | `/users/me/sessions/{sessionId}` | `USER` | Path：`sessionId` | `revoked` | 撤销本人指定设备会话；访问他人会话统一按资源不存在处理 |
| USER-07 | `GET` | `/users/me/ai-quota` | `USER` | 无 | `date`、`dailyLimit`、`usedCount`、`remainingCount`、`runningCount`、`concurrentLimit`、`resetsAt` | 返回 AI 当日配额与并发占用；实际 Provider 失败不计成功次数 |

常见异常：`USER_NOT_FOUND`、`CURRENT_PASSWORD_INCORRECT`、`PROFILE_VERSION_CONFLICT`、`SESSION_NOT_FOUND`、`ACCOUNT_DISABLED`。

## 7. 市场总览模块

### 7.1 接口清单

| 编号 | 请求方式 | URL | 权限 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| MKT-01 | `GET` | `/markets/overview` | `PUBLIC` | Query：`market=CN` | `marketStatus`、`tradeDate`、`domesticIndices`、`overseasIndices`、`breadth`、`limitStatistics`、`turnoverTrend`、`hotSectors`、`dataTime`、`dataStatus` | 市场首页聚合接口，所有子数据携带各自截止时间；聚合失败时允许部分返回 |
| MKT-02 | `GET` | `/markets/{marketCode}/status` | `PUBLIC` | Path：`marketCode`，MVP 为 `CN`；Query：可选 `date` | `marketCode`、`tradeDate`、`isTradingDay`、`sessionStatus`、`currentSession`、`nextSessionAt`、`calendarSourceTime` | 查询开市、集合竞价、连续竞价、午间休市、收盘状态 |
| MKT-03 | `GET` | `/markets/{marketCode}/breadth` | `PUBLIC` | Query：可选 `snapshotTime` | `riseCount`、`fallCount`、`flatCount`、`suspendedCount`、`limitUpCount`、`limitDownCount`、`totalCount`、行情数据状态字段 | 返回同一快照口径的市场广度，不混用不同批次行情 |
| MKT-04 | `GET` | `/markets/{marketCode}/turnover-trend` | `PUBLIC` | Query：`range`，取值 `TODAY`、`5D`、`20D`；可选 `interval` | 点位数组：`time`、`tradeAmount`、`tradeVolume`；另含 `unit`、`dataCutoffAt` | 返回市场成交量额趋势；盘中以分钟聚合，跨日以日维度聚合 |
| MKT-05 | `GET` | `/market-indices` | `PUBLIC` | Query：`region=DOMESTIC\|OVERSEAS\|ALL`、可选 `marketCode`、`keyword` | 指数摘要数组：`indexId`、`indexCode`、`indexName`、`region`、`currencyCode`、最新点位和数据状态 | 获取国内外指数列表；国内外指数可来自不同数据时间 |
| MKT-06 | `GET` | `/market-indices/{indexId}/quotes/latest` | `PUBLIC` | Path：`indexId` | `indexId`、`indexCode`、`indexName`、`previousClosePoint`、`openPoint`、`latestPoint`、`highPoint`、`lowPoint`、`changeAmount`、`changeRate`、成交量额、行情数据状态字段 | 获取单一指数最新快照；指数不存在返回 404 |
| MKT-07 | `GET` | `/market-indices/{indexId}/klines` | `PUBLIC` | Query：`period=MINUTE\|DAY\|WEEK\|MONTH`、`startDate`、`endDate`；分钟周期可传 `interval=1m\|5m\|15m\|30m\|60m` | `index`、`period`、`adjustment=NONE`、K 线点数组、`dataCutoffAt` | 查询指数历史走势；时间跨度按周期限制，周/月由日数据聚合 |
| MKT-08 | `GET` | `/trade-calendars` | `PUBLIC` | Query：`exchangeCode`、`startDate`、`endDate`，跨度最多 366 天 | 日历数组：`tradeDate`、`isTradingDay`、`previousTradeDate`、`nextTradeDate`、`sessions` | 查询交易日历，供日期选择器和行情口径说明使用 |

### 7.2 聚合与降级规则

- `markets/overview` 以组件级状态返回部分结果。指数成功但板块失败时 HTTP 仍为 200，失败组件在 `componentStatus` 中标为 `UNAVAILABLE`。
- 全部核心行情均不可用时返回 HTTP 503 和 `MARKET_DATA_UNAVAILABLE`，不得返回全零数据。
- 非交易日返回最近有效收盘快照，并将 `marketStatus.sessionStatus` 标记为 `CLOSED`，不视为数据延迟。
- 交易时段数据超过 60 秒时返回 `DELAYED` 或 `STALE`，同时提供 `lastSuccessfulSyncAt`。

## 8. 股票与证券模块

### 8.1 接口清单

| 编号 | 请求方式 | URL | 权限 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| STK-01 | `GET` | `/securities/search` | `PUBLIC` | Query：`q`，1 至 50 字符；可选 `types`、`exchangeCodes`、`limit`，最大 20 | `items`：`SecuritySummary`、`matchedField`、`highlight` | 按证券代码、名称、拼音和拼音首字母搜索；只返回允许展示的证券状态 |
| STK-02 | `GET` | `/securities` | `PUBLIC` | Query：`keyword`、`securityType`、`exchangeCode`、`boardCode`、`listingStatus`、`sectorId`、分页排序 | `PageData<SecuritySummary>` | 证券主数据列表，适用于筛选器和管理型选择组件，不替代实时榜单 |
| STK-03 | `GET` | `/securities/{securityId}` | `PUBLIC` | Path：`securityId` | `SecuritySummary` 扩展字段：`listedDate`、`delistedDate`、`lotSize`、`sourceUpdatedAt`、`primarySector` | 获取证券基础资料；退市证券允许查看历史，但明确展示状态 |
| STK-04 | `GET` | `/securities/{securityId}/quote` | `PUBLIC` | Path：`securityId` | `QuoteSnapshot`，登录时附加 `watchlistState` | 个股详情头部完整快照；缓存缺失时从 MySQL 最近有效数据降级 |
| STK-05 | `POST` | `/quotes/securities/batch-query` | `PUBLIC` | Body：`securityIds`，去重后 1 至 50 个 | `items: QuoteSnapshot[]`、`missingSecurityIds`、`snapshotVersion` | 批量查询自选、板块成分股等首屏行情；不保证不同证券源时间完全相同，但返回统一批次版本 |
| STK-06 | `GET` | `/securities/{securityId}/intraday` | `PUBLIC` | Query：可选 `tradeDate`、`interval=1m\|5m\|15m\|30m\|60m` | `security`、`tradeDate`、`previousClosePrice`、分时点数组、`dataCutoffAt`、`dataStatus` | 返回分时 OHLCV；当日盘中数据包含已闭合分钟，不返回未完成分钟伪 K 线 |
| STK-07 | `GET` | `/securities/{securityId}/klines` | `PUBLIC` | Query：`period=DAY\|WEEK\|MONTH`、`startDate`、`endDate`、`adjustment=NONE\|FORWARD\|BACKWARD` | `security`、`period`、`adjustment`、K 线点数组、`dataCutoffAt`、`dataStatus` | 查询历史 K 线；MVP 默认 `NONE`，不支持的复权方式返回明确错误而非静默替换 |
| STK-08 | `GET` | `/securities/{securityId}/business-profile` | `PUBLIC` | Path：`securityId` | `securityId`、`businessDescription`、`primarySector`、`updatedAt`、`dataStatus` | 获取主营业务和主要行业；资料缺失时返回字段为 `null`，AI 不得自行补全 |
| STK-09 | `GET` | `/securities/{securityId}/sectors` | `PUBLIC` | Query：可选 `sectorType`、`effectiveDate` | 板块数组：`sectorId`、`sectorCode`、`sectorName`、`sectorType`、`relationType`、`isPrimary`、生效区间 | 获取证券所属行业、概念和地域板块，默认查询当前有效关系 |
| STK-10 | `GET` | `/securities/{securityId}/news` | `PUBLIC` | Query：`newsType`、`startAt`、`endAt`、分页 | `PageData<NewsSummary>`、`lastSuccessfulSyncAt`、`dataStatus` | 获取与股票确认关联且授权可展示的资讯；候选或已拒绝关系不返回 |

### 8.2 K 线点结构

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `time` | datetime/date | 分钟时间或交易日 |
| `openPrice` | decimal-string | 开盘价 |
| `highPrice` | decimal-string | 最高价 |
| `lowPrice` | decimal-string | 最低价 |
| `closePrice` | decimal-string | 收盘价 |
| `previousClosePrice` | decimal-string/null | 前收价，日 K 可用 |
| `changeAmount` | decimal-string/null | 涨跌额 |
| `changeRate` | decimal-string/null | 涨跌幅 |
| `tradeVolume` | integer-string | 成交量，股 |
| `tradeAmount` | decimal-string | 成交金额，元 |
| `turnoverRate` | decimal-string/null | 换手率 |
| `qualityStatus` | enum | `VALID`、`DELAYED`、`CORRECTED` |

### 8.3 参数限制与异常

- 分钟 K 单次最多查询 5 个交易日；日 K 最多 10 年；周/月 K 最多 20 年。
- `securityId` 是系统稳定主键。不得把 `securityCode` 单独作为详情资源标识，因为不同交易所可能存在相同代码空间。
- 停牌证券返回最近有效价格和 `isSuspended=true`，不得将价格置零。
- 常见异常：`SECURITY_NOT_FOUND`、`SECURITY_NOT_LISTED`、`QUOTE_NOT_AVAILABLE`、`KLINE_RANGE_TOO_LARGE`、`ADJUSTMENT_NOT_SUPPORTED`、`MARKET_DATA_STALE`。

## 9. 行情榜单与导出模块

### 9.1 榜单接口

| 编号 | 请求方式 | URL | 权限 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| QTE-01 | `GET` | `/stock-rankings` | `PUBLIC` | Query：`rankingType=GAINERS\|LOSERS\|TURNOVER`、可选 `exchangeCodes`、`boardCodes`、`sectorId`、`excludeSt`、`excludeSuspended`、`page`、`size` | `PageData<QuoteSnapshot>`、`rankingType`、`snapshotVersion`、`dataTime`、`dataStatus` | 获取涨幅榜、跌幅榜或成交额榜；整个榜单使用同一已完成快照版本 |
| QTE-02 | `GET` | `/stock-rankings/limit-up` | `PUBLIC` | Query：可选交易所、板块、分页 | `PageData<QuoteSnapshot>`、`ruleDate`、`snapshotVersion`、`dataTime` | 返回按证券、板块、ST 和上市天数规则计算的涨停股票，不使用固定 10% 粗略判断 |
| QTE-03 | `GET` | `/stock-rankings/limit-down` | `PUBLIC` | Query：可选交易所、板块、分页 | `PageData<QuoteSnapshot>`、`ruleDate`、`snapshotVersion`、`dataTime` | 返回跌停股票，计算口径与涨跌停规则表一致 |
| QTE-04 | `GET` | `/stock-rankings/options` | `PUBLIC` | 无 | 可用榜单类型、交易所、板块类型、允许排序字段和默认条件 | 为前端筛选器提供受控枚举，不允许客户端自行构造字段名 |

### 9.2 异步导出接口

| 编号 | 请求方式 | URL | 权限 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| EXP-01 | `POST` | `/export-jobs` | `USER` | Header：`Idempotency-Key`；Body：`exportType=STOCK_RANKING\|AI_REPORT`、`filters`、`columns`；AI 报告需传 `reportId` | HTTP 202；`exportId`、`status=QUEUED`、`createdAt`、`expiresAt` | 创建导出任务；榜单最多 5,000 行；只允许导出白名单字段 |
| EXP-02 | `GET` | `/export-jobs/{exportId}` | `USER` | Path：`exportId` | `exportId`、`exportType`、`status`、`progress`、`fileName`、`rowCount`、`expiresAt`、`error` | 查询本人导出任务状态；状态为 `QUEUED`、`RUNNING`、`COMPLETED`、`FAILED`、`EXPIRED` |
| EXP-03 | `GET` | `/export-jobs/{exportId}/download` | `USER` | Path：`exportId` | 二进制文件；Header：`Content-Disposition`、`X-Data-Cutoff-At`、`X-Trace-Id` | 下载已完成文件；文件包含生成时间、数据截止时间、口径和免责声明 |
| EXP-04 | `DELETE` | `/export-jobs/{exportId}` | `USER` | Path：`exportId` | `deleted` | 删除本人临时导出文件和任务缓存；已过期时保持幂等成功 |

导出任务状态和短期文件可保存在 Redis 与受控 Docker Volume/对象存储中，默认 24 小时过期；`sys_log` 记录导出审计。当前数据库没有长期 `export_job` 表，因此 MVP 不承诺重启后恢复未完成导出任务，也不提供永久导出历史。若产品后续要求可靠恢复和长期历史，应通过 Flyway 新增导出任务表后再提升契约。

常见异常：`EXPORT_LIMIT_EXCEEDED`、`EXPORT_RATE_LIMITED`、`EXPORT_NOT_READY`、`EXPORT_EXPIRED`、`EXPORT_FAILED`、`FORMULA_INJECTION_BLOCKED`。

## 10. 板块分析模块

| 编号 | 请求方式 | URL | 权限 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| SEC-01 | `GET` | `/sectors` | `PUBLIC` | Query：`sectorType=INDUSTRY\|CONCEPT\|REGION`、可选 `parentId`、`keyword`、`status=ACTIVE` | 板块数组：`sectorId`、`sectorCode`、`sectorName`、`sectorType`、`parentId`、`levelNo` | 获取板块主数据；普通用户默认只能查询有效板块 |
| SEC-02 | `GET` | `/sector-rankings` | `PUBLIC` | Query：`sectorType`、`rankingType=GAINERS\|LOSERS\|TURNOVER`、分页 | 板块行情分页：板块摘要、均价、涨跌幅、公司数、成交量额、领涨股、数据状态字段 | 返回同一快照的行业、概念或地域板块排行 |
| SEC-03 | `GET` | `/sectors/{sectorId}` | `PUBLIC` | Path：`sectorId` | 板块主数据、父级板块、当前统计、数据状态字段 | 获取板块详情头部；已停用板块可返回历史状态但不进入当前排行 |
| SEC-04 | `GET` | `/sectors/{sectorId}/quote` | `PUBLIC` | Path：`sectorId` | `averagePrice`、`changeRate`、`companyCount`、`tradeVolume`、`tradeAmount`、领涨/领跌股、数据状态字段 | 获取板块最新行情统计 |
| SEC-05 | `GET` | `/sectors/{sectorId}/trend` | `PUBLIC` | Query：`period=MINUTE\|DAY`、`startDate`、`endDate`、分钟 `interval` | 走势点数组、成分口径说明、`dataCutoffAt`、`dataStatus` | 基于历史板块快照或当前有效成分股聚合返回走势，并明确采用的成分口径 |
| SEC-06 | `GET` | `/sectors/{sectorId}/constituents` | `PUBLIC` | Query：可选 `effectiveDate`、`rankingType`、分页 | `PageData<QuoteSnapshot>`；每项附 `relationType`、`isPrimary`、`contributionRank` | 获取指定日期有效成分股；不使用名称模糊匹配代替正式成分关系 |
| SEC-07 | `GET` | `/sectors/{sectorId}/news` | `PUBLIC` | Query：`newsType`、时间范围、分页 | `PageData<NewsSummary>`、`lastSuccessfulSyncAt`、`dataStatus` | 获取与板块确认关联的资讯；个股资讯不会因单一成分关系自动泛化为板块事实 |

常见异常：`SECTOR_NOT_FOUND`、`SECTOR_INACTIVE`、`SECTOR_CONSTITUENTS_MISSING`、`SECTOR_QUOTE_NOT_AVAILABLE`、`SECTOR_TREND_NOT_AVAILABLE`。

## 11. 新闻与公告模块

### 11.1 前台接口

| 编号 | 请求方式 | URL | 权限 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| NEWS-01 | `GET` | `/news` | `PUBLIC` | Query：`newsTypes`、`securityId`、`sectorId`、`marketCode`、`startAt`、`endAt`、`keyword`、分页 | `PageData<NewsSummary>`、`lastSuccessfulSyncAt`、`dataStatus` | 资讯中心列表；默认仅返回主记录、已发布内容和已确认关联，按发布时间倒序 |
| NEWS-02 | `GET` | `/news/{newsId}` | `PUBLIC` | Path：`newsId` | `NewsSummary` 扩展：`languageCode`、版权/授权提示、所有确认关联、`rightsExpireAt` | 获取授权范围内详情，不返回未经授权的完整正文 |
| NEWS-03 | `GET` | `/news/sync-status` | `PUBLIC` | 无 | `overallStatus`、`lastSuccessfulSyncAt`、`delaySeconds`、`availableSourceCount`、`failedSourceCount` | 返回聚合后的公开资讯状态，不泄露 Provider 内部错误或配置 |
| NEWS-04 | `GET` | `/news/options` | `PUBLIC` | 无 | 资讯类型、来源类型、可用时间范围和筛选规则 | 为前端提供受控筛选选项 |

### 11.2 展示与授权规则

- 仅返回 `contentStatus=PUBLISHED` 且授权有效的内容；撤稿保留元数据时必须显著标记，不继续展示摘要。
- `dedupStatus=DUPLICATE` 的稿件默认折叠到 `canonicalNewsId`，避免同一事件重复刷屏。
- 低置信候选关联不进入普通列表和 AI 证据，只有 `relationStatus=CONFIRMED` 可用。
- 原文 URL 仅允许 `https` 等白名单协议，前端新窗口打开并使用 `noopener noreferrer`。
- 资讯为空只表示当前授权数据范围内无结果，不得表达为“公司不存在风险或事件”。
- 常见异常：`NEWS_NOT_FOUND`、`NEWS_WITHDRAWN`、`NEWS_RIGHTS_EXPIRED`、`NEWS_SOURCE_UNAVAILABLE`、`NEWS_DATA_DELAYED`。

## 12. 自选中心模块

### 12.1 自选分组接口

| 编号 | 请求方式 | URL | 权限 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| WAT-01 | `GET` | `/watchlist-groups` | `USER` | Query：可选 `includeItems=false` | 分组数组：`groupId`、`groupName`、`sortNo`、`isDefault`、`itemCount`、`version`；可选 `items` | 获取当前用户有效分组，按 `sortNo`、`groupId` 排序 |
| WAT-02 | `POST` | `/watchlist-groups` | `USER` | Header：`Idempotency-Key`；Body：`groupName` | `groupId`、`groupName`、`sortNo`、`isDefault=false`、`version`、`createdAt` | 新建自定义分组；同一用户活动分组名称唯一 |
| WAT-03 | `PATCH` | `/watchlist-groups/{groupId}` | `USER` | Header：`If-Match`；Body：`groupName` | 更新后的分组和新 `version` | 重命名本人分组；默认分组允许改显示名，但仍保持默认属性 |
| WAT-04 | `DELETE` | `/watchlist-groups/{groupId}` | `USER` | Header：`If-Match`；Query：删除非空组时必填 `moveItemsToGroupId` | `deleted`、`movedItemCount` | 软删除自定义分组；默认分组不可删除；非空分组必须先指定目标组 |
| WAT-05 | `PUT` | `/watchlist-groups/order` | `USER` | Body：`groupIds`，必须包含本人全部有效分组且不重复 | 更新后的分组顺序与版本 | 原子更新分组顺序；缺项、重复或包含他人分组均拒绝 |

### 12.2 自选项接口

| 编号 | 请求方式 | URL | 权限 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| WAT-06 | `GET` | `/watchlist-groups/{groupId}/items` | `USER` | Query：可选 `includeQuote=true`、分页 | 自选项分页：`itemId`、`groupId`、`security`、`sortNo`、`version`、`createdAt`、可选 `quote`、`latestNewsCount` | 获取本人分组内证券及最新行情；行情不可用时仍返回自选关系 |
| WAT-07 | `POST` | `/watchlist-groups/{groupId}/items` | `USER` | Header：`Idempotency-Key`；Body：`securityId` | `itemId`、`groupId`、`security`、`sortNo`、`version`、`createdAt` | 添加股票到指定分组；同组同证券保持幂等，允许同一证券存在于不同分组 |
| WAT-08 | `DELETE` | `/watchlist-groups/{groupId}/items/{itemId}` | `USER` | Path：`groupId`、`itemId` | `deleted` | 从本人分组移除自选项；重复删除按幂等成功处理 |
| WAT-09 | `PATCH` | `/watchlist-groups/{groupId}/items/{itemId}` | `USER` | Header：`If-Match`；Body：`targetGroupId` | 更新后的 `itemId`、`groupId`、`sortNo`、`version` | 将自选项移动到本人其他分组；目标组已存在同证券时合并并返回 `merged=true` |
| WAT-10 | `PUT` | `/watchlist-groups/{groupId}/items/order` | `USER` | Body：`itemIds`，必须包含组内全部当前项且不重复 | 更新后的项顺序与版本 | 原子更新组内排序；并发变化时返回 409 并要求刷新 |
| WAT-11 | `GET` | `/watchlists/overview` | `USER` | Query：可选 `groupId`、`newsSince` | 分组摘要、自选行情数组、市场状态、每只证券最新资讯数、`snapshotVersion`、数据状态字段 | 自选中心首屏聚合；不因单只证券行情缺失导致全页失败 |
| WAT-12 | `GET` | `/watchlists/membership` | `USER` | Query：`securityIds`，1 至 50 个 | Map：每个 `securityId` 对应所在分组的 `groupId`、`groupName`、`itemId` | 用于榜单、板块和个股页批量回显“已自选”状态 |

### 12.3 业务规则与异常

- 注册完成后创建且仅创建一个默认分组；数据库唯一约束保证每个用户只有一个有效默认组。
- 分组名称去除首尾空白后长度 1 至 20 个字符，同一用户有效分组名不可重复。
- 所有路径资源都必须校验 `user_id`，对他人资源返回 `WATCHLIST_RESOURCE_NOT_FOUND`，避免泄露存在性。
- 添加退市证券允许保留历史关注，但新增时需返回状态提醒；不存在的证券不可加入。
- 常见异常：`WATCHLIST_GROUP_NOT_FOUND`、`WATCHLIST_GROUP_NAME_EXISTS`、`DEFAULT_GROUP_CANNOT_DELETE`、`WATCHLIST_ITEM_EXISTS`、`WATCHLIST_TARGET_GROUP_CONFLICT`、`WATCHLIST_VERSION_CONFLICT`。

## 13. AI 研究模块

### 13.1 AI 场景与上下文预览

| 编号 | 请求方式 | URL | 权限 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| AI-01 | `GET` | `/ai/scenes` | `USER` | 无 | 场景数组：`scene`、`name`、`description`、`allowedTargetTypes`、`minTargets`、`maxTargets`、`defaultRange`、`questionMaxLength` | 返回当前启用的市场、板块、个股、个股风险和多标的对比场景 |
| AI-02 | `POST` | `/ai/context-previews` | `USER` | Body：`scene`、`targets`、`analysisStartAt`、`analysisEndAt` | `targets`、可用数据类别、各类 `dataCutoffAt`、资讯条数、`limitations`、`canGenerate` | 在正式消耗配额前展示将使用的数据摘要；不返回完整内部 Prompt 或未授权正文 |

`targets` 请求元素：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `targetType` | enum | 是 | `MARKET`、`SECTOR`、`SECURITY` |
| `targetId` | string | 是 | 目标业务主键 |
| `targetRole` | enum | 是 | `PRIMARY`、`COMPARISON`；`CONTEXT` 仅允许服务端生成 |

场景目标规则：

| 场景 | 目标规则 |
| --- | --- |
| `MARKET` | 1 个 `MARKET` 主目标 |
| `SECTOR` | 1 个 `SECTOR` 主目标 |
| `STOCK` | 1 个 `SECURITY` 主目标 |
| `STOCK_RISK` | 1 个 `SECURITY` 主目标 |
| `COMPARE` | 2 至 3 个 `SECURITY` 目标，且仅 1 个 `PRIMARY` |

### 13.2 AI 任务接口

| 编号 | 请求方式 | URL | 权限 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| AI-03 | `POST` | `/ai/tasks` | `USER` | Header：`Idempotency-Key`；Body：`sessionId` 可选、`scene`、`targets`、`analysisStartAt`、`analysisEndAt`、`question` 可选 | HTTP 202；`AiTaskSummary`、`streamUrl`、`quota`；未传 `sessionId` 时同时返回新会话 ID | 校验权限、配额、目标和核心行情后创建 `CREATED/PREPARING` 任务，再通过 Outbox/Redis Stream 投递 |
| AI-04 | `GET` | `/ai/tasks/{taskId}` | `USER` | Path：`taskId` | `AiTaskSummary`，完成时包含 `reportId`，运行时包含最新进度 | 查询本人任务最终或当前状态；Redis 缺失时回退 MySQL 状态 |
| AI-05 | `GET` | `/ai/tasks/{taskId}/stream` | `USER` | Header：`Accept: text/event-stream`；可选 `Last-Event-ID` | SSE 事件：`snapshot`、`status`、`chunk`、`report`、`error`、`done` | 流式接收任务状态和临时文本；SSE 只用于中继，不作为最终报告存储 |
| AI-06 | `POST` | `/ai/tasks/{taskId}/cancel` | `USER` | Header：`Idempotency-Key`；无 Body | `taskId`、`status`、`cancelRequested`、`effectiveImmediately` | 请求取消本人任务；已完成任务保持完成并返回 `effectiveImmediately=false` |
| AI-07 | `POST` | `/ai/tasks/{taskId}/retry` | `USER` | Header：`Idempotency-Key`；Body：可选 `question` | HTTP 202；新的 `AiTaskSummary`，含 `retryOfTaskId`、`streamUrl` | 对 `FAILED` 或 `TIMED_OUT` 任务创建新任务，不覆盖旧任务和旧用量 |
| AI-08 | `POST` | `/ai/sessions/{sessionId}/follow-up-tasks` | `USER` | Header：`Idempotency-Key`；Body：`question`、可选 `analysisStartAt`、`analysisEndAt` | HTTP 202；新的 `AiTaskSummary`、`streamUrl` | 在本人活动会话中追问；复用必要历史但重新固化最新数据上下文 |

### 13.3 创建 AI 任务请求示例

```json
{
  "sessionId": null,
  "scene": "COMPARE",
  "targets": [
    {
      "targetType": "SECURITY",
      "targetId": "19876543210001",
      "targetRole": "PRIMARY"
    },
    {
      "targetType": "SECURITY",
      "targetId": "19876543210002",
      "targetRole": "COMPARISON"
    }
  ],
  "analysisStartAt": "2026-09-01T00:00:00+08:00",
  "analysisEndAt": "2026-09-08T15:00:00+08:00",
  "question": "比较两只股票近期量价变化和相关事件，重点说明不确定性。"
}
```

### 13.4 SSE 事件契约

SSE 入口：

```http
GET /api/v1/ai/tasks/{taskId}/stream
Accept: text/event-stream
Authorization: Bearer <accessToken>
Last-Event-ID: 37
```

通用事件格式：

```text
id: 38
event: chunk
data: {"taskId":"19876543219999","section":"CORE_CONCLUSION","delta":"从当前量价数据看...","sequence":38}
```

| 事件 | `data` 关键字段 | 说明 |
| --- | --- | --- |
| `snapshot` | `task`、`lastSequence`、可选 `partialContent` | 建连或重连后的当前完整状态 |
| `status` | `taskId`、`status`、`progressStage`、`sequence` | 任务状态变化 |
| `chunk` | `taskId`、`section`、`delta`、`sequence` | 临时文本片段，前端只追加到对应章节 |
| `report` | `taskId`、`reportId`、`qualityStatus`、`isLimited`、`sequence` | 最终报告已经写入 MySQL，可通过 REST 查询 |
| `error` | `taskId`、`errorCode`、`message`、`retryable`、`sequence` | 任务失败、超时或安全拒绝 |
| `done` | `taskId`、`finalStatus`、`sequence` | 流结束，客户端关闭连接 |

SSE 规则：

- `Last-Event-ID` 仅支持 Redis 临时流保留期内补发，任务完成后临时片段默认保留 30 分钟。
- 临时片段可能因最终结构、引用或安全校验失败而不形成报告，前端必须以 `report` 事件或任务 `COMPLETED` 为成功依据。
- 关闭浏览器或 AI 侧栏不自动取消任务；只有调用取消接口才进入取消流程。
- 客户端断线后先查询任务状态，再决定重连 SSE 或读取最终报告。

### 13.5 AI 状态、质量与异常规则

- 状态迁移：`CREATED -> PREPARING -> QUEUED -> RUNNING -> VALIDATING -> COMPLETED`。
- `CANCELED`、`FAILED`、`TIMED_OUT` 是终态。完成、取消或失败后不允许回退到运行态。
- 核心行情缺失时拒绝创建或终止任务；新闻缺失时可产生 `LIMITED` 报告，但必须提供 `limitedReason`。
- 完整报告固定包含核心结论、行情与量价依据、对比分析、资讯事件线索、风险与不确定性、数据截止时间、来源引用和非投资建议声明。
- 模型生成的 URL 不直接作为证据；引用只能绑定任务开始时固化的证据候选。
- 正常完成目标：首段 P95 不超过 5 秒，完整结果 P95 不超过 30 秒，超过 60 秒进入超时处理。
- 单用户默认最多 2 个并发任务，全局按 30 个并发任务设计；实际每日额度由配置返回。
- 常见异常：`AI_QUOTA_EXCEEDED`、`AI_CONCURRENCY_EXCEEDED`、`AI_TARGET_INVALID`、`AI_CORE_DATA_MISSING`、`AI_TASK_NOT_FOUND`、`AI_TASK_NOT_CANCELABLE`、`AI_PROVIDER_RATE_LIMITED`、`AI_PROVIDER_UNAVAILABLE`、`AI_OUTPUT_REJECTED`、`AI_TASK_TIMED_OUT`。

## 14. AI 历史、报告与反馈模块

### 14.1 会话与消息接口

| 编号 | 请求方式 | URL | 权限 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| HIS-01 | `GET` | `/ai/sessions` | `USER` | Query：`scene`、`keyword`、`favorite`、`startAt`、`endAt`、分页 | `PageData`：`sessionId`、`scene`、`title`、`status`、`isFavorite`、`lastTask`、`lastActivityAt`、`createdAt`、`version` | 查询本人有效分析历史，按最后活动时间倒序 |
| HIS-02 | `GET` | `/ai/sessions/{sessionId}` | `USER` | Path：`sessionId` | 会话摘要、目标摘要、最近任务和报告摘要、`version` | 获取本人会话详情；删除状态对普通列表不可见 |
| HIS-03 | `PATCH` | `/ai/sessions/{sessionId}` | `USER` | Header：`If-Match`；Body：可选 `title`、`isFavorite` | 更新后的会话和新 `version` | 重命名或收藏会话；标题长度 1 至 60 个字符 |
| HIS-04 | `DELETE` | `/ai/sessions/{sessionId}` | `USER` | Header：`If-Match` | `deleted`、`purgeAfter` | 软删除会话，默认 30 天后物理清理；运行中任务需先取消或等待结束 |
| HIS-05 | `GET` | `/ai/sessions/{sessionId}/messages` | `USER` | Query：分页，默认按 `sequenceNo` 升序 | 消息分页：`messageId`、`taskId`、`roleType`、`sequenceNo`、`contentFormat`、`content`、`status`、`dataCutoffAt`、`createdAt` | 获取本人会话消息；不返回 `SYSTEM` 内部 Prompt，系统消息仅返回可公开状态说明 |

### 14.2 报告、证据与反馈接口

| 编号 | 请求方式 | URL | 权限 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| HIS-06 | `GET` | `/ai/reports/{reportId}` | `USER` | Path：`reportId` | `reportId`、`taskId`、`sessionId`、`coreConclusion`、`quoteEvidence`、`comparisonAnalysis`、`eventClues`、`riskAndUncertainty`、`disclaimer`、`renderedMarkdown`、`qualityStatus`、`isLimited`、`limitedReason`、数据截止时间、模型/模板版本、`generatedAt`、当前用户反馈 | 获取本人结构化最终报告；失败任务不存在报告资源 |
| HIS-07 | `GET` | `/ai/reports/{reportId}/evidence` | `USER` | Query：可选 `evidenceType` | 证据数组：`evidenceNo`、`evidenceType`、`sourceTitle`、`sourceUrl`、`evidenceSummary`、`sourcePublishedAt`、`dataTime`、`accessStatus` | 查询报告引用；来源失效后保留证据摘要并更新访问状态，不能静默删除编号 |
| HIS-08 | `PUT` | `/ai/reports/{reportId}/feedback` | `USER` | Body：`feedbackType=HELPFUL\|NOT_HELPFUL`、可选 `reasonCode`、`detail` | `feedbackId`、`feedbackType`、`reasonCode`、`detail`、`updatedAt` | 创建或替换本人对报告的唯一反馈；差评建议提供原因，详情最多 300 字符 |
| HIS-09 | `DELETE` | `/ai/reports/{reportId}/feedback` | `USER` | 无 | `deleted` | 删除本人反馈，不影响报告和内部聚合历史 |

反馈原因枚举：`FACT_ERROR`、`CITATION_ERROR`、`OVER_INFERENCE`、`OFF_TOPIC`、`OUTDATED`。

常见异常：`AI_SESSION_NOT_FOUND`、`AI_SESSION_READ_ONLY`、`AI_SESSION_HAS_RUNNING_TASK`、`AI_SESSION_VERSION_CONFLICT`、`AI_REPORT_NOT_FOUND`、`AI_EVIDENCE_RESTRICTED`、`AI_FEEDBACK_INVALID`。

## 15. 实时行情 WebSocket 模块

### 15.1 Ticket 接口

| 编号 | 请求方式 | URL | 权限 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| RT-01 | `POST` | `/realtime/tickets` | `PUBLIC` | 登录用户可携带 JWT；Body：`channel=MARKET` | `ticket`、`expiresInSeconds=60`、`authenticated`、`maxTopics`、`maxConnections`、`websocketUrl` | 签发 60 秒有效、单次使用的 WebSocket Ticket；游客 Ticket 按 IP 和 Origin 限制 |

### 15.2 建连与订阅

连接地址：

```text
wss://example.com/ws/v1/market?ticket=<single-use-ticket>
```

客户端订阅消息：

```json
{
  "requestId": "a7012ec3-310b-4cce-8e7f-63279912f123",
  "action": "SUBSCRIBE",
  "topics": [
    "security:19876543210001",
    "index:19876543210020",
    "ranking:GAINERS"
  ]
}
```

支持主题：

| 主题 | 权限 | 说明 |
| --- | --- | --- |
| `security:{securityId}` | 公共 | 个股最新行情变化 |
| `index:{indexId}` | 公共 | 指数最新点位变化 |
| `sector:{sectorId}` | 公共 | 板块行情统计变化 |
| `ranking:{rankingType}` | 公共 | 榜单快照版本切换通知，不推送完整榜单 |
| `watchlist` | 登录 | 当前用户自选证券聚合变化，服务端根据用户 ID 展开 |

服务端行情事件：

```json
{
  "event": "QUOTE_UPDATED",
  "topic": "security:19876543210001",
  "businessId": "19876543210001",
  "sequence": "89301731",
  "dataTime": "2026-09-08T14:30:15+08:00",
  "serverTime": "2026-09-08T14:30:16.120+08:00",
  "dataStatus": "REALTIME",
  "changedFields": {
    "latestPrice": "12.35",
    "changeRate": "0.021500",
    "tradeVolume": "125009800"
  }
}
```

### 15.3 WebSocket 协议规则

- 支持 `SUBSCRIBE`、`UNSUBSCRIBE`、`PING`；服务端响应 `ACK`、`PONG`、`ERROR` 和业务事件。
- 单连接最多订阅 50 个个股/板块主题；每个登录用户最多 3 个并发连接，游客按 IP 限制。
- 心跳周期 30 秒，90 秒无有效心跳关闭连接。
- 推送只包含变化字段。客户端发现序列跳跃、时间倒退或重连时，必须停止应用增量并通过 REST 获取完整快照。
- `ranking:*` 仅通知 `snapshotVersion` 变化，客户端重新请求榜单，避免推送大列表。
- Redis Pub/Sub 不保证离线补发；WebSocket 不承担历史数据和最终事实存储。
- 慢客户端发生积压时合并中间报价，只保证发送最新状态，不阻塞行情采集。
- 常见关闭/错误码：`WS_TICKET_INVALID`、`WS_TICKET_EXPIRED`、`WS_ORIGIN_DENIED`、`WS_TOPIC_INVALID`、`WS_TOPIC_LIMIT_EXCEEDED`、`WS_CONNECTION_LIMIT_EXCEEDED`、`WS_SUBSCRIPTION_FORBIDDEN`。

## 16. 后台用户与权限模块

后台接口统一使用 `/api/v1/admin` 前缀。除登录身份外，每个接口还必须校验 Spring Security 权限标识；前端菜单是否显示不能替代后端授权。

### 16.1 用户管理

| 编号 | 请求方式 | URL | 权限标识 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| ADM-USR-01 | `GET` | `/admin/users` | `sys:user:list` | Query：`keyword`、`status`、`roleId`、`createdStartAt`、`createdEndAt`、分页排序 | 用户分页：`userId`、`username`、脱敏邮箱/手机、昵称、状态、角色、创建时间、最后登录时间、`version` | 分页查询未物理删除用户；普通管理员看不到密码、Token 和完整联系方式 |
| ADM-USR-02 | `GET` | `/admin/users/{userId}` | `sys:user:detail` | Path：`userId` | 用户基础资料、角色、状态、创建来源、时间字段、`tokenVersion`、`version` | 查看单个用户管理信息；是否展示未脱敏联系方式由单独权限控制 |
| ADM-USR-03 | `POST` | `/admin/users` | `sys:user:create` | Header：`Idempotency-Key`；Body：`username`、临时 `password`、可选资料、`roleIds`、`status` | 新用户摘要、角色、`mustChangePassword=true`、`version` | 管理员创建账号；临时密码不出现在响应和日志中 |
| ADM-USR-04 | `PATCH` | `/admin/users/{userId}` | `sys:user:update` | Header：`If-Match`；Body：可选资料字段 | 更新后的用户和新 `version` | 修改用户资料，不允许通过该接口修改密码、角色和逻辑删除状态 |
| ADM-USR-05 | `PATCH` | `/admin/users/{userId}/status` | `sys:user:status` | Header：`If-Match`；Body：`status=ACTIVE\|LOCKED`、`reason` | `userId`、`status`、`revokedSessionCount`、新 `version` | 锁定用户时递增 Token 版本并撤销全部刷新会话；不得锁定最后一个超级管理员 |
| ADM-USR-06 | `PUT` | `/admin/users/{userId}/roles` | `sys:user:role` | Header：`If-Match`；Body：完整 `roleIds` 数组 | 用户角色数组、新 `version`、`revokedSessionCount` | 原子替换角色关系，权限缓存立即失效；禁止移除最后一个超级管理员的关键角色 |
| ADM-USR-07 | `POST` | `/admin/users/{userId}/password-reset` | `sys:user:password-reset` | Header：`Idempotency-Key`；Body：`delivery=EMAIL`、`reason` | `accepted`、`maskedDestination`、`expiresInSeconds` | 发送一次性密码重置凭证，不允许管理员读取或指定用户最终密码 |
| ADM-USR-08 | `POST` | `/admin/users/{userId}/sessions/revoke` | `sys:user:session-revoke` | Header：`Idempotency-Key`；Body：`reason` | `revokedSessionCount`、`tokenVersion` | 强制用户全部下线并记录审计 |
| ADM-USR-09 | `DELETE` | `/admin/users/{userId}` | `sys:user:delete` | Header：`If-Match`；Body：`reason` | `deleted`、`revokedSessionCount` | 按遗留字段语义将 `deleted` 置为 0；禁止删除本人和最后一个超级管理员 |

API 层对遗留 `sys_user.status` 做语义转换：数据库 `1` 映射 `ACTIVE`，`2` 映射 `LOCKED`。不把反向逻辑删除字段 `deleted=1/0` 暴露给前端。

### 16.2 角色管理

| 编号 | 请求方式 | URL | 权限标识 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| ADM-ROL-01 | `GET` | `/admin/roles` | `sys:role:list` | Query：`keyword`、`status`、分页 | 角色分页：`roleId`、`name`、`description`、`status`、`userCount`、`permissionCount`、`version` | 查询有效角色 |
| ADM-ROL-02 | `GET` | `/admin/roles/{roleId}` | `sys:role:detail` | Path：`roleId` | 角色详情、权限 ID、权限树摘要、`version` | 查看角色及当前授权 |
| ADM-ROL-03 | `POST` | `/admin/roles` | `sys:role:create` | Header：`Idempotency-Key`；Body：`name`、`description`、`status` | 新角色详情与 `version` | 创建角色；活动角色名称唯一 |
| ADM-ROL-04 | `PATCH` | `/admin/roles/{roleId}` | `sys:role:update` | Header：`If-Match`；Body：可选 `name`、`description`、`status` | 更新后的角色、新 `version`、`affectedUserCount` | 更新或停用角色；停用后使受影响用户权限缓存失效 |
| ADM-ROL-05 | `PUT` | `/admin/roles/{roleId}/permissions` | `sys:role:permission` | Header：`If-Match`；Body：完整 `permissionIds` | 权限 ID、`affectedUserCount`、新 `version` | 原子替换角色权限；目录父节点可由服务端补齐但不隐式授予额外按钮权限 |
| ADM-ROL-06 | `DELETE` | `/admin/roles/{roleId}` | `sys:role:delete` | Header：`If-Match`；Body：`reason` | `deleted` | 逻辑删除未被用户使用的角色；被使用时返回冲突，禁止删除系统保留角色 |

### 16.3 权限与菜单管理

| 编号 | 请求方式 | URL | 权限标识 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| ADM-PER-01 | `GET` | `/admin/permissions/tree` | `sys:permission:list` | Query：可选 `status`、`types` | 权限树：`permissionId`、`code`、`title`、`icon`、`perms`、`url`、`method`、`routeName`、`parentId`、`orderNum`、`type`、`status`、`version`、`children` | 获取目录、菜单、按钮权限树 |
| ADM-PER-02 | `GET` | `/admin/permissions/{permissionId}` | `sys:permission:detail` | Path：`permissionId` | 权限详情、子节点数、角色引用数、`version` | 查看权限详情 |
| ADM-PER-03 | `POST` | `/admin/permissions` | `sys:permission:create` | Header：`Idempotency-Key`；Body：`code`、`title`、`icon`、`perms`、`url`、`method`、`routeName`、`parentId`、`orderNum`、`type`、`status` | 新权限详情与 `version` | 创建权限；活动 `code` 和非空 `perms` 分别唯一 |
| ADM-PER-04 | `PATCH` | `/admin/permissions/{permissionId}` | `sys:permission:update` | Header：`If-Match`；Body：允许修改的权限字段 | 更新后的权限、新 `version`、`affectedRoleCount` | 更新权限并清理相关用户权限缓存；禁止把节点移动到自身子树 |
| ADM-PER-05 | `DELETE` | `/admin/permissions/{permissionId}` | `sys:permission:delete` | Header：`If-Match`；Body：`reason` | `deleted` | 仅允许删除无子节点且未被角色引用的非系统保留权限 |

### 16.4 账户与 RBAC 异常

常见异常：`ADMIN_USER_NOT_FOUND`、`USERNAME_EXISTS`、`EMAIL_EXISTS`、`LAST_SUPER_ADMIN_PROTECTED`、`ROLE_NOT_FOUND`、`ROLE_NAME_EXISTS`、`ROLE_IN_USE`、`PERMISSION_NOT_FOUND`、`PERMISSION_CODE_EXISTS`、`PERMISSION_PERMS_EXISTS`、`PERMISSION_TREE_CYCLE`、`RESOURCE_VERSION_CONFLICT`。

## 17. 后台资讯治理模块

### 17.1 资讯来源管理

| 编号 | 请求方式 | URL | 权限标识 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| ADM-NEWS-01 | `GET` | `/admin/news-sources` | `news:source:list` | Query：`providerId`、`sourceType`、`authorizationStatus`、`status`、分页 | 来源分页：来源 ID/编码/名称/类型、授权状态和期限、`allowAiAnalysis`、运行状态、最近成功/失败时间、`version` | 查询授权媒体、交易所、公司和监管机构来源 |
| ADM-NEWS-02 | `GET` | `/admin/news-sources/{sourceId}` | `news:source:detail` | Path：`sourceId` | 来源完整非敏感配置、授权期限、健康状态和 `version` | 不返回 Provider 凭证和供应商完整配置 |
| ADM-NEWS-03 | `POST` | `/admin/news-sources` | `news:source:create` | Header：`Idempotency-Key`；Body：`providerId`、`sourceCode`、`sourceName`、`sourceType`、`homepageUrl`、授权区间、`allowAiAnalysis`、`status` | 新来源详情与 `version` | 创建内容来源；来源编码唯一，授权状态不能由客户端任意伪造为有效 |
| ADM-NEWS-04 | `PATCH` | `/admin/news-sources/{sourceId}` | `news:source:update` | Header：`If-Match`；Body：可修改来源名称、主页、授权区间、AI 使用许可和状态 | 更新后的来源详情与新 `version` | 授权到期或暂停时立即停止新内容进入 AI，上线内容按授权规则降级 |

### 17.2 资讯关联审核

| 编号 | 请求方式 | URL | 权限标识 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| ADM-NEWS-05 | `GET` | `/admin/news-relations` | `news:relation:list` | Query：`relationStatus`、`targetType`、`newsId`、`minConfidence`、时间范围、分页 | 关联分页：`relationId`、新闻摘要、目标摘要、关联方法、置信度、状态、原因、审核信息 | 默认查看 `CANDIDATE`，供低置信关联人工复核 |
| ADM-NEWS-06 | `PATCH` | `/admin/news-relations/{relationId}` | `news:relation:review` | Body：`relationStatus=CONFIRMED\|REJECTED`、`reasonSummary` | 更新后的关联、`reviewedBy`、`reviewedAt` | 人工确认或拒绝候选关系；审核后刷新相关前台资讯缓存 |
| ADM-NEWS-07 | `POST` | `/admin/news/{newsId}/relations` | `news:relation:create` | Header：`Idempotency-Key`；Body：`targetType`、`targetId`、`reasonSummary` | 新关联，`relationMethod=MANUAL`、`relationStatus=CONFIRMED` | 手工建立可解释关联；同一新闻和目标保持幂等 |
| ADM-NEWS-08 | `DELETE` | `/admin/news-relations/{relationId}` | `news:relation:delete` | Body：`reasonSummary` | `deleted` 或状态 `REJECTED` | 保留审计优先采用拒绝状态，不物理删除有使用历史的关系 |

常见异常：`NEWS_SOURCE_NOT_FOUND`、`NEWS_SOURCE_CODE_EXISTS`、`NEWS_RIGHTS_PERIOD_INVALID`、`NEWS_RELATION_NOT_FOUND`、`NEWS_RELATION_ALREADY_REVIEWED`、`NEWS_RELATION_TARGET_INVALID`。

## 18. 后台数据源、任务与质量模块

### 18.1 外部 Provider 管理

| 编号 | 请求方式 | URL | 权限标识 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| ADM-PRV-01 | `GET` | `/admin/providers` | `ops:provider:list` | Query：`providerType=QUOTE\|NEWS\|LLM`、`status`、分页 | Provider 分页：ID、编码、类型、名称、状态、限流策略摘要、健康状态、最近成功时间、连续失败数、`version` | 查询外部行情、新闻和 LLM Provider 状态 |
| ADM-PRV-02 | `GET` | `/admin/providers/{providerId}` | `ops:provider:detail` | Path：`providerId` | 非敏感 Provider 详情；`credentialConfigured` 仅返回布尔值 | 凭证仅保存外部配置引用，接口不返回引用值和密钥 |
| ADM-PRV-03 | `POST` | `/admin/providers` | `ops:provider:create` | Header：`Idempotency-Key`；Body：编码、类型、名称、`credentialConfigKey`、限流策略、状态 | 新 Provider 详情与 `version` | 建立 Provider 元数据；服务端校验配置引用存在，但不接收明文密钥 |
| ADM-PRV-04 | `PATCH` | `/admin/providers/{providerId}` | `ops:provider:update` | Header：`If-Match`；Body：名称、状态、凭证引用、限流策略 | 更新后的 Provider 和新 `version` | 变更 Provider；禁用后停止新任务调用，不中断数据库事务 |
| ADM-PRV-05 | `POST` | `/admin/providers/{providerId}/health-checks` | `ops:provider:health-check` | Header：`Idempotency-Key`；Body：可选 `timeoutSeconds`，最大 10 | HTTP 202；`checkId`、`status=RUNNING`、`startedAt` | 触发受限轻量探测，不执行全量行情、新闻或高成本模型请求 |
| ADM-PRV-06 | `GET` | `/admin/providers/{providerId}/health-checks/{checkId}` | `ops:provider:health-check` | Path 参数 | `checkId`、`status`、`latencyMs`、`checkedAt`、脱敏错误摘要 | 查询健康检查结果；临时结果可保存在 Redis |

### 18.2 同步游标管理

| 编号 | 请求方式 | URL | 权限标识 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| ADM-SYNC-01 | `GET` | `/admin/sync-checkpoints` | `ops:sync:list` | Query：`providerId`、`taskType`、`scopeKey`、`status`、分页 | 游标分页：ID、Provider 摘要、任务类型、范围、最近源时间、尝试/成功时间、状态、连续失败数、错误码、`version` | 查看证券、行情、板块、指数、新闻等增量同步状态；默认不返回原始游标内容 |
| ADM-SYNC-02 | `GET` | `/admin/sync-checkpoints/{checkpointId}` | `ops:sync:detail` | Path：`checkpointId` | 游标详情，`cursorValue` 仅在具备 `ops:sync:cursor` 权限时返回脱敏值 | 用于定位增量停滞，不允许通过普通详情泄露供应商信息 |
| ADM-SYNC-03 | `PATCH` | `/admin/sync-checkpoints/{checkpointId}/status` | `ops:sync:update` | Header：`If-Match`；Body：`status=READY\|PAUSED`、`reason` | 更新后的游标状态与新 `version` | 人工暂停或恢复后续调度；运行中任务按协作取消，不直接修改为成功 |

### 18.3 定时任务执行管理

| 编号 | 请求方式 | URL | 权限标识 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| ADM-JOB-01 | `GET` | `/admin/job-definitions` | `ops:job:list` | 无 | 任务定义数组：`jobName`、`displayName`、`handlerName`、`scheduleDescription`、`supportsManualTrigger`、`supportsShard`、`enabled` | 返回应用白名单任务定义，不直接暴露任意 Handler 调用能力 |
| ADM-JOB-02 | `POST` | `/admin/job-definitions/{jobName}/executions` | `ops:job:trigger` | Header：`Idempotency-Key`；Body：可选 `providerId`、`scopeKey`、`shardTotal`、`reason` | HTTP 202；`batchId`、执行记录摘要、`triggerType=MANUAL` | 人工触发白名单任务；范围、分片和频率受服务端约束并记录管理员审计 |
| ADM-JOB-03 | `GET` | `/admin/job-executions` | `ops:job:list` | Query：`jobName`、`providerId`、`status`、`triggerType`、`batchId`、时间范围、分页 | 执行分页：ID、任务、批次、分片、尝试次数、状态、计数、时间、错误类别、`traceId` | 查询任务执行摘要，按开始时间倒序 |
| ADM-JOB-04 | `GET` | `/admin/job-executions/{executionId}` | `ops:job:detail` | Path：`executionId` | 执行完整摘要：调度/开始/完成时间、源数据范围、输入/成功/忽略/失败/输出数、脱敏错误摘要、Trace ID | 用于排查采集和聚合任务，不返回第三方完整原始响应 |
| ADM-JOB-05 | `POST` | `/admin/job-executions/{executionId}/retries` | `ops:job:retry` | Header：`Idempotency-Key`；Body：`reason` | HTTP 202；新执行摘要，`triggerType=RETRY`、新 `attemptNo` | 仅允许对失败或部分失败执行重试；新记录不覆盖旧执行记录 |

### 18.4 数据质量问题

| 编号 | 请求方式 | URL | 权限标识 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| ADM-DQ-01 | `GET` | `/admin/data-quality-issues` | `ops:data-quality:list` | Query：`dataDomain`、`severity`、`status`、`issueCode`、`batchId`、时间范围、分页 | 问题分页：ID、批次、数据域、对象类型/键、问题码、严重级别、原因、状态、创建/处理时间 | 查看被隔离或标记的数据质量问题 |
| ADM-DQ-02 | `GET` | `/admin/data-quality-issues/{issueId}` | `ops:data-quality:detail` | Path：`issueId` | 问题详情、原始摘要哈希、去敏最小样本、处理信息 | `sanitizedSample` 已去敏，不提供完整第三方原始响应 |
| ADM-DQ-03 | `PATCH` | `/admin/data-quality-issues/{issueId}` | `ops:data-quality:resolve` | Body：`status=RESOLVED\|IGNORED`、`resolutionSummary` | 更新后的状态、`resolvedBy`、`resolvedAt` | 处理质量问题；只改变治理状态，不直接篡改行情或新闻事实 |

常见异常：`PROVIDER_NOT_FOUND`、`PROVIDER_CONFIG_INVALID`、`PROVIDER_HEALTH_CHECK_RATE_LIMITED`、`SYNC_CHECKPOINT_NOT_FOUND`、`SYNC_STATE_CONFLICT`、`JOB_NOT_SUPPORTED`、`JOB_ALREADY_RUNNING`、`JOB_RETRY_NOT_ALLOWED`、`DATA_QUALITY_ISSUE_NOT_FOUND`、`DATA_QUALITY_STATE_CONFLICT`。

## 19. 后台 AI 运营模块

| 编号 | 请求方式 | URL | 权限标识 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| ADM-AI-01 | `GET` | `/admin/ai/overview` | `ai:ops:overview` | Query：可选 `startAt`、`endAt` | 任务总量、成功/失败/取消/超时数、成功率、受限报告数、队列长度、运行中任务数、首段/总耗时分位数、Token、估算成本、Provider 状态 | AI 运营总览；成本仅面向授权管理员 |
| ADM-AI-02 | `GET` | `/admin/ai/tasks` | `ai:ops:task-list` | Query：`taskId`、`userId`、`scene`、`status`、`providerCode`、`errorCategory`、时间范围、分页 | 任务元数据分页：任务/会话/用户 ID、场景、目标摘要、状态、时间、错误类别、Trace ID | 默认不返回用户问题、消息正文、完整上下文和报告正文 |
| ADM-AI-03 | `GET` | `/admin/ai/tasks/{taskId}` | `ai:ops:task-detail` | Path：`taskId` | 任务状态机时间、重试关系、Provider/模型、目标、错误摘要、用量摘要、上下文类型计数 | 用于故障定位；读取用户问题或报告正文需单独合规授权，MVP 不提供该接口 |
| ADM-AI-04 | `POST` | `/admin/ai/tasks/{taskId}/cancel` | `ai:ops:task-cancel` | Header：`Idempotency-Key`；Body：`reason` | `taskId`、`status`、`cancelRequested` | 管理员取消卡死或风险任务，记录操作人和原因 |
| ADM-AI-05 | `GET` | `/admin/ai/usage` | `ai:ops:usage` | Query：`groupBy=DAY\|PROVIDER\|MODEL\|SCENE`、`startAt`、`endAt`、可选 Provider/模型/状态 | 分组用量：调用次数、Token、缓存 Token、估算成本、成功率、首段和总耗时 | 基于 `ai_usage` 聚合，不暴露 Provider 请求正文 |
| ADM-AI-06 | `GET` | `/admin/ai/feedback-statistics` | `ai:ops:feedback` | Query：`startAt`、`endAt`、`scene`、`feedbackType`、`reasonCode` | 反馈数量、正负反馈率、原因分布、趋势 | 只返回聚合数据，不默认展示用户反馈详情和用户身份 |

常见异常：`AI_ADMIN_TASK_NOT_FOUND`、`AI_ADMIN_CANCEL_NOT_ALLOWED`、`AI_USAGE_RANGE_TOO_LARGE`、`AI_PRIVATE_CONTENT_FORBIDDEN`。

## 20. 操作日志模块

| 编号 | 请求方式 | URL | 权限标识 | 请求参数 | 返回参数 | 接口说明 |
| --- | --- | --- | --- | --- | --- | --- |
| LOG-01 | `GET` | `/admin/operation-logs` | `sys:log:list` | Query：`userId`、`username`、`operation`、`resultStatus`、`httpMethod`、`requestUri`、`traceId`、`ip`、时间范围、分页 | 日志分页：`logId`、用户摘要、操作、耗时、请求 URI/方法、结果状态、IP、Trace ID、创建时间 | 查询脱敏操作日志；默认最多查询 90 天范围 |
| LOG-02 | `GET` | `/admin/operation-logs/{logId}` | `sys:log:detail` | Path：`logId` | 日志详情、控制层方法、脱敏参数摘要、遗留用户引用、Trace ID | 不返回密码、JWT、Cookie、验证码、API Key 或完整 AI 上下文 |

日志为审计记录，不提供编辑和删除业务接口。数据保留与归档由运维策略和定时任务控制。

## 21. 接口与数据存储映射

| API 模块 | MySQL 主要表 | Redis 主要数据 | 查询策略 |
| --- | --- | --- | --- |
| 用户认证 | `sys_user`、`sys_user_role`、`sys_role`、`sys_role_permission`、`sys_permission`、`sys_log` | 刷新会话、Access Token 黑名单、验证码、登录失败计数、权限缓存 | 用户事实写 MySQL；短期会话和安全状态写 Redis |
| 市场总览 | `stock_market_index_info`、`stock_outer_market_index_info`、`stock_trade_calendar`、`stock_minute_bar`、`stock_kline_day` | 最新指数、市场广度、成交趋势、热点板块 | 首选 Redis 快照，失败时回退 MySQL 最近有效数据 |
| 股票与行情 | `stock_security`、`stock_security_status_history`、`stock_business`、`stock_minute_bar`、`stock_kline_day`、兼容表 `stock_rt_info` | 最新个股行情、热门 K 线、搜索热点 | 最新行情读 Redis；历史行情与主数据读 MySQL |
| 板块 | `stock_sector`、`stock_security_sector`、兼容表 `stock_block_rt_info` | 最新板块行情、板块排行 | 最新统计读 Redis；板块关系读 MySQL |
| 新闻公告 | `news_source`、`stock_news`、`stock_news_relation`、`external_provider` | 最新资讯列表、同步状态摘要 | 最新列表读 Redis ID 集合并批量回表；历史筛选读 MySQL |
| 自选 | `user_watchlist_group`、`user_watchlist_item` | 可选短期视图缓存 | 自选关系始终以 MySQL 为准，行情从 Market 模块聚合 |
| AI 研究 | `ai_session`、`ai_task`、`ai_task_target`、`ai_context_snapshot`、`ai_message`、`ai_report`、`ai_evidence`、`ai_feedback`、`ai_usage` | 任务队列、任务心跳、临时片段、并发与配额 | 最终任务、报告、证据写 MySQL；执行中状态和流片段读 Redis |
| 异步导出 | `sys_log` 仅保存审计 | 短期任务状态；文件保存在临时卷/对象存储 | 文件默认 24 小时过期，不作为永久业务事实 |
| 系统运营 | `external_provider`、`data_sync_checkpoint`、`job_execution_summary`、`data_quality_issue`、`event_outbox`、`sys_log` | Provider 健康检查临时结果、任务锁 | 管理查询以 MySQL 摘要为准，实时健康状态可合并 Redis |

任何面向用户的 REST 查询均不得为了“更实时”而同步请求第三方股票、新闻或大模型 API。只有后台任务、受限健康检查和 AI Worker 可以通过 `stock-integration` 访问外部 Provider。

## 22. 限流、安全与审计

### 22.1 建议限流基线

| 接口类别 | 限制基线 | 维度 |
| --- | --- | --- |
| 公共行情查询 | 120 次/分钟 | IP |
| 证券搜索 | 60 次/分钟 | IP 或用户 |
| 登录 | 10 次/15 分钟，失败后逐步收紧 | 账号 + IP |
| 验证码发送 | 1 次/60 秒、5 次/日 | 目标邮箱 + IP |
| 自选写操作 | 60 次/分钟 | 用户 |
| AI 任务创建 | 每用户最多 2 个并发，日配额由配置决定 | 用户 |
| 榜单导出 | 2 次/分钟、单次最多 5,000 行 | 用户 |
| 人工任务触发 | 10 次/小时并受任务级互斥约束 | 管理员 + 任务名 |
| WebSocket | 用户最多 3 连接，单连接最多 50 个普通主题 | 用户或 IP |

超过限流返回 HTTP 429，响应头可包含：

```http
RateLimit-Limit: 120
RateLimit-Remaining: 0
RateLimit-Reset: 1757313060
Retry-After: 30
```

### 22.2 输入与输出安全

- Controller 使用 Bean Validation 校验长度、格式、范围和枚举，业务层再次校验资源状态与所有权。
- 搜索、排序和筛选字段使用白名单映射；MyBatis-Plus Wrapper 不接受客户端原始字段名或 SQL 片段。
- 所有文本输出进行上下文相关转义；Markdown 渲染使用安全白名单，不执行 HTML、脚本或事件属性。
- Excel 文本以 `=`、`+`、`-`、`@` 开头时进行公式注入防护。
- 外链只允许白名单协议；资讯原文和 AI 引用跳转由前端添加安全属性。
- 日志按字段白名单记录，请求参数先脱敏；密码、JWT、Refresh Token、Cookie、验证码和 API Key 永不落日志。
- 用户私密数据按用户 ID 隔离。管理员统计接口不默认返回用户 AI 问题、消息、上下文和报告正文。
- 删除 AI 会话采用 30 天延迟清理，期间普通用户不可访问；到期清理由定时任务执行。

### 22.3 审计范围

以下操作必须写入 `sys_log` 并携带 `traceId`：

- 登录成功/失败、刷新令牌重放、退出和密码变更。
- 用户状态、角色、权限、会话撤销和逻辑删除。
- 新闻来源授权变更和关联人工审核。
- Provider 变更、健康检查、同步暂停/恢复、任务人工触发/重试。
- 数据质量问题解决/忽略、AI 管理取消、榜单和报告导出。

## 23. HTTP 状态码与业务错误码

### 23.1 HTTP 状态码

| HTTP 状态 | 使用场景 |
| --- | --- |
| `200 OK` | 查询成功、修改成功、删除结果返回成功 |
| `201 Created` | 用户、分组、角色、权限等同步资源创建成功 |
| `202 Accepted` | AI、导出、健康检查、人工调度等异步任务已受理 |
| `304 Not Modified` | ETag 条件查询未变化，无响应体 |
| `400 Bad Request` | JSON、类型、格式、范围或业务前置参数无效 |
| `401 Unauthorized` | 未登录、Access Token 无效或过期 |
| `403 Forbidden` | 已认证但无权限、账号受限或操作不允许 |
| `404 Not Found` | 资源不存在，或为防水平越权而隐藏他人资源存在性 |
| `409 Conflict` | 唯一约束、状态机、乐观锁或幂等键冲突 |
| `413 Payload Too Large` | 请求体或批量对象超过上限 |
| `422 Unprocessable Entity` | 语法正确但分析目标组合、时间区间等业务语义无效 |
| `429 Too Many Requests` | IP、用户、验证码、导出、AI 或 Provider 限流 |
| `500 Internal Server Error` | 未预期内部异常，外部消息不暴露堆栈和 SQL |
| `502 Bad Gateway` | 受控外部调用返回无效响应，例如管理员健康检查 |
| `503 Service Unavailable` | 核心行情、Redis、MySQL、AI Provider 等暂不可用 |
| `504 Gateway Timeout` | 同步网关等待超时；AI 业务超时通常通过任务终态表达 |

### 23.2 错误码命名

业务错误码使用稳定的大写英文标识，不把中文消息作为程序判断条件：

| 前缀 | 模块 | 示例 |
| --- | --- | --- |
| `COMMON_` | 通用 | `COMMON_VALIDATION_FAILED`、`COMMON_RESOURCE_NOT_FOUND` |
| `AUTH_` | 认证 | `AUTH_TOKEN_EXPIRED`、`AUTH_FORBIDDEN` |
| `USER_` | 当前用户 | `USER_NOT_FOUND` |
| `MARKET_` | 市场 | `MARKET_DATA_UNAVAILABLE` |
| `SECURITY_` | 证券 | `SECURITY_NOT_FOUND` |
| `QUOTE_` | 行情 | `QUOTE_NOT_AVAILABLE` |
| `SECTOR_` | 板块 | `SECTOR_NOT_FOUND` |
| `NEWS_` | 资讯 | `NEWS_RIGHTS_EXPIRED` |
| `WATCHLIST_` | 自选 | `WATCHLIST_VERSION_CONFLICT` |
| `AI_` | AI | `AI_QUOTA_EXCEEDED`、`AI_OUTPUT_REJECTED` |
| `EXPORT_` | 导出 | `EXPORT_NOT_READY` |
| `ADMIN_` | 后台业务 | `ADMIN_USER_NOT_FOUND` |
| `PROVIDER_`、`SYNC_`、`JOB_` | 运营任务 | `PROVIDER_NOT_FOUND`、`JOB_ALREADY_RUNNING` |

错误码发布后保持向后兼容。新增错误码可以在 V1 内追加，改变既有错误语义必须升级 API 版本。

## 24. API 版本与兼容策略

- URL 主版本为 `/api/v1`。新增可选字段、可选枚举和新端点属于向后兼容变更。
- 删除字段、改变字段类型、改变必填性或改变错误语义属于破坏性变更，必须进入 `/api/v2`。
- 前端必须容忍 JSON 新增未知字段，但对于未知状态枚举应展示“未知状态”并记录监控，不得映射成成功。
- 废弃接口至少保留一个发布周期，并通过 `Deprecation`、`Sunset` 和文档说明迁移路径。
- 第三方 Provider DTO 不得出现在公共 API。更换行情、新闻或 LLM 供应商不应改变前端契约。

## 25. 验收检查

1. 游客能够完成市场、榜单、板块、个股和资讯浏览，调用 AI、自选、历史和导出接口时均被服务端拒绝。
2. 注册用户只能访问本人的自选、AI 会话、任务、报告、证据、反馈和导出任务。
3. 管理员接口同时校验角色权限与具体资源权限，无法通过前端构造请求绕过。
4. 所有 Snowflake ID 都以字符串返回，价格、金额和比例不使用二进制浮点数。
5. 实时行情、榜单、资讯和 AI 报告都能识别数据截止时间和数据状态。
6. AI 创建、重试、取消和追问可处理重复请求，失败任务不会生成完成报告。
7. SSE 断线可在保留期内恢复，超出保留期后可通过 REST 查询最终任务与报告。
8. WebSocket 丢序或重连后可通过 REST 快照恢复，不依赖 Redis Pub/Sub 补发。
9. 导出最多 5,000 行，执行公式注入防护，并记录操作审计。
10. 数据源、同步、任务、质量问题与 AI 用量管理接口不泄露密钥、原始供应商响应或用户私密正文。
11. 所有 JSON 接口符合统一成功、分页、异步和错误响应格式。
12. OpenAPI 实现阶段必须以本文契约为基线，接口 DTO 不得直接暴露 MyBatis-Plus Entity 或第三方 Provider DTO。

## 26. 统一返回格式

除 `304`、SSE、WebSocket 和二进制文件下载外，所有接口统一返回以下 JSON 外壳。

### 26.1 成功响应

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "securityId": "19876543210001",
    "fullSymbol": "SH.600000",
    "securityName": "浦发银行"
  },
  "traceId": "01J7AQ1K8Y4Q9W9GAF2B6CVR6M",
  "timestamp": "2026-09-08T14:30:16.120+08:00"
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `success` | boolean | 是 | 成功固定为 `true`，失败固定为 `false` |
| `code` | string | 是 | 成功固定为 `SUCCESS`；失败为稳定业务错误码 |
| `message` | string | 是 | 面向用户或开发者的中文说明，不作为程序逻辑依据 |
| `data` | object/array/null | 是 | 业务数据；无额外数据时返回 `{}`，失败通常为 `null` |
| `traceId` | string | 是 | 请求链路追踪 ID，问题反馈时提供该值 |
| `timestamp` | datetime | 是 | 服务端响应时间，`Asia/Shanghai` |

### 26.2 分页成功响应

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "查询成功",
  "data": {
    "items": [
      {
        "securityId": "19876543210001",
        "securityCode": "600000",
        "securityName": "浦发银行"
      }
    ],
    "page": 1,
    "size": 20,
    "total": 125,
    "totalPages": 7,
    "hasNext": true
  },
  "traceId": "01J7AQ1K8Y4Q9W9GAF2B6CVR6M",
  "timestamp": "2026-09-08T14:30:16.120+08:00"
}
```

### 26.3 异步任务受理响应

HTTP 状态为 `202 Accepted`：

```json
{
  "success": true,
  "code": "ACCEPTED",
  "message": "任务已受理",
  "data": {
    "taskId": "19876543219999",
    "status": "QUEUED",
    "statusUrl": "/api/v1/ai/tasks/19876543219999",
    "streamUrl": "/api/v1/ai/tasks/19876543219999/stream"
  },
  "traceId": "01J7AQ1K8Y4Q9W9GAF2B6CVR6M",
  "timestamp": "2026-09-08T14:30:16.120+08:00"
}
```

### 26.4 业务错误响应

```json
{
  "success": false,
  "code": "AI_QUOTA_EXCEEDED",
  "message": "今日 AI 分析额度已用完",
  "data": {
    "retryable": false,
    "dailyLimit": 20,
    "usedCount": 20,
    "resetsAt": "2026-09-09T00:05:00+08:00"
  },
  "traceId": "01J7AQ1K8Y4Q9W9GAF2B6CVR6M",
  "timestamp": "2026-09-08T14:30:16.120+08:00"
}
```

### 26.5 参数校验失败响应

HTTP 状态为 `400 Bad Request`：

```json
{
  "success": false,
  "code": "COMMON_VALIDATION_FAILED",
  "message": "请求参数校验失败",
  "data": {
    "violations": [
      {
        "field": "question",
        "reason": "长度不能超过 500 个字符",
        "rejectedValue": null
      },
      {
        "field": "targets",
        "reason": "对比分析需要 2 至 3 个股票标的",
        "rejectedValue": null
      }
    ]
  },
  "traceId": "01J7AQ1K8Y4Q9W9GAF2B6CVR6M",
  "timestamp": "2026-09-08T14:30:16.120+08:00"
}
```

为防止敏感信息回显，`rejectedValue` 仅对普通枚举和数值字段选择性返回；密码、Token、验证码、邮箱、手机号、用户问题和自由文本固定返回 `null`。

### 26.6 认证失败响应

HTTP 状态为 `401 Unauthorized`：

```json
{
  "success": false,
  "code": "AUTH_TOKEN_EXPIRED",
  "message": "登录状态已过期，请重新登录",
  "data": {
    "refreshAllowed": true
  },
  "traceId": "01J7AQ1K8Y4Q9W9GAF2B6CVR6M",
  "timestamp": "2026-09-08T14:30:16.120+08:00"
}
```

### 26.7 流式和文件响应例外

| 类型 | 返回约定 |
| --- | --- |
| SSE | 通过 `event` 和 JSON `data` 返回，不再包裹统一响应；建连前鉴权失败仍返回统一 JSON 错误 |
| WebSocket | 建连前 Ticket 失败使用 HTTP/统一错误；建连后使用 `ACK`、`ERROR` 和业务事件协议 |
| Excel/文件 | 成功返回二进制流和下载 Header；失败时在尚未输出文件字节前返回统一 JSON 错误 |
| `304` | 按 HTTP 标准不返回响应体 |
