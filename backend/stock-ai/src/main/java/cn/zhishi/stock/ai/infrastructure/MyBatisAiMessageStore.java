package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiMessage;
import cn.zhishi.stock.ai.domain.AiMessageStore;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/** {@link AiMessageStore} 的 MyBatis 实现。 */
public class MyBatisAiMessageStore implements AiMessageStore {

    private final AiMessageMapper mapper;
    private final Clock clock;

    public MyBatisAiMessageStore(AiMessageMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public int nextSequenceNo(long sessionId) {
        return mapper.nextSequenceNo(sessionId);
    }

    @Override
    public void insert(AiMessage message) {
        mapper.insert(new AiMessageRow(
                message.messageId(),
                message.sessionId(),
                message.taskId(),
                message.roleType(),
                message.sequenceNo(),
                message.content(),
                toLocalDateTime(message.dataCutoffAt()),
                toLocalDateTime(message.createdAt())));
    }

    @Override
    public int countBySession(long sessionId) {
        return mapper.countBySession(sessionId);
    }

    @Override
    public List<AiMessage> listVisible(long sessionId, int offset, int limit) {
        return mapper.listVisible(sessionId, offset, limit).stream().map(this::toMessage).toList();
    }

    @Override
    public int countVisible(long sessionId) {
        return mapper.countVisible(sessionId);
    }

    /**
     * 投影成领域类型。
     *
     * <p>刻意是实例方法：它要用 {@code clock} 的时区换算 {@code datetime} 列。
     * 改成静态就得另找时区来源，等于给"时区"造第二处定义。
     */
    private AiMessage toMessage(AiMessageRow row) {
        return new AiMessage(
                row.messageId(),
                row.sessionId(),
                row.taskId(),
                row.roleType(),
                row.sequenceNo(),
                row.content(),
                toOffsetDateTime(row.dataCutoffAt()),
                toOffsetDateTime(row.createdAt()));
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }
}
