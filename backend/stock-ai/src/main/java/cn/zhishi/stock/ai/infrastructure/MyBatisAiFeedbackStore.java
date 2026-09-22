package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiFeedback;
import cn.zhishi.stock.ai.domain.AiFeedbackStore;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;

public class MyBatisAiFeedbackStore implements AiFeedbackStore {

    private final AiFeedbackMapper mapper;
    private final Clock clock;

    public MyBatisAiFeedbackStore(AiFeedbackMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void upsert(AiFeedback feedback) {
        mapper.upsert(new AiFeedbackRow(
                feedback.feedbackId(),
                feedback.reportId(),
                feedback.userId(),
                feedback.feedbackType(),
                feedback.reasonCode(),
                feedback.detail(),
                toLocalDateTime(feedback.createdAt()),
                toLocalDateTime(feedback.updatedAt())));
    }

    @Override
    public Optional<AiFeedback> find(long reportId, long userId) {
        AiFeedbackRow row = mapper.find(reportId, userId);
        return row == null ? Optional.empty() : Optional.of(toFeedback(row));
    }

    @Override
    public boolean delete(long reportId, long userId) {
        return mapper.delete(reportId, userId) > 0;
    }

    private AiFeedback toFeedback(AiFeedbackRow row) {
        return new AiFeedback(
                row.feedbackId(),
                row.reportId(),
                row.userId(),
                row.feedbackType(),
                row.reasonCode(),
                row.detail(),
                toOffsetDateTime(row.createdAt()),
                toOffsetDateTime(row.updatedAt()));
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : LocalDateTime.ofInstant(value.toInstant(), clock.getZone());
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }
}
