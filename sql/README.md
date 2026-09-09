# AI 智能股票分析平台数据库脚本

## 目录说明

- `stock_db.sql`：原项目 SQL，包含 11 张旧表和历史初始化数据，保持原样。
- `flyway/`：MySQL 8.x 的版本化结构迁移，生产环境唯一结构变更入口。
- `checks/preflight_existing_schema.sql`：已有数据库升级前的只读冲突检查。
- `checks/post_migration_validation.sql`：全部迁移后的只读完整性检查。
- `tests/validate_migrations.ps1`：不依赖数据库的迁移结构契约测试。

## 环境约束

- MySQL 最低版本为 8.0.16，推荐 MySQL 8.4 LTS，原因是迁移使用已强制执行的 `CHECK` 约束和 JSON。
- 数据库默认字符集使用 `utf8mb4`，排序规则使用 `utf8mb4_general_ci`；代码、哈希和状态字段按需使用 `ascii_bin` 或 `utf8mb4_bin`。
- 应用、JDBC 连接和定时任务统一使用 `Asia/Shanghai`。表中的 `DATETIME` 不携带时区，均按该业务时区解释。
- 业务 ID 由应用使用 MyBatis-Plus `ASSIGN_ID` 或等价 Snowflake 实现生成，迁移中不使用自增列。
- 数据库不保存 Redis 缓存、验证码、JWT Access Token、第三方密钥或完整第三方原始响应。

## 空数据库初始化

1. 创建一个字符集为 `utf8mb4` 的空 schema，数据库名称由部署环境配置决定。
2. 将 Flyway location 指向 `filesystem:sql/flyway`，不要先执行 `stock_db.sql`。
3. 从 V1 顺序执行至 V7。
4. 运行 `checks/post_migration_validation.sql`，除“未映射旧日志引用”外的异常明细应为空，`foreign_key_count` 应为 0。
5. 证券、交易日历、涨跌停规则、Provider、用户和权限数据由应用引导或授权数据同步产生，本目录不写入业务种子数据。

## 已有数据库升级

1. 对现有 schema 做可恢复备份，并在隔离环境完成一次恢复演练。
2. 运行 `checks/preflight_existing_schema.sql`。
3. 先处理被标记为 blocking 的重复邮箱、角色名、权限编码或权限标识，不允许迁移脚本静默合并这些业务数据。
4. 配置 Flyway `baselineOnMigrate=true`、`baselineVersion=1`，让已有 11 表数据库记录为 V1 基线。
5. 执行 V2 至 V7。禁止在已有数据库上再次执行 V1，也禁止先导入 `stock_db.sql` 后让 Flyway 从 V1 建表。
6. 运行 `checks/post_migration_validation.sql` 并核对所有异常明细。

## 版本职责

| 版本 | 职责 |
| --- | --- |
| V1 | 现有 11 张表纯 DDL 基线，不包含原始插入数据 |
| V2 | 旧表字符集、字段兼容、唯一约束和查询索引修复 |
| V3 | Provider、证券、板块、交易日历、涨跌停和 K 线 |
| V4 | 授权资讯来源、新闻公告、去重和业务关联 |
| V5 | 自选分组和自选证券 |
| V6 | AI 会话、任务、上下文、报告、证据、反馈和用量 |
| V7 | 定时任务摘要、数据质量问题和事务 Outbox |

## 关键数据约定

- 旧表 `sys_user`、`sys_role`、`sys_permission` 的 `deleted` 保留遗留语义：`1` 表示未删除，`0` 表示已删除。邮箱、角色名、权限编码和授权标识只在未删除记录范围内唯一；新表不使用该反向逻辑删除字段。
- `sys_log.legacy_user_ref` 永久保留无法映射的历史用户引用；新日志只写 `BIGINT user_id`。
- `stock_rt_info` 是旧查询兼容表，不再作为新行情链路的高频主写表。
- 最新报价保存于 Redis；同一分钟内的报价聚合完成后幂等写入 `stock_minute_bar`。
- `stock_minute_bar.trade_volume` 和 `stock_kline_day.trade_volume` 统一以股为单位，成交金额统一以人民币元为单位。
- 比例字段使用小数比例，`0.10` 表示 `10%`，前端展示时再格式化。
- 周 K 和月 K 默认从日 K 按交易日历查询聚合；MVP 不单独建周/月表。
- 分钟行情默认保留至少 90 天。正式达到全市场采集规模前，应通过容量测试确定按月分区或冷热表方案，并以新的 Flyway 版本实施，不在应用启动时自动执行分区 DDL。

## 事务与一致性

- 不创建数据库外键。业务写入必须在应用层校验对象存在性和所有权，并使用本地事务保证原子性。
- 用户、角色、权限、自选和 AI 状态更新使用唯一索引与乐观锁处理并发。
- AI 任务创建、缓存失效等需要向 Redis 发布的事件，与业务记录在同一事务写入 `event_outbox`。
- Outbox 消费者至少一次投递，接收方以 `event_id` 或业务幂等键去重。
- Redis 数据丢失后，最终业务事实从 MySQL 恢复，不把缓存写入成功当作业务提交成功。

## 回滚与发布

- MySQL DDL 会隐式提交，Flyway 版本迁移不依赖自动事务回滚。
- 上线前必须备份；失败时优先修复并前滚，不手工删除 `flyway_schema_history` 记录。
- 破坏性字段删除、列重命名第二阶段和历史数据清理应使用“扩展、迁移、切换、收缩”流程，不能与应用切换在同一次迁移中完成。
- `stock_minute_bar` 的分区和保留策略属于运维容量变更，应通过后续独立 Flyway 版本发布。

## 验证命令

在项目根目录执行静态契约测试：

```powershell
pwsh -NoProfile -File sql/tests/validate_migrations.ps1
```

校验脚本保存为无 BOM UTF-8，请使用 PowerShell 7 或更高版本；Windows PowerShell 5.1 会按本地代码页读取脚本并可能产生中文解析错误。

数据库迁移完成后，再使用 MySQL 客户端依次执行：

```text
sql/checks/preflight_existing_schema.sql   # 仅已有库升级前
sql/checks/post_migration_validation.sql   # 所有环境迁移后
```
