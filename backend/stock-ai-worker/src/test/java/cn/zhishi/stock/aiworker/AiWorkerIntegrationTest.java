package cn.zhishi.stock.aiworker;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.ai.domain.AiContextSnapshotStore;
import cn.zhishi.stock.ai.domain.AiContextTarget;
import cn.zhishi.stock.ai.domain.AiMessageStore;
import cn.zhishi.stock.ai.domain.AiReport;
import cn.zhishi.stock.ai.domain.AiReportStore;
import cn.zhishi.stock.ai.domain.AiScene;
import cn.zhishi.stock.ai.domain.AiTargetRole;
import cn.zhishi.stock.ai.domain.AiTargetType;
import cn.zhishi.stock.ai.domain.AiTask;
import cn.zhishi.stock.ai.domain.AiTaskEvent;
import cn.zhishi.stock.ai.domain.AiTaskEventStream;
import cn.zhishi.stock.ai.domain.AiTaskEventType;
import cn.zhishi.stock.ai.domain.AiTaskQueue;
import cn.zhishi.stock.ai.domain.AiTaskQueueMessage;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 执行器的真实基础设施集成测试。
 *
 * <h2>它补的是哪一块空白</h2>
 * 本模块此前的 4 个测试类**都不启动真上下文**：一个只读注解、一个用
 * {@code ApplicationContextRunner} 直接注册配置类、两个用 Mockito。
 * 而 {@code ApplicationContextRunner} **不走 {@code @MapperScan}**，
 * 所以"扫描范围漏了某个包"这类问题它永远看不到——线上症状是整个应用起不来
 * （{@code No qualifying bean of type ...Mapper available}），
 * 而报错指向的是配置类（M3-04 踩过一次）。
 *
 * <p>本模块恰好踩在最危险的位置上：{@code @MapperScan} 要覆盖
 * {@code cn.zhishi.stock.ai} 与 {@code cn.zhishi.stock.news} **两个**包、
 * 装配了 30 多个 Bean、而且是**非 Web 应用**（少了 Web 那套自动配置的兜底）。
 * 所以这里第一件事就是让完整上下文在真库上起来。
 *
 * <h2>第二件事：把「入队 → 消费 → 落报告」整条链路跑一遍</h2>
 * 这是 M3-07 的核心，而且**不需要 HTTP 层**就能验证：
 * 直接往 {@code ai_task} 写一条 {@code QUEUED} 任务、往队列投一次、调一次
 * {@link AiTaskConsumer#poll()}，然后查五张表。
 *
 * <p>刻意**不**经 {@code AiTaskService} 造任务：那是 Web 侧的用例，
 * 本模块有意不装配它（见 {@code AiWorkerConfigurationTest}）。
 *
 * <h2>它第一次跑就抓到两个单测看不见的缺陷</h2>
 * <ol>
 *   <li>{@code RedisAiTaskQueue} 用 {@code Map<byte[], byte[]>.get(byte[])} 取字段，
 *       而数组的 {@code equals} 是引用相等 → 每条消息都被当成"缺少 taskId"丢弃并 ACK。
 *       Redis 那一侧（{@code XLEN}、{@code last-delivered-id}、{@code XPENDING}）全都正常，
 *       只有任务永远停在 {@code QUEUED}。
 *   <li>执行器拿到的目标来自数据库，而 {@code ai_task_target} 只存 bigint 代理键，
 *       对外标识为空；{@code AiContextBuilder} 却要用对外标识取行情
 *       → 每个任务都会以 {@code AI_CORE_DATA_MISSING} 失败。
 * </ol>
 * 两个都不会让任何单测变红，因为它们只在"真的读一次队列""真的从库里读回目标"时才成立。
 *
 * <h2>为什么要把调度推到未来</h2>
 * {@code StockAiWorkerApplication} 上有 {@code @EnableScheduling}，所以上下文一启动，
 * {@link AiTaskConsumer} 就开始每 1 秒轮询。它与测试**争抢同一条队列消息**：
 * 测试刚投递完，后台那一轮就可能先把它取走并 ACK，于是测试自己的
 * {@code poll()} 什么也取不到。把两个调度器的首次延迟与间隔都设成一小时，
 * 测试就拿到了独占的队列。
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "stock.ai.worker.poll-initial-delay-ms=3600000",
        "stock.ai.worker.poll-delay-ms=3600000",
        "stock.ai.worker.recovery-initial-delay-ms=3600000",
        "stock.ai.worker.recovery-delay-ms=3600000"
})
@Testcontainers
class AiWorkerIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.2-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        String migrations = Path.of("..", "..", "sql", "flyway")
                .toAbsolutePath().normalize().toString().replace('\\', '/');
        registry.add("spring.flyway.locations", () -> "filesystem:" + migrations);
    }

    private static final AtomicLong IDS = new AtomicLong(9_900_000_000_000L);

    @Autowired AiTaskStore tasks;
    @Autowired AiTaskQueue queue;
    @Autowired AiReportStore reports;
    @Autowired AiContextSnapshotStore snapshots;
    @Autowired AiMessageStore messages;
    @Autowired AiTaskEventStream events;
    @Autowired AiTaskConsumer consumer;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    /**
     * 完整上下文能在真库上起来 —— 装配、双包 {@code @MapperScan}、
     * 非 Web 应用的自动配置三件事同时被证明。
     */
    @Test
    @DisplayName("完整上下文在真库上启动：装配与双包扫描都没问题")
    void bootsTheWholeWorkerContext() {
        assertThat(tasks).isNotNull();
        assertThat(queue).isNotNull();
        assertThat(consumer).isNotNull();
    }

    /**
     * 队列的核心契约：投进去的一条消息，{@code receive} 必须能取到它，
     * 并且把 {@code taskId} 解析出来。
     *
     * <p>这条测试是必要的：此前没有任何测试碰过 {@code RedisAiTaskQueue} 的真实实现
     * （{@code AiTaskConsumerTest} 用的是桩队列），而"投递→取回"这条路上
     * 字段名、**字节数组的相等语义**、消费组偏移任何一处不对，都只会表现为
     * 任务卡在排队中——Redis 那一侧看起来完全健康。
     */
    @Test
    @DisplayName("投递一条消息后能取回它，且 taskId 解析正确")
    void receivesWhatWasEnqueued() {
        AiTask task = queuedTask(IDS.incrementAndGet(), IDS.incrementAndGet());
        tasks.insert(task);
        queue.enqueue(task.taskId());

        assertThat(redis.opsForStream().size("stream:ai:tasks"))
                .describedAs("XADD 之后 stream 里必须有这一条")
                .isPositive();

        List<AiTaskQueueMessage> messages = queue.receive(5);

        assertThat(messages)
                .describedAs("投递后必须能取回；取不到的话 worker 会安静地什么都不做")
                .extracting(AiTaskQueueMessage::taskId)
                .contains(task.taskId());
    }

    /**
     * 核心链路：一条已入队的任务被消费一次之后，五张表都有真实行。
     *
     * <p>这里的任务**刻意**先带对外标识 {@code sim-600519} 写入、再从库里读回来
     * （{@code ai_task_target} 只存 bigint 代理键与代码快照，所以读回来时对外标识为空）。
     * 这正是生产路径的形状——执行器拿到的目标就是这一份。
     */
    @Test
    @DisplayName("入队一条任务 → 消费一次 → 完成并落报告、快照、助手消息、事件流")
    void consumesAnEnqueuedTaskAndPersistsTheWholeChain() {
        long userId = IDS.incrementAndGet();
        long sessionId = IDS.incrementAndGet();
        AiTask task = queuedTask(userId, sessionId);
        tasks.insert(task);
        queue.enqueue(task.taskId());

        consumer.poll();

        AiTask stored = tasks.find(task.taskId()).orElseThrow();
        assertThat(stored.status())
                .describedAs("任务必须真的跑完，而不是停在排队中或失败；实际 errorCode=%s message=%s",
                        stored.errorCode(), stored.errorMessage())
                .isEqualTo(AiTaskStatus.COMPLETED);
        assertThat(stored.attemptNo()).isEqualTo(1);
        assertThat(stored.completedAt()).isNotNull();
        assertThat(stored.firstChunkAt()).isNotNull();

        AiReport report = reports.findByTask(task.taskId()).orElseThrow();
        assertThat(report.taskId()).isEqualTo(task.taskId());
        assertThat(report.sessionId()).isEqualTo(sessionId);
        assertThat(report.renderedMarkdown()).contains("## 核心结论").contains("## 免责声明");
        assertThat(report.marketDataCutoffAt()).isNotNull();

        assertThat(snapshots.countByTask(task.taskId()))
                .describedAs("上下文快照必须落库：它就是「报告基于哪一刻的数据」的证据")
                .isPositive();
        assertThat(messages.countBySession(sessionId)).isPositive();

        // 来源引用也必须随报告一起落下（HIS-07 的写入侧）。
        // 这条只能在真库上验：整批 INSERT 的多值 VALUES、两个枚举与 VARCHAR 列的
        // 映射、以及 uk_ai_evidence_report_no 是否真的建对了——桩全都看不见。
        assertThat(evidenceRowsOf(task.taskId()))
                .describedAs("报告写下时，它的来源引用必须一起落库")
                .isPositive();

        assertThat(events.readAfter(task.taskId(), 0, 200))
                .extracting(event -> event.type())
                .contains(AiTaskEventType.STATUS, AiTaskEventType.CHUNK,
                        AiTaskEventType.REPORT, AiTaskEventType.DONE);
    }

    /** {@code ai_report} 没有 {@code user_id}，所以按 {@code task_id} 经报告表关联。 */
    private int evidenceRowsOf(long taskId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_evidence e"
                        + " JOIN ai_report r ON r.id = e.report_id"
                        + " WHERE r.task_id = ?",
                Integer.class,
                taskId);
    }

    /**
     * 同一任务被投递两次，只产生一个报告。
     *
     * <p>队列语义是「至少一次」，所以重投是**正常形态**而不是异常：执行者在 ACK 前挂掉、
     * 或者恢复扫描把心跳过期的任务重新投出去，都会产生第二条消息。
     * 挡在中间的是 {@code AiTaskStore.claimForExecution} 的条件更新——
     * 第二次投递拿不到执行权，于是**一次模型调用都不会发生**。
     */
    @Test
    @DisplayName("同一任务重投两次：只有一份报告，且第二次不推进版本")
    void redeliveryDoesNotProduceASecondReport() {
        long userId = IDS.incrementAndGet();
        long sessionId = IDS.incrementAndGet();
        AiTask task = queuedTask(userId, sessionId);
        tasks.insert(task);

        queue.enqueue(task.taskId());
        consumer.poll();
        AiTask afterFirst = tasks.find(task.taskId()).orElseThrow();
        int evidenceAfterFirst = evidenceRowsOf(task.taskId());

        queue.enqueue(task.taskId());
        consumer.poll();
        AiTask afterSecond = tasks.find(task.taskId()).orElseThrow();

        assertThat(afterFirst.status()).isEqualTo(AiTaskStatus.COMPLETED);
        assertThat(afterSecond.status()).isEqualTo(AiTaskStatus.COMPLETED);
        assertThat(afterSecond.attemptNo())
                .describedAs("第二次没抢到执行权，尝试次数不该再涨")
                .isEqualTo(afterFirst.attemptNo());
        assertThat(afterSecond.version())
                .describedAs("第二次一次写库都没发生")
                .isEqualTo(afterFirst.version());
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ai_report WHERE task_id = ?",
                        Integer.class,
                        task.taskId()))
                .isEqualTo(1);
        // 证据与报告同生：重复投递也不能让来源翻倍（uk_ai_evidence_report_no 的职责）。
        // 先断言"第一次确实写了"，否则两边都是 0 时下面那条相等断言会毫无意义地通过。
        assertThat(evidenceAfterFirst)
                .describedAs("第一次投递就该把来源引用写下来")
                .isPositive();
        assertThat(evidenceRowsOf(task.taskId()))
                .describedAs("定稿只发生一次，证据行数不该因为重投而翻倍")
                .isEqualTo(evidenceAfterFirst);
    }

    /**
     * 事件流的核心契约：追加之后能按序号读回，游标语义正确，{@code latestSequence} 能取到。
     *
     * <p>这条同样必要：{@code RedisAiTaskEventStream} 里取字段用的是
     * {@code Map<byte[], byte[]>.get(byte[])}，而数组的 {@code equals} 是引用相等，
     * 于是**所有事件都被跳过**——{@code readAfter} 永远返回空、{@code latestSequence}
     * 永远为空，SSE 中继一条事件都发不出去，而 {@code XLEN} 在正常增长。
     * 它和队列那个缺陷是同一类，也同样是单测（桩）看不见的。
     */
    @Test
    @DisplayName("事件流：追加后能按序号读回，游标从上一序号之后继续，latestSequence 可查")
    void readsBackAppendedEvents() {
        long taskId = IDS.incrementAndGet();
        events.append(taskId, AiTaskEventType.STATUS, sequence -> "{\"sequence\":" + sequence + "}");
        events.append(taskId, AiTaskEventType.CHUNK, sequence -> "{\"sequence\":" + sequence + "}");
        events.append(taskId, AiTaskEventType.DONE, sequence -> "{\"sequence\":" + sequence + "}");

        assertThat(events.readAfter(taskId, 0, 10))
                .describedAs("从头读：三条都要回来")
                .extracting(AiTaskEvent::type)
                .containsExactly(AiTaskEventType.STATUS, AiTaskEventType.CHUNK, AiTaskEventType.DONE);

        assertThat(events.readAfter(taskId, 1, 10))
                .describedAs("Last-Event-ID=1：只补发它之后的两条")
                .extracting(AiTaskEvent::type)
                .containsExactly(AiTaskEventType.CHUNK, AiTaskEventType.DONE);

        assertThat(events.latestSequence(taskId)).hasValue(3L);
    }

    // ---------- 夹具 ----------

    private AiTask queuedTask(long userId, long sessionId) {
        OffsetDateTime now = OffsetDateTime.now(clock).withNano(0);
        return new AiTask(
                IDS.incrementAndGet(),
                UUID.randomUUID().toString(),
                sessionId,
                userId,
                null,
                AiScene.STOCK,
                "集成测试：怎么看",
                now.minusDays(1),
                now,
                AiTaskStatus.QUEUED,
                false,
                0,
                2,
                "SIMULATED",
                "sim-analyst-v1",
                "trace-it",
                now,
                now,
                null,
                null,
                null,
                null,
                now.plusSeconds(60),
                null,
                null,
                null,
                null,
                0,
                List.of(new AiContextTarget(
                        AiTargetType.SECURITY, "sim-600519", "600519", "集成测试证券",
                        AiTargetRole.PRIMARY, 600_519L)));
    }
}
