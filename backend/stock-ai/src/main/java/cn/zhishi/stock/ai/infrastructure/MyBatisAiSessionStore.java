package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiSession;
import cn.zhishi.stock.ai.domain.AiSessionStore;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }
}
