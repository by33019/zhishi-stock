package cn.zhishi.stock.aiworker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zhishi.stock.ai.application.AiTaskRecoveryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 恢复扫描调度测试。
 *
 * <p>它守着两件事：**扫描真的被调用了**，以及**整批失败要冒出去**。
 *
 * <p>后者不是形式主义：{@code AiTaskRecoveryService} 对**单个**任务
 * 是 catch 后继续的，所以"数据库连不上"这种整批失败在日志里只会留下一行
 * {@code WARN}。让异常冒到调度器，Spring 会记一条 ERROR 然后照常跑下一轮
 * （{@code LOG_AND_SUPPRESS_ERROR_HANDLER}），运维才看得到。
 */
class ScheduledAiTaskRecoveryTest {

    private final AiTaskRecoveryService recovery = mock(AiTaskRecoveryService.class);

    @Test
    @DisplayName("每一轮都调用一次恢复扫描")
    void scansOncePerRound() {
        new ScheduledAiTaskRecovery(recovery).scan();

        verify(recovery).recover();
    }

    @Test
    @DisplayName("整批失败要冒出去：调度器会记 ERROR 并继续跑下一轮，不会静默")
    void propagatesBatchFailures() {
        when(recovery.recover()).thenThrow(new IllegalStateException("数据库不可用"));

        assertThatThrownBy(() -> new ScheduledAiTaskRecovery(recovery).scan())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数据库不可用");
    }
}
