package cn.zhishi.stock.export.infrastructure;

import cn.zhishi.stock.export.domain.ExportJob;
import cn.zhishi.stock.export.domain.ExportJobStore;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link ExportJobStore} 的 Redis 实现（契约 §9.2："导出任务状态可保存在 Redis"）。
 *
 * <h2>两个键各司其职</h2>
 * <ul>
 *   <li>{@code export:job:{id}} → 作业记录的 JSON，**自带 TTL**（{@code recordTtl}）。
 *       TTL 是"记录一定会消失"的兜底：清理任务没跑（进程挂了、被禁用）时，
 *       记录仍然会自己退场，不会留下永远查得到、又永远没有文件的僵尸作业。</li>
 *   <li>{@code export:jobs:purge} → 有序集合，成员是 {@code exportId}，
 *       分值是"记录可清理时刻"的毫秒时间戳。它让 {@link #findExpired} 能
 *       只读该读的那几条，而不是 SCAN 全库。</li>
 * </ul>
 *
 * <h2>为什么分值用 {@code createdAt + recordTtl} 而不是 {@code expiresAt}</h2>
 * {@code expiresAt} 是**文件**的到期时刻（默认 24 小时）。若用它当清理分值，
 * 记录就会在"文件刚过期"的那一刻被删掉——而那正是 {@code EXPORT_EXPIRED}
 * 要向用户解释的时刻。记录多活一段时间，这条解释才有据可依。
 *
 * <h2>为什么有序集合的分值可以放毫秒时间戳</h2>
 * Redis 的 ZSET 分值是 64 位双精度浮点。毫秒级时间戳约 {@code 1.8e12}，
 * 远小于 {@code 2^53}，因此**整数精确**，不存在相邻作业因精度丢失而排序错乱的问题。
 *
 * <h2>读失败与记录损坏</h2>
 * 与 {@code RedisMarketOverviewStore} 同口径：Redis 抖动或 JSON 解不开时
 * 记 WARN 并返回"不存在"，不把异常抛给调用方。记录一旦解不开就已不可恢复，
 * 让它把整个查询接口变成 500 只会把一次读取失败放大成一次功能不可用。
 * 残留在有序集合里的 id 会由清理任务在它到期时一并带走。
 */
public class RedisExportJobStore implements ExportJobStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisExportJobStore.class);

    /** 作业记录的键前缀。属于实现细节，不进契约。 */
    static final String JOB_KEY_PREFIX = "export:job:";

    /** 待清理索引的键。同上。 */
    static final String PURGE_INDEX_KEY = "export:jobs:purge";

    private final StringRedisTemplate redis;
    private final ExportJobJsonCodec codec;
    private final Duration recordTtl;
    private final Clock clock;

    public RedisExportJobStore(
            StringRedisTemplate redis,
            ExportJobJsonCodec codec,
            Duration recordTtl,
            Clock clock) {
        this.redis = redis;
        this.codec = codec;
        this.recordTtl = recordTtl;
        this.clock = clock;
    }

    @Override
    public void save(ExportJob job) {
        redis.opsForValue().set(jobKey(job.exportId()), codec.encode(job), recordTtl);
        redis.opsForZSet().add(PURGE_INDEX_KEY, job.exportId(), purgeScoreOf(job));
    }

    @Override
    public Optional<ExportJob> find(String exportId) {
        try {
            String json = redis.opsForValue().get(jobKey(exportId));
            return json == null ? Optional.empty() : Optional.of(codec.decode(json));
        } catch (DataAccessException | IllegalStateException exception) {
            LOGGER.warn("读取导出作业失败，按不存在处理：exportId={}", exportId, exception);
            return Optional.empty();
        }
    }

    @Override
    public void delete(String exportId) {
        // 先删索引再删记录：反过来的话，进程在两步之间退出会留下一条
        // "记录已不在、索引还在"的孤儿，而它每次清理都会再被取出来一次、永远删不掉。
        redis.opsForZSet().remove(PURGE_INDEX_KEY, exportId);
        redis.delete(jobKey(exportId));
    }

    @Override
    public List<String> findExpired(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        double nowMillis = OffsetDateTime.now(clock).toInstant().toEpochMilli();
        Set<String> ids = redis.opsForZSet()
                .rangeByScore(PURGE_INDEX_KEY, Double.NEGATIVE_INFINITY, nowMillis, 0, limit);
        return ids == null ? List.of() : List.copyOf(ids);
    }

    private String jobKey(String exportId) {
        return JOB_KEY_PREFIX + exportId;
    }

    /** 记录可清理时刻的毫秒时间戳。**只有这一处**定义它怎么算。 */
    private double purgeScoreOf(ExportJob job) {
        return job.createdAt().plus(recordTtl).toInstant().toEpochMilli();
    }
}
