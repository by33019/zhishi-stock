package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiMessage;
import cn.zhishi.stock.ai.domain.AiMessageStore;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

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

    private LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }
}
