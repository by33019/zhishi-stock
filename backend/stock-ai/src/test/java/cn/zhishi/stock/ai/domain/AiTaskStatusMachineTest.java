package cn.zhishi.stock.ai.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link AiTaskStatusMachine} 的迁移白名单。
 *
 * <p>白名单在这里**独立写一遍**：测试的价值正在于它是另一份独立的表述，
 * 而不是去读实现里的那张表。实现漏一条、多一条，这里都会红。
 */
class AiTaskStatusMachineTest {

    /** 契约允许的迁移。顺序与状态声明顺序一致，便于阅读。 */
    private static final Map<AiTaskStatus, Set<AiTaskStatus>> ALLOWED = allowed();

    private static Map<AiTaskStatus, Set<AiTaskStatus>> allowed() {
        Map<AiTaskStatus, Set<AiTaskStatus>> map = new EnumMap<>(AiTaskStatus.class);
        map.put(AiTaskStatus.CREATED, EnumSet.of(
                AiTaskStatus.PREPARING, AiTaskStatus.QUEUED, AiTaskStatus.CANCELED, AiTaskStatus.FAILED));
        map.put(AiTaskStatus.PREPARING, EnumSet.of(
                AiTaskStatus.QUEUED, AiTaskStatus.RUNNING, AiTaskStatus.CANCELED,
                AiTaskStatus.FAILED, AiTaskStatus.TIMED_OUT));
        map.put(AiTaskStatus.QUEUED, EnumSet.of(
                AiTaskStatus.PREPARING, AiTaskStatus.RUNNING, AiTaskStatus.CANCELED,
                AiTaskStatus.FAILED, AiTaskStatus.TIMED_OUT));
        map.put(AiTaskStatus.RUNNING, EnumSet.of(
                AiTaskStatus.QUEUED, AiTaskStatus.VALIDATING, AiTaskStatus.CANCELED,
                AiTaskStatus.FAILED, AiTaskStatus.TIMED_OUT));
        map.put(AiTaskStatus.VALIDATING, EnumSet.of(
                AiTaskStatus.QUEUED, AiTaskStatus.COMPLETED, AiTaskStatus.CANCELED,
                AiTaskStatus.FAILED, AiTaskStatus.TIMED_OUT));
        map.put(AiTaskStatus.COMPLETED, EnumSet.noneOf(AiTaskStatus.class));
        map.put(AiTaskStatus.CANCELED, EnumSet.noneOf(AiTaskStatus.class));
        map.put(AiTaskStatus.FAILED, EnumSet.noneOf(AiTaskStatus.class));
        map.put(AiTaskStatus.TIMED_OUT, EnumSet.noneOf(AiTaskStatus.class));
        return map;
    }

    @Test
    void everyStatusHasAnEntryInTheWhitelist() {
        assertThat(ALLOWED.keySet())
                .as("白名单必须覆盖全部状态，否则下面的穷举会漏测")
                .containsExactlyInAnyOrder(AiTaskStatus.values());
    }

    @Test
    void exhaustiveTransitionMatrixMatchesWhitelist() {
        for (AiTaskStatus from : AiTaskStatus.values()) {
            for (AiTaskStatus to : AiTaskStatus.values()) {
                boolean expected = ALLOWED.get(from).contains(to);
                assertThat(AiTaskStatusMachine.canTransition(from, to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void terminalStatusesCannotTransitionToThemselves() {
        for (AiTaskStatus terminal : AiTaskStatus.terminalStatuses()) {
            assertThat(AiTaskStatusMachine.canTransition(terminal, terminal))
                    .as("终态 %s 不能迁移到自身", terminal)
                    .isFalse();
        }
    }

    @Test
    void runningCannotJumpStraightToCompleted() {
        assertThat(AiTaskStatusMachine.canTransition(AiTaskStatus.RUNNING, AiTaskStatus.COMPLETED))
                .as("跳过 VALIDATING 会让未校验的文本被标成成功报告")
                .isFalse();
    }

    @Test
    void everyNonTerminalStatusCanBeCanceledOrFailed() {
        for (AiTaskStatus status : AiTaskStatus.activeStatuses()) {
            assertThat(AiTaskStatusMachine.canTransition(status, AiTaskStatus.CANCELED))
                    .as("%s 必须能被取消", status)
                    .isTrue();
            assertThat(AiTaskStatusMachine.canTransition(status, AiTaskStatus.FAILED))
                    .as("%s 必须能失败", status)
                    .isTrue();
        }
    }

    @Test
    void cancellableIsTrueExactlyForNonTerminalStatuses() {
        for (AiTaskStatus status : AiTaskStatus.values()) {
            assertThat(AiTaskStatusMachine.cancellable(status))
                    .as("%s 的 cancellable()", status)
                    .isEqualTo(!status.terminal());
        }
    }

    @Test
    void onlyFailedAndTimedOutAreRetryable() {
        assertThat(AiTaskStatusMachine.retryable(AiTaskStatus.FAILED)).isTrue();
        assertThat(AiTaskStatusMachine.retryable(AiTaskStatus.TIMED_OUT)).isTrue();
        assertThat(AiTaskStatusMachine.retryable(AiTaskStatus.CANCELED))
                .as("用户主动取消的任务不该被重试复活")
                .isFalse();
        assertThat(AiTaskStatusMachine.retryable(AiTaskStatus.COMPLETED)).isFalse();
        for (AiTaskStatus status : AiTaskStatus.activeStatuses()) {
            assertThat(AiTaskStatusMachine.retryable(status)).as("%s 不可重试", status).isFalse();
        }
    }
}
