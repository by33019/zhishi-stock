package cn.zhishi.stock.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zhishi.stock.ai.domain.AiFeedback;
import cn.zhishi.stock.ai.domain.AiFeedbackReasonCode;
import cn.zhishi.stock.ai.domain.AiFeedbackStore;
import cn.zhishi.stock.ai.domain.AiFeedbackType;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * HIS-08 / HIS-09 的业务规则。
 *
 * <h2>最要紧的一条：写完之后必须回读</h2>
 * {@code upsert} 撞 {@code uk_ai_feedback_report_user} 时，数据库保留**原有行的 id**，
 * 本次生成的自增 id 被丢弃。如果用例层把入参当结果返回，调用方拿到的 feedbackId
 * 指向一行并不存在的行——而前端接下来正要用它去删反馈。
 * 因此这里断言"返回值来自 store.find 的重读结果"，而不是"等于我传进去的 id"。
 */
class AiFeedbackServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-22T07:03:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final long REPORT_ID = 8001L;
    private static final long USER_ID = 1001L;
    private static final long GENERATED_ID = 7777L;
    private static final long STORED_ID = 9001L;

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-09-22T15:03:00+08:00");

    private final AiReportQueryService reports = mock(AiReportQueryService.class);
    private final AiFeedbackStore store = mock(AiFeedbackStore.class);

    private final AiFeedbackService service =
            new AiFeedbackService(reports, store, () -> GENERATED_ID, CLOCK);

    @Test
    @DisplayName("返回值来自回读：撞唯一索引时数据库保留原 id，入参的自增 id 不能当结果")
    void returnsTheReReadRowInsteadOfTheGeneratedId() {
        when(store.find(REPORT_ID, USER_ID)).thenReturn(Optional.of(stored()));

        AiFeedbackView view = service.save(REPORT_ID, USER_ID, "not_helpful", "citation_error", " 引用对不上 ");

        assertThat(view.feedbackId()).isEqualTo(Long.toString(STORED_ID));
        assertThat(view.feedbackId()).isNotEqualTo(Long.toString(GENERATED_ID));
        assertThat(view.feedbackType()).isEqualTo("NOT_HELPFUL");
        assertThat(view.reasonCode()).isEqualTo("CITATION_ERROR");
        assertThat(view.detail()).isEqualTo("引用编号对不上");
    }

    @Test
    @DisplayName("大小写不敏感，且 detail / reasonCode 的空白被裁剪、空串按「未提供」处理")
    void normalisesInput() {
        when(store.find(REPORT_ID, USER_ID)).thenReturn(Optional.of(minimal("HELPFUL", null, null)));
        ArgumentCaptor<AiFeedback> captor = ArgumentCaptor.forClass(AiFeedback.class);

        service.save(REPORT_ID, USER_ID, "heLPful", "   ", "  ");

        verify(store).upsert(captor.capture());
        AiFeedback written = captor.getValue();
        assertThat(written.feedbackType()).isEqualTo(AiFeedbackType.HELPFUL);
        assertThat(written.reasonCode()).isNull();
        assertThat(written.detail()).isNull();
    }

    @Test
    @DisplayName("未知 feedbackType / reasonCode → 400，且不写库")
    void rejectsUnknownCodes() {
        assertThatThrownBy(() -> service.save(REPORT_ID, USER_ID, "MAYBE", null, null))
                .isInstanceOf(InvalidAiFeedbackException.class)
                .hasMessageContaining("不支持的反馈态度");
        assertThatThrownBy(() -> service.save(REPORT_ID, USER_ID, "HELPFUL", "WHATEVER", null))
                .isInstanceOf(InvalidAiFeedbackException.class)
                .hasMessageContaining("不支持的原因码");
        verify(store, never()).upsert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("detail 超过 300 字符 → 400，上限与领域常量同源")
    void rejectsOverlongDetail() {
        String tooLong = "字".repeat(AiFeedback.MAX_DETAIL_LENGTH + 1);

        assertThatThrownBy(() -> service.save(REPORT_ID, USER_ID, "NOT_HELPFUL", null, tooLong))
                .isInstanceOf(InvalidAiFeedbackException.class)
                .hasMessageContaining(String.valueOf(AiFeedback.MAX_DETAIL_LENGTH));
        verify(store, never()).upsert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("归属校验失败时不写库：读不到的报告也打不了分")
    void doesNotWriteWhenOwnershipFails() {
        when(reports.requireOwned(REPORT_ID, USER_ID))
                .thenThrow(AiTaskException.reportNotFound(REPORT_ID));

        assertThatThrownBy(() -> service.save(REPORT_ID, USER_ID, "HELPFUL", null, null))
                .isInstanceOf(AiTaskException.class);
        verify(store, never()).upsert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("删除如实返回「是否真的删掉了一行」，不做成 404")
    void deleteReportsWhetherARowWasRemoved() {
        when(store.delete(REPORT_ID, USER_ID)).thenReturn(false);
        assertThat(service.delete(REPORT_ID, USER_ID)).isFalse();

        when(store.delete(REPORT_ID, USER_ID)).thenReturn(true);
        assertThat(service.delete(REPORT_ID, USER_ID)).isTrue();
    }

    @Test
    @DisplayName("归属校验失败时不碰仓储：别人的报告连反馈都删不掉")
    void doesNotTouchStoreWhenDeleteOwnershipFails() {
        when(reports.requireOwned(REPORT_ID, USER_ID))
                .thenThrow(AiTaskException.reportNotFound(REPORT_ID));

        assertThatThrownBy(() -> service.delete(REPORT_ID, USER_ID))
                .isInstanceOf(AiTaskException.class);
        verify(store, never()).delete(anyLong(), anyLong());
    }

    private static AiFeedback stored() {
        return new AiFeedback(
                STORED_ID,
                REPORT_ID,
                USER_ID,
                AiFeedbackType.NOT_HELPFUL,
                AiFeedbackReasonCode.CITATION_ERROR,
                "引用编号对不上",
                NOW,
                NOW);
    }

    private static AiFeedback minimal(
            String type, AiFeedbackReasonCode reason, String detail) {
        return new AiFeedback(
                STORED_ID,
                REPORT_ID,
                USER_ID,
                AiFeedbackType.valueOf(type),
                reason,
                detail,
                NOW,
                NOW);
    }
}
