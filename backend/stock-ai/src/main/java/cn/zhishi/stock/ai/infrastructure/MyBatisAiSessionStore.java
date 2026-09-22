package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiSession;
import cn.zhishi.stock.ai.domain.AiSessionQuery;
import cn.zhishi.stock.ai.domain.AiSessionStore;
import cn.zhishi.stock.ai.domain.AiSessionSummary;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** {@link AiSessionStore} 的 MyBatis 实现。 */
public class MyBatisAiSessionStore implements AiSessionStore {

    private final AiSessionMapper mapper;
    private final Clock clock;

    public MyBatisAiSessionStore(AiSessionMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Optional<AiSession> find(long sessionId) {
        AiSessionRow row = mapper.find(sessionId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new AiSession(
                row.sessionId(),
                row.userId(),
                row.scene(),
                row.title(),
                row.status(),
                row.favorite(),
                row.lastTaskId(),
                toOffsetDateTime(row.lastActivityAt()),
                row.version(),
                toOffsetDateTime(row.createdAt())));
    }

    @Override
    public void insert(AiSession session) {
        mapper.insert(new AiSessionRow(
                session.sessionId(),
                session.userId(),
                session.scene(),
                session.title(),
                session.status(),
                session.favorite(),
                session.lastTaskId(),
                toLocalDateTime(session.lastActivityAt()),
                session.version(),
                toLocalDateTime(session.createdAt())));
    }

    @Override
    public void touch(long sessionId, long lastTaskId, OffsetDateTime at) {
        mapper.touch(sessionId, lastTaskId, toLocalDateTime(at));
    }

    @Override
    public List<AiSessionSummary> listByUser(
            long userId, AiSessionQuery query, int offset, int limit) {
        return mapper
                .listByUser(
                        userId,
                        // 枚举以名字进库（s.scene 是 varchar），传 name 而不是枚举本身，
                        // 免得依赖 MyBatis 的枚举类型处理器去猜。
                        query.scene() == null ? null : query.scene().name(),
                        query.keyword(),
                        query.favorite(),
                        toLocalDateTime(query.startAt()),
                        toLocalDateTime(query.endAt()),
                        offset,
                        limit)
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @Override
    public int countByUser(long userId, AiSessionQuery query) {
        return mapper.countByUser(
                userId,
                query.scene() == null ? null : query.scene().name(),
                query.keyword(),
                query.favorite(),
                toLocalDateTime(query.startAt()),
                toLocalDateTime(query.endAt()));
    }

    /**
     * 投影成领域类型。
     *
     * <p>刻意是**实例**方法：它要用 {@code clock} 的时区把 {@code datetime} 换算成带偏移的时间。
     * 写成静态方法就得另找一个时区来源（比如硬编码 {@code Asia/Shanghai}），
     * 那会给"时区"造出第二处定义——而两处一旦分叉，同一行数据的两个时间列会用不同时区换算。
     */
    private AiSessionSummary toSummary(AiSessionSummaryRow row) {        return new AiSessionSummary(
                row.sessionId(),
                row.userId(),
                row.scene(),
                row.title(),
                row.status(),
                row.favorite(),
                row.lastTaskId(),
                row.lastTaskStatus(),
                toOffsetDateTime(row.lastActivityAt()),
                toOffsetDateTime(row.createdAt()),
                row.version());
    }

    @Override
    public boolean update(long sessionId, int version, String title, boolean favorite) {
        return mapper.update(sessionId, version, title, favorite) > 0;
    }

    @Override
    public boolean softDelete(
            long sessionId, int version, OffsetDateTime deletedAt, OffsetDateTime purgeAfter) {
        return mapper.softDelete(
                sessionId, version, toLocalDateTime(deletedAt), toLocalDateTime(purgeAfter)) > 0;
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }
}
