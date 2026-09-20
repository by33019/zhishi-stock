# 知势后端

AI 智能股票分析平台的服务端。Spring Boot 3.5.9 / Java 17 / Maven 多模块，对外提供统一的 REST 接口。

## 模块结构

| 模块 | 职责 | 规模 |
| --- | --- | --- |
| `stock-common` | 跨模块共享类型，如统一返回壳 `ApiResponse` | 2 个类 |
| `stock-system` | 认证与用户域：登录、JWT、refresh token 轮换、权限 | 33 个类 |
| `stock-market` | 行情域：市场总览查询、快照与缓存编排 | 15 个类 |
| `stock-integration` | 外部数据源适配层（Provider 接口与实现） | 2 个类 |
| `stock-backend` | Web 入口：控制器、安全配置、全局异常处理 | 19 个类 |
| `stock-job` | 定时任务：行情采集（默认每 60s 一次） | 5 个类 |

依赖方向：`stock-backend` / `stock-job` → 各业务模块 → `stock-common`。业务模块之间不互相依赖。

## 构建与测试

```bash
# 编译
mvn -f backend/pom.xml -DskipTests package

# 全量测试（需 Docker：集成测试用 Testcontainers 起真实 MySQL 8.4 + Redis）
mvn -f backend/pom.xml test

# 只跑某个模块
mvn -f backend/pom.xml -pl stock-market -am test
```

当前状态：**45 个测试全绿**（22 个测试类），其中集成测试会拉起真实 MySQL 与 Redis 容器。

> **Windows + Git Bash 注意**：Maven 自带的 `bin/mvn`（bash 脚本）在 MinGW 下会因路径转换缺陷抛
> `ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher`。
> 本机已修复该脚本（两处 `if $cygwin` 改为 `if $cygwin || $mingw`，原脚本备份为 `bin/mvn.orig-backup`）；
> 若换机器，可改用 `mvn.cmd`。
>
> **依赖下载不稳定时**：仓库内 `backend/settings.xml` 已配置阿里云公共仓库镜像，
> 构建时加 `-s backend/settings.xml` 可显著提速并避免连接中断。

## 配置项

所有配置均通过环境变量注入，带默认值可直接本地启动。

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP 端口（仅 `stock-backend`） |
| `DB_URL` | `jdbc:mysql://localhost:3306/stock_system?...` | MySQL 连接串 |
| `DB_USERNAME` | `stock` | 数据库用户 |
| `DB_PASSWORD` | 空 | 数据库密码 |
| `REDIS_HOST` | `localhost` | Redis 主机 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_PASSWORD` | 空 | Redis 密码 |
| `FLYWAY_ENABLED` | `false` | 应用内是否执行迁移（容器编排中由独立 flyway 服务负责） |
| `JWT_SECRET` | 空 | JWT 签名密钥，**生产必须显式设置** |
| `COOKIE_SECURE` | `true` | refresh cookie 是否仅走 HTTPS；本地 http 联调需设为 `false` |
| `MARKET_SCENARIO` | `NORMAL` | 模拟行情场景（`NORMAL` / 其它确定性场景） |
| `MARKET_HOLIDAYS` | 空 | 模拟交易日历的节假日集合，逗号分隔 ISO 日期。**采集与查询共用**，两边必须一致 |
| `DEMO_USERNAME` / `DEMO_PASSWORD` | `demo` / `Stock@123` | 演示账号种子 |
| `MARKET_COLLECT_INITIAL_DELAY_MS` | `1000` | 行情采集首次延迟（仅 `stock-job`） |
| `MARKET_COLLECT_DELAY_MS` | `60000` | 行情采集间隔（仅 `stock-job`） |

## 启动方式

推荐用仓库根目录的 Compose 编排（会一并拉起 MySQL、Redis 并执行 Flyway 迁移）：

```bash
cd ..
docker-compose up -d --build
```

单独启动 `stock-backend`（需自备 MySQL / Redis）：

```bash
mvn -f backend/pom.xml -pl stock-backend -am spring-boot:run
```

## 约定

- **统一返回壳**：`ApiResponse{success, code, message, data, traceId, timestamp}`，所有接口一致。
- **业务 ID**：Snowflake 字符串。前端全程按 string 处理，避免 JS 精度丢失。
- **比例字段**：小数表示（`0.10` = 10%）；成交量单位为股，成交金额单位为元。
- **数据库无外键**：一致性由应用层事务 + 唯一索引 / 乐观锁 + `event_outbox` 保证。
- **迁移**：Flyway `sql/flyway/` V1–V8；不要在应用内执行 DDL。
- **AI 输出**：必须包含证据、数据截止时间、风险提示与免责声明（由编排层保证）。

## 外部依赖现状

真实行情源、资讯源与 LLM Provider **均未接入**。当前通过 `QuoteProvider` 式可插拔接口 +
确定性模拟实现（`SimulatedQuoteProvider`）落地，真实适配器后置。前端不直连任何数据源。
