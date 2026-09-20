# 知势 · AI 智能股票分析平台

面向 A 股市场的智能分析平台。核心主张：**行情与研究结论都可核验**——所有行情展示数据截止时间，所有 AI 输出必须附带证据、风险提示与免责声明。

当前处于 V1 开发期，已完成首个纵向切片（认证 + 市场总览），并跑通全栈端到端验收。任务进度见 [`TASKS.md`](./TASKS.md)，状态快照见 [`PROJECT_STATUS.md`](./PROJECT_STATUS.md)。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 前端 | Vue 3.5 · TypeScript 6 · Vite 8 · Vue Router 5 · Pinia 4 · ECharts 6 · Lucide |
| 后端 | Spring Boot 3.5.9 · Java 17 · Maven 多模块（6 个）· MyBatis-Plus · Spring Security · Spring Data Redis |
| 数据 | MySQL 8.4 · Redis 8.2 · Flyway V1–V8 |
| 测试 | 后端 JUnit 5 + Testcontainers（真实 MySQL/Redis）· 前端 Vitest + Vue Test Utils + Playwright |
| 编排 | Docker Compose：mysql → flyway → stock-api / stock-job → frontend（nginx 反代 `/api/`） |

## 快速启动

### 方式一：空库全量迁移（推荐首次体验）

MySQL 从空库启动，Flyway 顺序执行 V1–V8。

```bash
cp .env.example .env
docker-compose up -d --build
```

### 方式二：旧库升级路径

适用于"已有旧库、需要升级到 V8"的场景：MySQL 首次初始化时导入 `sql/stock_db.sql`，Flyway 把该库记为 V1 基线后只执行 V2–V8。

```bash
cp .env.example .env
docker-compose -f compose.yaml -f compose.legacy.yaml -p zhishi-legacy up -d --build
```

> ⚠️ 该路径**必须使用全新数据卷**（`docker-entrypoint-initdb.d` 仅在数据卷为空时执行）。
> 重置：`docker-compose -f compose.yaml -f compose.legacy.yaml -p zhishi-legacy down -v`

启动完成后访问 **http://localhost:8088/market**，演示账号 `demo` / `Stock@123`。

> **关于 Compose 命令**：本机环境 `docker compose` 子命令不可用，只有独立命令 `docker-compose`。
> 若你的环境两者都可用，把上述命令中的 `docker-compose` 换成 `docker compose` 即可，参数完全一致。

## 目录结构

```
.
├── backend/            Spring Boot 多模块后端（详见 backend/README.md）
│   ├── stock-common/       共享类型（ApiResponse）
│   ├── stock-system/       认证与用户域
│   ├── stock-market/       行情域
│   ├── stock-news/         资讯域
│   ├── stock-integration/  外部数据源适配层
│   ├── stock-backend/      Web 入口
│   └── stock-job/          定时任务
├── frontend/           Vue 3 前端（详见 frontend/README.md）
├── sql/
│   ├── flyway/             Flyway 迁移脚本 V1–V8
│   ├── stock_db.sql        旧库样本（已裁剪，用于升级路径）
│   ├── checks/             迁移前后校验脚本
│   └── tools/              旧库样本裁剪工具
├── docs/               产品与架构文档
├── compose.yaml        空库全量迁移编排
├── compose.legacy.yaml 旧库升级路径覆盖文件
├── TASKS.md            任务唯一真相源
└── PROJECT_STATUS.md   项目状态快照
```

## 常用命令

```bash
# 前端
cd frontend
npm install
npm run dev              # 开发服务器 http://localhost:5173
npm run typecheck        # 类型检查
npx vitest --configLoader runner --run   # 单元测试
npm run build            # 生产构建
E2E_BASE_URL=http://127.0.0.1:8088 npm run e2e:real   # 端到端验收（需先起容器）

# 后端
mvn -f backend/pom.xml test                            # 全量测试（需 Docker）
mvn -f backend/pom.xml -pl stock-backend -am package    # 只打某个模块

# 数据库校验（旧库升级路径启动后）
docker exec -i zhishi-legacy-mysql-1 mysql -ustock -p<密码> stock_system \
  < sql/checks/post_migration_validation.sql
```

> 前端的 `--configLoader runner` 参数是必需的：项目的 Vite 配置依赖它才能正确加载。

## 核心架构约束

以下约束来自产品与架构设计，**不随实现阶段改变**：

1. **前端不直连任何数据源**（MySQL / Redis / 行情源 / 资讯源 / LLM），只访问 Spring Boot 接口。
2. **AI 由 Spring Boot 编排第三方 LLM**，V1 不拆分 FastAPI 服务。
3. **MySQL 不使用外键**，一致性由应用层事务 + 唯一索引 / 乐观锁 + `event_outbox` 保证。
4. **统一返回壳**：`ApiResponse{success, code, message, data, traceId, timestamp}`。
5. **A 股视觉约定**：红涨绿跌。
6. **业务 ID 使用 Snowflake 字符串**，前端全程按 string 处理。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [`docs/AI智能股票分析平台-PRD.md`](./docs/AI智能股票分析平台-PRD.md) | 产品需求 |
| [`docs/Architecture.md`](./docs/Architecture.md) | 系统架构设计 |
| [`docs/RESTful-API.md`](./docs/RESTful-API.md) | 接口契约 |
| [`docs/superpowers/plans/`](./docs/superpowers/plans/) | 实施计划与里程碑 |
| [`TASKS.md`](./TASKS.md) | 任务清单（唯一真相源） |
| [`PROJECT_STATUS.md`](./PROJECT_STATUS.md) | 项目状态与已知问题 |
| [`CHANGELOG.md`](./CHANGELOG.md) | 变更记录 |
| [`AGENTS.md`](./AGENTS.md) | 协作约定（含提交信息格式） |

## 当前状态

- ✅ 数据库迁移：空库与旧库升级**两条路径均已验证**
- ✅ 后端 45 测试全绿 · 前端 35 测试全绿
- ✅ 全栈 Compose 端到端验收通过（市场 API、登录、Cookie 恢复、退出、路由保护）
- 🟡 前端 11 个路由中，`/market` 与 `/login` 已接真实 API，其余 9 个仍走 Mock
- 🟡 真实行情源 / 资讯源 / LLM Provider 尚未接入，当前使用确定性模拟实现

## 协作约定

提交信息格式为 `类型：中文描述`，例如 `功能：完成市场总览前端界面`。
详见 [`AGENTS.md`](./AGENTS.md)。
