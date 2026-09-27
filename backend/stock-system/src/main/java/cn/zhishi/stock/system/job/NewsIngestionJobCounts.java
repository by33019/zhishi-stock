package cn.zhishi.stock.system.job;

import cn.zhishi.stock.news.domain.NewsIngestionResult;

/**
 * 资讯采集结果到任务计数的映射。
 *
 * <h2>为什么放在 stock-system</h2>
 * 定时采集（{@code stock-job}）与人工触发（{@code stock-backend}）都要做这个映射，
 * 而两者唯一的共同依赖是 {@code stock-system}。放在任一侧都会让另一侧复制一份，
 * 而复制出来的两份一旦分叉，症状是"同一段逻辑在定时触发与人工触发下报出不同的数字"——
 * 这种不一致极难被发现，因为两个入口很少被同一天对照。
 *
 * <p>{@code stock-system} 已经为了自选分组的 {@code latestNewsCount} 依赖 {@code stock-news}，
 * 因此这里不引入新的依赖方向。
 *
 * <h2>三个数为什么这样分</h2>
 * {@link NewsIngestionResult} 把"采集到多少"（fetched）与"入库多少"（inserted）分开，
 * 而 {@code inserted} 里还包含被判为重复稿的那部分（deduplicated）——它们入库是为了
 * 折叠到主记录，不算新内容。因此：
 *
 * <ul>
 *   <li>{@code input} = fetched：本轮拿到多少条；</li>
 *   <li>{@code success} = inserted − deduplicated：真正新增的有效稿件；</li>
 *   <li>{@code ignored} = skipped + deduplicated：被跳过的（幂等命中、来源不可用）
 *       加上重复稿——都是"不算失败，但也没有产生新内容"；</li>
 *   <li>{@code output} = inserted：实际落库的稿件总数（含重复稿）。</li>
 * </ul>
 *
 * <p>这样 {@code success + ignored = inserted − dedup + skipped + dedup = fetched}，
 * 恰好等于 {@code input}，满足 {@code ck_job_execution_counts} 且三个数互不重叠。
 * 把 dedup 同时算进 success 与 ignored 是这里最容易犯的错——它不会立刻报错，
 * 只会在某一轮数据多一点时突然撞上 CHECK 约束，表现为"任务莫名失败"。
 */
public final class NewsIngestionJobCounts {

    private NewsIngestionJobCounts() {
    }

    public static JobExecutionCounts of(NewsIngestionResult result) {
        return new JobExecutionCounts(
                result.fetchedCount(),
                result.insertedCount() - result.deduplicatedCount(),
                (long) result.skippedCount() + result.deduplicatedCount(),
                0,
                result.insertedCount());
    }
}
