package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiTaskEvent;
import cn.zhishi.stock.ai.domain.AiTaskEventStream;
import cn.zhishi.stock.ai.domain.AiTaskEventType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link AiTaskEventStream} 的 Redis Stream 实现。
 *
 * <h2>两个编号，两种语义（刻意不是同一个数）</h2>
 * <ul>
 *   <li><b>事件 ID</b>（SSE 的 {@code id:}）＝ Redis Stream 的消息 ID，由 Redis 生成，
 *       单调且可在 {@code XRANGE} 里作排他起点。它是**断线续传的游标**。
 *   <li><b>{@code sequence}</b> ＝ 任务内序号（{@code INCR ai:task:seq:{taskId}}），
 *       从 1 开始的小整数。它是**前端排序与去重的依据**，与契约 §13.4 的示例形状一致。
 * </ul>
 *
 * <p>契约示例里两者恰好相等（那份流从 1 开始且没有别的事件），但语义不同：
 * 用一个数同时承担两种职责，就必须保证"游标恰好等于序号"，
 * 而 Redis 的消息 ID 是毫秒时间戳形状，做不到。硬凑的结果是补发时会丢一段或重一段，
 * 而两端都不会报错。
 *
 * <p>序号既写在条目字段里，也由载荷构造器写进 JSON。两份由**同一个入参**写出，
 * 不可能分叉；字段那一份是为了让 {@code latestSequence} 不必解析载荷。
 *
 * <h2>保留期在每次追加时刷新</h2>
 * 契约 §13.4 要求"任务完成后临时片段默认保留 30 分钟"。最后一次追加就发生在任务
 * 临近结束的时候，因此"从最后一次追加起算 30 分钟"与契约要求几乎等价，
 * 且不需要额外记录"完成时刻"。序号键同寿。
 *
 * <p>{@code redis.execute} 有两个同为单参数函数式接口的重载，因此每个 lambda
 * **必须显式标注** {@link RedisCallback}，否则编译期歧义。
 */
public class RedisAiTaskEventStream implements AiTaskEventStream {

    private static final String KEY_PREFIX = "stream:ai:chunk:";
    private static final String SEQUENCE_KEY_PREFIX = "ai:task:seq:";

    /** 按序号过滤，因此要多读一点再裁剪（见 {@link #readAfter}）。 */
    private static final int READ_OVERSHOOT = 4;

    private static final byte[] FIELD_TYPE = "type".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FIELD_SEQUENCE = "sequence".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FIELD_DATA = "data".getBytes(StandardCharsets.UTF_8);

    private final StringRedisTemplate redis;
    private final Duration retention;

    public RedisAiTaskEventStream(StringRedisTemplate redis, Duration retention) {
        this.redis = redis;
        this.retention = retention;
    }

    @Override
    public AiTaskEvent append(long taskId, AiTaskEventType type, LongFunction<String> payloadBuilder) {
        long sequence = nextSequence(taskId);
        String dataJson = payloadBuilder.apply(sequence);
        byte[] key = keyOf(taskId);
        long seconds = Math.max(1, retention.toSeconds());

        String eventId = redis.execute((RedisCallback<String>) connection -> {
            RecordId id = connection.streamCommands().xAdd(key, Map.of(
                    FIELD_TYPE, type.eventName().getBytes(StandardCharsets.UTF_8),
                    FIELD_SEQUENCE, Long.toString(sequence).getBytes(StandardCharsets.UTF_8),
                    FIELD_DATA, dataJson.getBytes(StandardCharsets.UTF_8)));
            connection.keyCommands().expire(key, seconds);
            connection.keyCommands().expire(sequenceKeyOf(taskId), seconds);
            return id.getValue();
        });
        return new AiTaskEvent(eventId, type, dataJson);
    }

    @Override
    public List<AiTaskEvent> readAfter(long taskId, long lastSequence, int count) {
        // 入参是任务内序号，而 XRANGE 的起点需要消息 ID。两者不同形，
        // 因此这里按序号过滤而不是按 ID 起点：读回一段再按 sequence 裁剪，
        // 代价是可能多读几条，收益是不必维护"序号 → 消息 ID"的映射（那会是第二个索引，
        // 而两个索引必然分叉——分叉的表现是重连后丢了一段或重了一段）。
        List<ByteRecord> records = redis.execute((RedisCallback<List<ByteRecord>>) connection ->
                connection.streamCommands().xRange(
                        keyOf(taskId),
                        Range.of(Range.Bound.unbounded(), Range.Bound.unbounded()),
                        Limit.limit().count(Math.max(count, count * READ_OVERSHOOT))));
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<AiTaskEvent> events = new ArrayList<>();
        for (ByteRecord record : records) {
            Map<byte[], byte[]> value = record.getValue();
            if (value == null || parseLong(value.get(FIELD_SEQUENCE)) <= lastSequence) {
                continue;
            }
            AiTaskEventType type = AiTaskEventType.fromName(text(value.get(FIELD_TYPE)));
            if (type == null) {
                continue;
            }
            events.add(new AiTaskEvent(record.getId().getValue(), type, text(value.get(FIELD_DATA))));
            if (events.size() >= count) {
                break;
            }
        }
        return List.copyOf(events);
    }

    @Override
    public Optional<Long> latestSequence(long taskId) {
        List<ByteRecord> records = redis.execute((RedisCallback<List<ByteRecord>>) connection ->
                connection.streamCommands().xRevRange(
                        keyOf(taskId),
                        Range.of(Range.Bound.unbounded(), Range.Bound.unbounded()),
                        Limit.limit().count(1)));
        if (records == null || records.isEmpty()) {
            return Optional.empty();
        }
        Map<byte[], byte[]> value = records.get(0).getValue();
        if (value == null || value.get(FIELD_SEQUENCE) == null) {
            return Optional.empty();
        }
        return Optional.of(parseLong(value.get(FIELD_SEQUENCE)));
    }

    private long nextSequence(long taskId) {
        Long value = redis.execute((RedisCallback<Long>) connection ->
                connection.stringCommands().incr(sequenceKeyOf(taskId)));
        if (value == null) {
            throw new IllegalStateException("无法分配事件序号：taskId=" + taskId);
        }
        return value;
    }

    private static byte[] keyOf(long taskId) {
        return (KEY_PREFIX + taskId).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sequenceKeyOf(long taskId) {
        return (SEQUENCE_KEY_PREFIX + taskId).getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] raw) {
        return raw == null ? null : new String(raw, StandardCharsets.UTF_8);
    }

    private static long parseLong(byte[] raw) {
        return raw == null ? 0L : Long.parseLong(new String(raw, StandardCharsets.UTF_8));
    }
}
