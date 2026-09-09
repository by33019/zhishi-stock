# AI 智能股票分析平台数据库设计说明

## 1. 设计目标

在不改写原始 `sql/stock_db.sql` 及其初始化数据的前提下，为 AI 智能股票分析平台补齐证券主数据、交易规则、行情时序、资讯、自选、AI 研究和任务运维数据结构。数据库以 MySQL 8.x 为目标版本，通过 Flyway 管理基线和增量迁移。

## 2. 迁移策略

- 保留 `sql/stock_db.sql`，仅作为旧项目全量数据样本和兼容来源。
- `V1__baseline_existing_schema.sql` 提取现有 11 张表的纯 DDL，不包含 `INSERT`。
- 已有数据库使用 Flyway `baselineOnMigrate=true`、`baselineVersion=1` 后从 V2 升级。
- 空数据库直接由 Flyway 从 V1 顺序执行到最新版本。
- 版本化迁移只执行一次，不以 `IF NOT EXISTS` 掩盖环境漂移。
- 迁移前后分别运行只读检查脚本，先处理会阻断唯一索引创建的数据冲突。

## 3. 数据规范

- 存储引擎统一为 InnoDB，字符集统一为 `utf8mb4`。
- 业务主键使用应用生成的 `BIGINT` Snowflake ID，不使用数据库自增。
- 业务时间使用 `DATETIME(3)`，应用和数据库会话统一使用 `Asia/Shanghai`。
- 金额、价格和比例使用 `DECIMAL`，禁止使用浮点类型保存权威金融数据。
- 核心筛选字段使用关系型列；JSON 仅用于外部扩展数据、AI 上下文快照和事件载荷。
- 不创建数据库外键，使用非空、唯一索引、检查约束、事务、乐观锁和应用校验维护完整性。
- 不在数据库中保存 Redis 缓存、JWT Access Token、验证码、第三方密钥或完整供应商原始响应。

## 4. 兼容处理

- `stock_business.block_label` 从 `varchar(10)` 扩展至 `varchar(20)`。
- `sys_log.user_id` 不直接强制转换。旧字符串列重命名为 `legacy_user_ref`，新增 `BIGINT user_id`，只回填能够匹配现有用户的纯数字 ID。
- 空邮箱先归一化为 `NULL`；邮箱、角色名称、权限编码和授权标识通过活动值生成列约束未删除记录唯一，允许保留与活动记录重复的历史软删除记录。
- 用户角色和角色权限的完全重复关系先保留最小 ID，再增加联合唯一索引。
- 旧表 `deleted` 的 `1=未删除、0=已删除` 语义暂不翻转，避免破坏旧代码；新表不复用这一反向语义。
- `stock_rt_info` 保留为只读兼容数据源，新链路最新行情进入 Redis，规范分钟 OHLCV 写入 `stock_minute_bar`。

## 5. 表域划分

### 5.1 现有系统域

保留 `stock_block_rt_info`、`stock_business`、`stock_market_index_info`、`stock_outer_market_index_info`、`stock_rt_info`、`sys_log`、`sys_permission`、`sys_role`、`sys_role_permission`、`sys_user` 和 `sys_user_role`。

### 5.2 市场域

- `external_provider`：行情、新闻和 LLM Provider 的非敏感元数据与当前状态。
- `data_sync_checkpoint`：按 Provider、任务和范围保存增量游标与最后成功时间。
- `stock_exchange`：交易所、时区、币种和状态。
- `stock_security`：证券统一身份、交易所、板块、上市与特殊处理状态。
- `stock_security_status_history`：证券状态生效区间，支持历史规则回放。
- `stock_sector`、`stock_security_sector`：板块主数据和证券板块关系。
- `stock_trade_calendar`：交易日、前后交易日和当日交易时段。
- `stock_limit_rule`：按交易所、板块、证券状态和上市天数版本化涨跌停规则。
- `stock_minute_bar`：规范 1 分钟 OHLCV，至少保留 90 天。
- `stock_kline_day`：日 K 与复权类型预留，至少保留 5 年。

### 5.3 资讯域

- `news_source`：实际内容来源、授权和可用状态。
- `stock_news`：新闻与公告元数据、授权摘要、指纹和原文状态。
- `stock_news_relation`：新闻与证券、板块或市场的关联和置信度。

### 5.4 自选域

- `user_watchlist_group`：默认与自定义分组、排序和乐观锁版本。
- `user_watchlist_item`：用户、分组和证券关系，同组同证券唯一。

### 5.5 AI 域

- `ai_session`：用户研究会话和删除清理状态。
- `ai_task`：任务状态机、请求、重试、取消、心跳和错误信息。
- `ai_task_target`：市场、板块、证券等多分析目标。
- `ai_context_snapshot`：任务开始时固化的行情、资讯和资料上下文。
- `ai_message`：用户问题和 AI 回答的有序消息。
- `ai_report`：固定章节、数据截止时间、模型和提示版本。
- `ai_evidence`：报告证据编号、来源、摘要和访问状态。
- `ai_feedback`：每个用户对每份报告的一份最新反馈。
- `ai_usage`：Provider、模型、Token、延迟和成本估算。

### 5.6 任务与一致性域

- `job_execution_summary`：业务任务批次、分片、数量和结果摘要。
- `data_quality_issue`：被隔离的数据质量问题及处理状态。
- `event_outbox`：与业务事务同库提交的待发布事件，用于 Redis/AI 任务投递补偿。

## 6. 查询与索引原则

- 行情时序提供“标的 + 时间倒序”和“时间范围”访问路径。
- 新闻以发布时间倒序为主要访问路径，关联表支持按目标反查。
- 自选以用户和分组为入口，数据库唯一索引承接接口幂等。
- AI 历史以用户、更新时间、场景和状态为入口，任务目标单独建表支持按标的检索。
- Outbox 以状态、下次重试时间和创建时间扫描，任务摘要以任务名和触发时间查询。

## 7. 验证标准

- 空库可按 V1 至 V7 顺序完成迁移。
- 已有库经 V1 baseline 后可从 V2 完成升级，原始业务数据不被整体清空。
- 所有要求的数据对象、核心唯一约束和查询索引存在。
- 迁移文件不包含业务初始化数据、外键、密钥、数据库创建或数据库切换语句。
- 前置检查可识别重复邮箱、重复关系和无法映射的日志用户引用。
- 后置检查可识别缺表、缺索引、孤立业务引用和非法行情价格关系。
