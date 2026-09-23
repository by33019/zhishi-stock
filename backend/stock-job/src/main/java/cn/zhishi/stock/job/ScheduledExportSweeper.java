package cn.zhishi.stock.job;

import cn.zhishi.stock.export.application.ExportRetentionSweeper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 导出文件与作业记录的到期清理（契约 §9.2：导出文件默认 24 小时过期）。
 *
 * <h2>为什么清理在 stock-job，而不是在 API 进程里</h2>
 * 架构把定时维护统一放在本模块：API 是请求驱动的，给它挂一个后台循环会让
 * "这个进程到底在做什么"变得含糊，也会在多实例部署时变成每个实例各扫一遍。
 *
 * <h2>因此导出目录必须是两个进程共用的卷</h2>
 * 文件由 API 写出、由本任务删除。两者看到不同目录时，清理会把 Redis 里的作业记录删掉，
 * 而文件永远留在 API 的卷上——**不会有任何报错**，只会在磁盘上慢慢堆积。
 * 部署侧由 {@code compose.yaml} 的 {@code export-files} 卷保证（两处都挂同一个卷）。
 *
 * <h2>为什么是 fixedDelay 而不是 cron</h2>
 * 清理没有"必须在某个时刻完成"的语义，它只需要**持续推进**。fixedDelay 让两轮之间
 * 至少隔一段时间（上一轮跑完才开始计时），因此不会在积压很多时把两轮叠在一起跑。
 */
@Component
public class ScheduledExportSweeper {

    private final ExportRetentionSweeper sweeper;
    private final int batchSize;

    public ScheduledExportSweeper(
            ExportRetentionSweeper sweeper,
            @Value("${stock.export.sweep-batch-size:200}") int batchSize) {
        this.sweeper = sweeper;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${stock.export.sweep-initial-delay-ms:60000}",
            fixedDelayString = "${stock.export.sweep-delay-ms:600000}")
    public void sweep() {
        // 异常不在这里吞：Spring 的 fixed-delay 任务会把日志记成 ERROR 然后照常跑下一轮，
        // 因此让 Redis/卷的真实错误浮上来比包装一层更有用。
        sweeper.sweep(batchSize);
    }
}
