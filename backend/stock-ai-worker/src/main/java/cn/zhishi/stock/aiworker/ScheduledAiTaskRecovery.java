package cn.zhishi.stock.aiworker;

import cn.zhishi.stock.ai.application.AiTaskRecoveryService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时恢复扫描（架构 §"任务超时与恢复"）。
 *
 * <h2>它修的是哪一类缺陷</h2>
 * 队列是「至少一次」投递，执行者可能在任何一步死掉：拿到消息后崩、调模型时崩、
 * 写报告前崩。这类故障**不会产生任何错误记录**——任务既不会失败也不会完成，
 * 前端就永远显示「正在生成分析」。它是本域唯一会表现为「什么都没发生」的缺陷。
 *
 * <h2>为什么失败要抛出去</h2>
 * Spring 的 fixed-delay 任务由 {@code LOG_AND_SUPPRESS_ERROR_HANDLER} 包裹，
 * 抛异常只会记一条 ERROR 然后照常跑下一轮，不会中断调度。
 * 只写日志不抛会让"数据库连不上"这种整批失败在日志里只留下一行
 * {@code WARN}（{@code AiTaskRecoveryService} 对单个任务是 catch 后继续的），
 * 而运维得先知道去查才能看到。
 *
 * <h2>为什么阈值不能在代码里写死</h2>
 * "多久算消息丢了""多久算执行者死了"取决于部署形态（单机 vs 多副本、
 * 供应商快慢）。写死会让调参必须重新构建镜像，而这类参数正是最需要按环境调的。
 */
@Component
public class ScheduledAiTaskRecovery {

    private final AiTaskRecoveryService recovery;

    public ScheduledAiTaskRecovery(AiTaskRecoveryService recovery) {
        this.recovery = recovery;
    }

    @Scheduled(
            initialDelayString = "${stock.ai.worker.recovery-initial-delay-ms:5000}",
            fixedDelayString = "${stock.ai.worker.recovery-delay-ms:30000}")
    public void scan() {
        recovery.recover();
    }
}
