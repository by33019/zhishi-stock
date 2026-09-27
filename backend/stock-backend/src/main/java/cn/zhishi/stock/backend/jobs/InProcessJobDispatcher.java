package cn.zhishi.stock.backend.jobs;

import cn.zhishi.stock.admin.domain.JobExecutionDispatcher;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用单个后台线程执行人工触发的任务。
 *
 * <h2>为什么是单线程</h2>
 * 三个白名单任务都是"全量动一次外部/全表"的动作：行情采集会覆盖快照、资讯采集会写库、
 * 清理会删文件。两个管理员同时点同一个任务，或同一个人点了两下，用多线程就会变成
 * **同一个任务并发跑**——而这三段逻辑都没有为并发设计（快照互相覆盖、清理重复删）。
 * 单线程把它们串起来，代价是"触发行情采集时点资讯采集要等一会儿"，
 * 而人工触发本来就是低频运维动作。
 *
 * <h2>为什么是守护线程</h2>
 * 线程不能阻止 JVM 退出。否则关停时一次正在跑的采集会让进程挂住，
 * 而优雅停机的时长不该由一个采集任务决定。
 *
 * <h2>为什么不在关停时等任务跑完</h2>
 * {@link #shutdown} 只做"不再接受新任务 + 给短窗口"，超时就中断。
 * 采集任务本身是幂等的（资讯靠来源 ID 与内容指纹去重、行情靠快照覆盖），
 * 被打断后下一轮的固定调度会补上。反过来，如果在这里无限等待，
 * 一次卡住的 Provider 调用就会让整个进程无法重启——**一次坏掉的部署比一次没跑完的采集严重得多**。
 */
public class InProcessJobDispatcher implements JobExecutionDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(InProcessJobDispatcher.class);

    /** 关停时给正在跑的任务留的时间；超时后中断。 */
    private static final long SHUTDOWN_WAIT_SECONDS = 10;

    private final ExecutorService executor;

    public InProcessJobDispatcher() {
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "admin-job-runner");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 提交执行。实现必须立即返回：契约 ADM-JOB-02 返回 202 的前提是这里不阻塞。
     *
     * <p>队列不可用时抛 {@code RejectedExecutionException}，
     * 由 {@code JobAdminService} 把那条已经写下的记录收尾成 FAILED。
     */
    @Override
    public void submit(Runnable task) {
        executor.execute(task);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("人工触发的任务在 {} 秒内未结束，已中断", SHUTDOWN_WAIT_SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
