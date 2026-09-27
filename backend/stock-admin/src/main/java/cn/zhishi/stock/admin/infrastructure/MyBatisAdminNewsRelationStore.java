package cn.zhishi.stock.admin.infrastructure;

import cn.zhishi.stock.admin.domain.AdminNewsRelationEntry;
import cn.zhishi.stock.admin.domain.AdminNewsRelationQuery;
import cn.zhishi.stock.admin.domain.AdminNewsRelationStore;
import cn.zhishi.stock.news.domain.NewsTargetType;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * {@link AdminNewsRelationStore} 的 MyBatis 实现。
 *
 * <p>与 {@code MyBatisAdminNewsSourceStore} 同一分工：分配主键、时区换算、
 * 把存储形状映射成领域形状。复核的"只审一次"由用例层的
 * {@code reviewed_at} 检查保证，这里不做条件更新（表没有乐观锁列）。
 */
public class MyBatisAdminNewsRelationStore implements AdminNewsRelationStore {

    private final AdminNewsRelationMapper mapper;
    private final LongSupplier idGenerator;
    private final Clock clock;

    public MyBatisAdminNewsRelationStore(
            AdminNewsRelationMapper mapper, LongSupplier idGenerator, Clock clock) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    public List<AdminNewsRelationEntry> page(AdminNewsRelationQuery query) {
        return mapper.pageRows(
                        query.relationStatus(),
                        query.targetType(),
                        query.newsId(),
                        query.minConfidence(),
                        toLocalDateTime(query.startedAt()),
                        toLocalDateTime(query.endedAt()),
                        query.size(),
                        query.offset())
                .stream()
                .map(this::toEntry)
                .toList();
    }

    @Override
    public long count(AdminNewsRelationQuery query) {
        return mapper.countRows(
                query.relationStatus(),
                query.targetType(),
                query.newsId(),
                query.minConfidence(),
                toLocalDateTime(query.startedAt()),
                toLocalDateTime(query.endedAt()));
    }

    @Override
    public Optional<AdminNewsRelationEntry> find(long relationId) {
        return Optional.ofNullable(mapper.find(relationId)).map(this::toEntry);
    }

    @Override
    public boolean newsExists(long newsId) {
        return mapper.countNews(newsId) > 0;
    }

    @Override
    public AdminNewsRelationEntry insertManual(
            long newsId,
            AdminNewsRelationTarget target,
            String reasonSummary,
            long reviewedBy,
            OffsetDateTime reviewedAt) {
        long id = idGenerator.getAsLong();
        mapper.insertManual(
                id,
                newsId,
                target.targetType(),
                target.targetId(),
                reasonSummary,
                reviewedBy,
                toLocalDateTime(reviewedAt));
        // DuplicateKeyException 不在这里拦：与 MyBatisAdminUserStore 同一分工，
        // 唯一索引冲突的幂等语义属于用例层（回读已有记录）。
        return Optional.ofNullable(mapper.find(id)).map(this::toEntry).orElseThrow();
    }

    @Override
    public Optional<AdminNewsRelationEntry> findByTarget(
            long newsId, NewsTargetType targetType, long targetId) {
        return Optional.ofNullable(mapper.findByTarget(newsId, targetType, targetId))
                .map(this::toEntry);
    }

    @Override
    public boolean review(
            long relationId,
            cn.zhishi.stock.news.domain.NewsRelationStatus relationStatus,
            String reasonSummary,
            long reviewedBy,
            OffsetDateTime reviewedAt) {
        return mapper.review(
                        relationId, relationStatus, reasonSummary, reviewedBy,
                        toLocalDateTime(reviewedAt)) == 1;
    }

    private AdminNewsRelationEntry toEntry(AdminNewsRelationRow row) {
        return new AdminNewsRelationEntry(
                row.relationId(),
                row.newsId(),
                row.newsTitle(),
                row.targetType(),
                row.targetId(),
                row.relationMethod(),
                row.confidenceScore(),
                row.relationStatus(),
                row.reasonSummary(),
                row.reviewedBy(),
                toOffsetDateTime(row.reviewedAt()),
                toOffsetDateTime(row.createdAt()));
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }
}
