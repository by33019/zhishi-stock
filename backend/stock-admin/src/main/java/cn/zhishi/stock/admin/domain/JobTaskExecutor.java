package cn.zhishi.stock.admin.domain;

import cn.zhishi.stock.system.job.JobExecutionOutcome;

/**
 * 任务执行体：把一个 {@link JobTrigger} 变成一次执行结论。
 *
 * <h2>为什么是端口，而不是直接调用采集器</h2>
 * 三个采集器住在 {@code stock-job} 模块里，而人工触发发生在 {@code stock-backend}。
 * 让后者依赖前者，会让 {@code @Component} 扫描把 {@code stock-job} 的整条装配链
 * （它自己的一份 {@code NewsArticleStore} / {@code MarketOverviewStore} 实现）
 * 拖进 API 进程，于是同一个应用里出现两套行情与资讯的写入口。
 *
 * <p>因此后台只声明"我要执行某个白名单任务"，由 {@code stock-backend} 用
 * {@code InProcessJobRunner} 实现它。执行体是**唯一**知道"某个任务名对应哪段逻辑"的地方，
 * 白名单（{@link JobDefinitionCatalog}）只负责说明"哪些任务允许被触发"。
 *
 * <h2>抛异常是合法的失败表达</h2>
 * 返回 {@link JobExecutionOutcome} 里已经带状态，但执行体抛异常同样可以被正确处理
 * （由 {@code JobExecutionRecorder} 记成 FAILED 并重抛）。两者分工是：
 * 执行体知道"失败了但计数还有意义"时返回结论；不知道时直接抛。
 */
public interface JobTaskExecutor {

    /**
     * 执行一个白名单任务。
     *
     * <p>实现方可以假定 {@code trigger.jobName()} 一定在白名单内——白名单校验在用例层
     * 已经做过。未知名字属于编程错误，抛 {@code IllegalArgumentException} 即可。
     */
    JobExecutionOutcome execute(JobTrigger trigger);
}
