package cn.zhishi.stock.admin.infrastructure;

import cn.zhishi.stock.admin.domain.AdminNewsSourcePatch;
import cn.zhishi.stock.admin.domain.AdminNewsSourceQuery;
import cn.zhishi.stock.admin.domain.AdminNewsSourceStore;
import cn.zhishi.stock.admin.domain.NewAdminNewsSource;
import cn.zhishi.stock.news.domain.NewsSource;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * {@link AdminNewsSourceStore} 的 MyBatis 实现。
 *
 * <p>行对象 {@code AdminNewsSourceRow} 与领域记录 {@code NewsSource} 的字段
 * 一一同名，映射是机械的；这里真正做的是三件事：分配主键、在 {@code Clock}
 * 的时区里换算 {@code datetime(3)}（无时区列）与 {@code OffsetDateTime}（契约值）、
 * 把"影响 0 行"原样上抛给用例层去区分 404 与版本冲突。
 */
public class MyBatisAdminNewsSourceStore implements AdminNewsSourceStore {

    private final AdminNewsSourceMapper mapper;
    private final LongSupplier idGenerator;
    private final Clock clock;

    public MyBatisAdminNewsSourceStore(
            AdminNewsSourceMapper mapper, LongSupplier idGenerator, Clock clock) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    public List<NewsSource> page(AdminNewsSourceQuery query) {
        return mapper.pageRows(
                        query.providerId(),
                        query.sourceType(),
                        query.authorizationStatus(),
                        query.status(),
                        query.size(),
                        query.offset())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long count(AdminNewsSourceQuery query) {
        return mapper.countRows(
                query.providerId(), query.sourceType(),
                query.authorizationStatus(), query.status());
    }

    @Override
    public Optional<NewsSource> find(long sourceId) {
        return Optional.ofNullable(mapper.find(sourceId)).map(this::toDomain);
    }

    @Override
    public Optional<NewsSource> findByCode(String sourceCode) {
        return Optional.ofNullable(mapper.findByCode(sourceCode)).map(this::toDomain);
    }

    @Override
    public NewsSource insert(
            NewAdminNewsSource command, NewsSource.AuthorizationStatus authorizationStatus) {
        long id = idGenerator.getAsLong();
        mapper.insert(
                id,
                command.providerId(),
                command.sourceCode(),
                command.sourceName(),
                command.sourceType(),
                command.homepageUrl(),
                authorizationStatus,
                command.rightsValidFrom(),
                command.rightsValidTo(),
                command.allowAiAnalysis(),
                command.status());
        // 回读而不是手工拼返回值：created_at / updated_at 由数据库默认值填充，
        // 手拼就会与真实行漂移。
        return Optional.ofNullable(mapper.find(id)).map(this::toDomain).orElseThrow();
    }

    @Override
    public Optional<NewsSource> update(
            long sourceId,
            int expectedVersion,
            AdminNewsSourcePatch patch,
            NewsSource.AuthorizationStatus authorizationStatus) {
        int affected = mapper.updateByCas(
                sourceId,
                expectedVersion,
                patch.sourceName().orElse(null),
                patch.homepageUrl().orElse(null),
                patch.rightsValidFrom().orElse(null),
                patch.rightsValidTo().orElse(null),
                patch.allowAiAnalysis().orElse(null),
                patch.status().orElse(null),
                authorizationStatus);
        return affected == 1
                ? Optional.ofNullable(mapper.find(sourceId)).map(this::toDomain)
                : Optional.empty();
    }

    private NewsSource toDomain(AdminNewsSourceRow row) {
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
}
