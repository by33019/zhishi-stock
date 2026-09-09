# AI 智能股票分析平台数据库 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保留现有初始化 SQL 的前提下，交付可用于空库初始化和已有库升级的 MySQL 8 Flyway 迁移集。

**Architecture:** 使用 V1 基线承接现有 11 张表，V2 安全修复遗留结构，V3 至 V7 按市场、资讯、自选、AI、任务一致性分域建表。迁移不使用外键和业务种子数据，应用通过 Snowflake ID、事务、唯一索引、乐观锁和 Outbox 保证完整性。

**Tech Stack:** MySQL 8.x、Flyway、MyBatis-Plus、PowerShell 静态校验、Docker MySQL 迁移验证

---

### Task 1: 建立迁移契约测试

**Files:**
- Create: `sql/tests/validate_migrations.ps1`

- [x] 写入预期迁移文件、表名、禁止项和关键兼容字段断言。
- [x] 在迁移文件不存在时运行测试，确认因缺少 V1 至 V7 正确失败。
- [x] 后续每完成一个迁移批次都重跑测试。

### Task 2: 建立旧结构基线

**Files:**
- Create: `sql/flyway/V1__baseline_existing_schema.sql`

- [x] 从 `stock_db.sql` 提取 11 张现有表的 DDL。
- [x] 移除 `DROP TABLE` 和全部 `INSERT`，不改变基线字段语义。
- [x] 验证 V1 表集合与现有 SQL 一致。

### Task 3: 修复遗留结构

**Files:**
- Create: `sql/flyway/V2__harden_legacy_schema.sql`
- Create: `sql/checks/preflight_existing_schema.sql`

- [x] 增加迁移前只读冲突检查。
- [x] 统一旧表字符集并扩展 `block_label`。
- [x] 安全迁移日志用户引用，保留无法转换的旧值。
- [x] 归一化空邮箱并增加唯一索引。
- [x] 清理完全重复的角色关系并增加联合唯一索引。
- [x] 增加行情和系统管理核心查询索引。

### Task 4: 建立市场数据域

**Files:**
- Create: `sql/flyway/V3__create_market_domain.sql`

- [x] 创建 Provider、同步游标、交易所、证券、证券状态历史、板块和板块关系表。
- [x] 创建交易日历和版本化涨跌停规则表。
- [x] 创建分钟 OHLCV 和日 K 表及幂等唯一索引。
- [x] 验证所有金融数值均使用 `DECIMAL` 或整数。

### Task 5: 建立资讯和自选数据域

**Files:**
- Create: `sql/flyway/V4__create_news_domain.sql`
- Create: `sql/flyway/V5__create_watchlist_domain.sql`

- [x] 创建资讯来源、新闻公告和多目标关联表。
- [x] 创建自选分组和自选项表。
- [x] 用唯一索引约束来源稿件、报告关联、分组名称和同组股票幂等。

### Task 6: 建立 AI 数据域

**Files:**
- Create: `sql/flyway/V6__create_ai_domain.sql`

- [x] 创建会话、任务、任务目标、上下文快照和消息表。
- [x] 创建报告、证据、反馈和用量表。
- [x] 建立任务恢复、历史检索、证据顺序和反馈幂等索引。

### Task 7: 建立任务与一致性数据域

**Files:**
- Create: `sql/flyway/V7__create_job_and_outbox_domain.sql`
- Create: `sql/checks/post_migration_validation.sql`

- [x] 创建任务执行摘要、数据质量问题和事件 Outbox。
- [x] 增加迁移后只读完整性检查。
- [x] 覆盖缺表、缺索引、孤立关联和非法行情数据检查。

### Task 8: 编写使用说明并完成验证

**Files:**
- Create: `sql/README.md`

- [x] 说明空库和已有库的两条 Flyway 执行路径。
- [x] 说明备份、时区、字符集、ID、逻辑删除和回滚策略。
- [x] 运行 PowerShell 静态验证并确认通过。
- [x] 使用 Docker MySQL 8.0 执行 V1 至 V7，并验证空库和真实旧数据升级路径。
