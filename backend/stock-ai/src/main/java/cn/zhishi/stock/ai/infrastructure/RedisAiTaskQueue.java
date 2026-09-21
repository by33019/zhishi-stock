package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiTaskQueue;
import cn.zhishi.stock.ai.domain.AiTaskQueueMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link AiTaskQueue} 的 Redis Stream 实现（Consumer Group {@code ai-worker}）。
 *
 * <h2>投递语义是「至少一次」，但「谁在做」以数据库为准</h2>
 * 本实现**不消费 pending 列表**：消息被取走而消费者在 ACK 前挂掉时，
 * Redis 不会自动重投，任务改由恢复扫描按心跳超时重新投递。
 *
 * <p>这样做的代价是 pending 会缓慢累积；收益是"谁在做"只有一处事实来源。
 * 若反过来依赖 pending 重投，就会出现"Redis 认为有人在做、数据库认为没人做"的分叉，
 * 而分叉的表现是任务永远停在 {@code PREPARING}——不会报错，只会卡住。
 *
 * <h2>队列不做长度裁剪（已知取舍）</h2>
 * {@code MAXLEN} 裁剪会在消费者长时间离线时**静默丢弃**未处理的任务。
 * 相比之下"键一直变长"至少是可观测的（{@code XLEN}），因此这里选择不裁剪，
 * 把运维层面的 {@code XTRIM} 留给部署方。已记入已知问题。
 *
 * <h2>为什么用连接层的 Stream 命令</h2>
 * 与 {@code RedisMarketOverviewStore} 一致，直接用 {@code StringRedisTemplate}；
 * 连接层命令避免了为 {@code MapRecord} 的泛型参数做无意义的适配。
 * {@code redis.execute} 有两个同为单参数函数式接口的重载，因此每个 lambda
 * **必须显式标注** {@link RedisCallback}，否则编译期歧义。
 */
public class RedisAiTaskQueue implements AiTaskQueue {

    /** 队列键，属于实现细节（与 V6 无关）。 */
    public static final String STREAM_KEY = "stream:ai:tasks";

    public static final String GROUP = "ai-worker";

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisAiTaskQueue.class);

    private static final byte[] KEY = STREAM_KEY.getBytes(StandardCharsets.UTF_8);
    private static final byte[] FIELD_TASK_ID = "taskId".getBytes(StandardCharsets.UTF_8);

    private final StringRedisTemplate redis;
    private final String consumerName;

    public RedisAiTaskQueue(StringRedisTemplate redis, String consumerName) {
        this.redis = redis;
        this.consumerName = consumerName;
    }

    @Override
    public void enqueue(long taskId) {
        redis.execute((RedisCallback<RecordId>) connection -> connection.streamCommands().xAdd(
                KEY, Map.of(FIELD_TASK_ID, Long.toString(taskId).getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public List<AiTaskQueueMessage> receive(int count) {
        ensureGroup();
        List<ByteRecord> records = redis.execute((RedisCallback<List<ByteRecord>>) connection ->
                connection.streamCommands().xReadGroup(
                        Consumer.from(GROUP, consumerName),
                        StreamReadOptions.empty().count(count),
                        StreamOffset.create(KEY, ReadOffset.lastConsumed())));
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<AiTaskQueueMessage> messages = new ArrayList<>();
        for (ByteRecord record : records) {
            Map<byte[], byte[]> value = record.getValue();
            byte[] raw = value == null ? null : value.get(FIELD_TASK_ID);
            if (raw == null) {
                // 没有 taskId 的消息无法执行。ACK 掉它而不是留在 pending 里——
                // 留着的唯一后果是每次恢复扫描都要再看它一眼。
                LOGGER.warn("丢弃缺少 taskId 的 AI 任务消息：id={}", record.getId().getValue());
                ack(record.getId().getValue());
                continue;
            }
            messages.add(new AiTaskQueueMessage(
                    record.getId().getValue(),
                    Long.parseLong(new String(raw, StandardCharsets.UTF_8)),
                    1L));
        }
        return List.copyOf(messages);
    }

    @Override
    public void ack(String messageId) {
        redis.execute((RedisCallback<Long>) connection ->
                connection.streamCommands().xAck(KEY, GROUP, RecordId.of(messageId)));
    }

    /**
     * 惰性建组。
     *
     * <p>用 {@code ReadOffset.from("0")} 而不是 {@code lastConsumed}：建组时把
     * **已有的**消息也算作未读，否则在"先投递、后启动 Worker"的 compose 启动顺序下，
     * 启动前投递的任务会被永久跳过。
     *
     * <p>{@code BUSYGROUP} 表示组已存在（另一个实例先建了），不是错误。
     */
    private void ensureGroup() {
        try {
            redis.execute((RedisCallback<String>) connection -> connection.streamCommands()
                    .xGroupCreate(KEY, GROUP, ReadOffset.from("0"), true));
        } catch (RuntimeException exception) {
            if (!isBusyGroup(exception)) {
                throw exception;
            }
        }
    }

    private static boolean isBusyGroup(RuntimeException exception) {
        String message = exception.getMessage();
        return message != null && message.contains("BUSYGROUP");
    }
}
