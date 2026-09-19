# CHANGELOG.md — 知势平台变更记录

> 本文件记录所有值得关注的变更。
> 格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)；
> 提交信息约定见 `AGENTS.md`（`类型：中文描述`）。
> 条目分类：新增 / 变更 / 修复 / 移除 / 文档 / 工程。
>
> 项目当前处于 V1 开发期，**尚未发布正式版本**（无 git tag），因此按日期分段记录。
> 任务编号（如 M1-10）对应 `TASKS.md`。

---

## [未发布]

### 移除

- 移除未使用的 `element-plus` 依赖：全仓库零引用，且与自建设计系统定位冲突（M1-10）

### 文档

- 补建根 `README.md`、`backend/README.md`、`frontend/.env.example`、本文件（M1-09 / M1-13）
- 统一文档中的 Compose 调用方式说明（M1-15）

---

## 2026-09-19 — 工程地基与全栈端到端验收

### 新增

- **CI 工作流**（`.github/workflows/ci.yml`）：3 个作业（backend / frontend / sql），push 与 PR 触发，并发运行自动取消旧任务
- **旧库升级路径**：`compose.legacy.yaml`（initdb 导入旧库 + Flyway baseline）与 `sql/tools/slim_legacy_dump.py`（可复现裁剪工具，仅标准库、幂等）
- **任务载体**：`TASKS.md`（任务唯一真相源）、`PROJECT_STATUS.md`（状态快照）
- **交付路线图**：`docs/superpowers/plans/2026-09-19-mvp-delivery-roadmap.md`（M1 / M2 / M3 共 32 个任务）
- 迁移后校验脚本 `sql/checks/post_migration_validation.sql` 补入 V8 的 `market_overview_snapshot`（期望表 39 → 40，修复契约漂移）
- Maven 国内镜像配置 `backend/settings.xml`（阿里云公共仓库）

### 变更

- 旧库样本 `sql/stock_db.sql` 由 **24,243,860 字节裁剪为 149,480 字节（0.6%）**：保留全部 DDL 与 RBAC / 日志数据，仅对 `stock_rt_info`、`stock_market_index_info`、`stock_block_rt_info` 三张大表采样

### 修复

- **容器内依赖下载中断导致镜像构建随机失败**。根因：容器网络在高并发 HTTPS 下载下存在约 **3% 偶发连接中断**（已排除 MTU 与链路本身，单文件可完整下载）。一次构建需拉取数百个构件，累积失败率接近必然。
  - 后端：阿里云镜像 + Maven wagon 重试 `count=5`（`backend/Dockerfile`）
  - 前端：国内 npm 镜像 + 限制并发 `--maxsockets=5` + 拉长超时 + 3 次重试，且失败时不清理 npm 缓存以支持增量续传（`frontend/Dockerfile`）
- 完善 MySQL 连接参数（`allowPublicKeyRetrieval=true`）与市场总览验收选择器

### 验收

- 后端 **45 测试全绿**（Testcontainers 真实 MySQL 8.4 + Redis）；前端 **35 测试全绿**
- 数据库**空库与旧库升级两条路径均通过**：旧库路径 `baseline v1` → 应用 V2–V8 → `now at version v8`，`foreign_key_count = 0`，41 张表
- **全栈 Compose 端到端验收通过**：`npm run e2e:real` 覆盖市场 API、登录、Cookie 恢复、退出与路由保护

---

## 2026-09-13 ~ 09-14 — 首个纵向切片（认证 + 市场总览）

### 新增

- 后端基础工程：Maven 多模块 6 个（`stock-common` / `stock-system` / `stock-market` / `stock-integration` / `stock-backend` / `stock-job`）
- 认证会话闭环：JWT access token（前端内存）+ opaque refresh token（httpOnly / SameSite=Strict cookie，Redis 轮换）
- 市场总览纵向闭环：`SimulatedQuoteProvider` 确定性模拟行情 → Redis 缓存 → MySQL 快照
- 本地基础设施编排：`compose.yaml`（mysql → flyway → stock-api / stock-job → frontend nginx 反代 `/api/`）
- 前端登录页与市场总览**接入真实 API**（其余 9 个页面仍走 `mockApi`）
- 数据库迁移 Flyway **V1–V8**

### 修复

- 加固认证与纵向闭环契约：统一业务 ID 为 Snowflake 字符串（前端全程 string，避免精度丢失）、完善全局异常处理与统一返回壳、收紧安全配置

---

## 2026-09-09 ~ 09-10 — 前端设计系统与登录页

### 新增

- 前端项目基线（Vue 3.5 / TypeScript 6 / Vite 8 / Vue Router / Pinia / ECharts）
- 全局字体层级体系与可读性浏览器回归
- 登录页一体化重构：统一内容结构、协议语义与移动端边界

### 文档

- 前端字体层级优化规范、任务拆解与验证记录
- 登录页一体化重构方案与实施步骤

---

## 2026-09-09 — 项目基线

### 新增

- 初始化知势股票平台项目基线
