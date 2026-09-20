package cn.zhishi.stock.news.infrastructure;

import cn.zhishi.stock.news.domain.NewsSource;
import cn.zhishi.stock.news.domain.NewsSourceStore;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;

/**
 * {@link NewsSourceStore} 的 MyBatis 实现：**哑存储**，不做任何业务判断。
 *
 * <p>时区处理与 {@code MyBatisWatchlistItemRepository} 一致：库里是 {@code datetime(3)}（无时区），
 * 写入与回读都用**同一个 {@code Clock}** 的时区换算，墙上时间因此无损往返。
 * 交给 JDBC 驱动按连接时区推断的话，本机（Asia/Shanghai）与 CI（UTC）会得到不同时刻。
 */
public class MyBatisNewsSourceStore implements NewsSourceStore {

    private final NewsSourceMapper mapper;
    private final Clock clock;

    public MyBatisNewsSourceStore(NewsSourceMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public List<NewsSource> findAll() {
        return mapper.findAll().stream().map(this::toSource).toList();
    }

    @Override
    public Optional<NewsSource> findByCode(String sourceCode) {
        return Optional.ofNullable(mapper.findByCode(sourceCode)).map(this::toSource);
    }

    @Override
    public Optional<NewsSource> findById(long sourceId) {
        return Optional.ofNullable(mapper.findById(sourceId)).map(this::toSource);
    }

    @Override
    public void ensureAll(Collection<NewsSource> sources) {
        for (NewsSource source : sources) {
            if (mapper.findByCode(source.sourceCode()) != null) {
                // 已登记就整条跳过：授权与运行状态是人工决定，Provider 无权改写。
                continue;
            }
            try {
                mapper.insert(
                        source.sourceId(),
                        source.sourceCode(),
                        source.sourceName(),
                        source.sourceType(),
                        source.homepageUrl(),
                        source.authorizationStatus(),
                        source.rightsValidFrom(),
                        source.rightsValidTo(),
                        source.allowAiAnalysis(),
                        source.status());
            } catch (DuplicateKeyException exception) {
                // 并发下另一个进程刚登记了同一条来源：唯一索引已经保证了正确性，忽略即可。
            }
        }
    }

    @Override
    public void recordSyncSuccess(Collection<Long> sourceIds, OffsetDateTime at) {
        LocalDateTime local = toLocalDateTime(at);
        for (Long sourceId : sourceIds) {
            mapper.markSuccess(sourceId, local);
        }
    }

    @Override
    public void recordSyncFailure(Collection<Long> sourceIds, OffsetDateTime at) {
        LocalDateTime local = toLocalDateTime(at);
        for (Long sourceId : sourceIds) {
            mapper.markFailure(sourceId, local);
        }
    }

    private NewsSource toSource(NewsSourceRow row) {
        return new NewsSource(
                row.sourceId(),
                row.sourceCode(),
                row.sourceName(),
                row.sourceType(),
                row.homepageUrl(),
                row.authorizationStatus(),
                row.rightsValidFrom(),
                row.rightsValidTo(),
                row.allowAiAnalysis(),
                row.status(),
                toOffsetDateTime(row.lastSuccessAt()),
                toOffsetDateTime(row.lastFailureAt()),
                row.version());
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }
}
