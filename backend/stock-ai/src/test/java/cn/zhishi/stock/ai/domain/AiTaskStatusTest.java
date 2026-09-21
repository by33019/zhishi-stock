package cn.zhishi.stock.ai.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link AiTaskStatus} 的语义。
 *
 * <p>{@code activeStatuses()} 与 {@code terminalStatuses()} 必须**恰好划分**全部取值：
 * 并发配额算的就是"活跃"这个集合，漏一个会让用户能多开一个任务，
 * 多一个会让任务永远释放不掉额度。两者都不会报错。
 */
class AiTaskStatusTest {

    @Test
    void terminalStatusesAreExactlyTheFourEndStates() {
        assertThat(AiTaskStatus.terminalStatuses())
                .containsExactly(
                        AiTaskStatus.COMPLETED,
                        AiTaskStatus.CANCELED,
                        AiTaskStatus.FAILED,
                        AiTaskStatus.TIMED_OUT);
    }

    @Test
    void activeStatusesAreExactlyTheFiveInFlightStates() {
        assertThat(AiTaskStatus.activeStatuses())
                .containsExactly(
                        AiTaskStatus.CREATED,
                        AiTaskStatus.PREPARING,
                        AiTaskStatus.QUEUED,
                        AiTaskStatus.RUNNING,
                        AiTaskStatus.VALIDATING);
    }

    @Test
    void activeAndTerminalPartitionEveryStatusExactly() {
        List<AiTaskStatus> union = new java.util.ArrayList<>(AiTaskStatus.activeStatuses());
        union.addAll(AiTaskStatus.terminalStatuses());

        assertThat(union)
                .as("两个清单合起来必须恰好是全部取值，且无重复")
                .containsExactlyInAnyOrder(AiTaskStatus.values())
                .doesNotHaveDuplicates();
    }

    @Test
    void terminalPredicateAgreesWithTerminalStatuses() {
        for (AiTaskStatus status : AiTaskStatus.values()) {
            assertThat(status.terminal())
                    .as("%s 的 terminal() 必须与 terminalStatuses() 一致", status)
                    .isEqualTo(AiTaskStatus.terminalStatuses().contains(status));
        }
    }

    @Test
    void codesFollowDeclarationOrder() {
        assertThat(AiTaskStatus.codes())
                .containsExactly(
                        "CREATED",
                        "PREPARING",
                        "QUEUED",
                        "RUNNING",
                        "VALIDATING",
                        "COMPLETED",
                        "CANCELED",
                        "FAILED",
                        "TIMED_OUT");
    }

    @Test
    void fromCodeIsCaseInsensitiveAndTrims() {
        assertThat(AiTaskStatus.fromCode("  running ")).contains(AiTaskStatus.RUNNING);
        assertThat(AiTaskStatus.fromCode("Timed_Out")).contains(AiTaskStatus.TIMED_OUT);
    }

    @Test
    void fromCodeReturnsEmptyForUnknownOrNull() {
        assertThat(AiTaskStatus.fromCode("PENDING")).isEmpty();
        assertThat(AiTaskStatus.fromCode("")).isEmpty();
        assertThat(AiTaskStatus.fromCode(null)).isEmpty();
    }
}
