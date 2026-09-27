package cn.zhishi.stock.admin.domain;

import cn.zhishi.stock.system.job.JobNames;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 可被后台管理面看到的任务白名单（契约 §16.3 ADM-JOB-01）。
 *
 * <h2>这一份清单是"能被人工触发"的清单，不是"存在的任务"的清单</h2>
 * 见 {@link JobDefinition} 的说明。清单本身很短，因此写成代码而不是配置：
 * 每一条都要有人回答"它的作用范围参数是什么、能不能并发跑、失败了要不要重试"，
 * 而这些答案跟着代码走，不跟着配置文件走。
 *
 * <h2>调度描述从同一批配置键读出来</h2>
 * {@code scheduleDescription} 若硬编码"每 60 秒"，而运维把
 * {@code stock.market.collect-delay-ms} 改成 10 秒，页面上就会长期显示一个错误的事实。
 * 因此这里读的是三个 {@code @Scheduled} 用的**同一组键**（默认值也一致）。
 * 改一处忘另一处的代价是页面说谎，而页面说谎比页面空白更难发现。
 */
public class JobDefinitionCatalog {

    private final List<JobDefinition> definitions;
    private final Map<String, JobDefinition> byName;

    public JobDefinitionCatalog(long marketDelayMs, long newsDelayMs, long exportDelayMs) {
        this(builtIn(marketDelayMs, newsDelayMs, exportDelayMs));
    }

    /**
     * 直接用给定的定义集合构造。
     *
     * <p>存在的理由不只是测试：{@code trigger}/{@code retry} 的两条拒绝分支
     * （任务已停用、任务不支持人工触发）在内置清单里永远走不到——三个任务都是启用且可触发的。
     * 没有这个构造器，"停用的任务不能被触发"就只能靠读代码来相信。
     */
    public JobDefinitionCatalog(List<JobDefinition> definitions) {
        this.definitions = List.copyOf(definitions);
        Map<String, JobDefinition> index = new LinkedHashMap<>();
        for (JobDefinition definition : this.definitions) {
            JobDefinition previous = index.put(definition.jobName(), definition);
            if (previous != null) {
                throw new IllegalArgumentException("任务名重复：" + definition.jobName());
            }
        }
        this.byName = Map.copyOf(index);
    }

    public List<JobDefinition> all() {
        return definitions;
    }

    public Optional<JobDefinition> find(String jobName) {
        return Optional.ofNullable(byName.get(jobName));
    }

    private static List<JobDefinition> builtIn(
            long marketDelayMs, long newsDelayMs, long exportDelayMs) {
        return List.of(
                new JobDefinition(
                        JobNames.MARKET_OVERVIEW_COLLECT,
                        "行情总览采集",
                        JobNames.MARKET_OVERVIEW_HANDLER,
                        fixedDelayDescription(marketDelayMs),
                        true,
                        false,
                        // 本项目的三个定时任务都没有独立开关：它们必须一直在跑。
                        // 停用意味着改代码或去掉 @Scheduled，而不是翻一个配置位——
                        // 一个能关掉采集的开关，最终一定会有人在排查时关掉它然后忘记打开。
                        true,
                        "市场代码",
                        List.of("CN"),
                        "CN"),
                new JobDefinition(
                        JobNames.NEWS_INGEST,
                        "资讯/公告增量采集",
                        JobNames.NEWS_INGEST_HANDLER,
                        fixedDelayDescription(newsDelayMs),
                        true,
                        false,
                        true,
                        // 资讯采集天然覆盖全部已登记来源，没有"只采某一家"的需求；
                        // 留出这个参数只会让调用方以为它能缩小范围（实际上不能）。
                        null,
                        List.of(),
                        null),
                new JobDefinition(
                        JobNames.EXPORT_RETENTION_SWEEP,
                        "导出文件到期清理",
                        JobNames.EXPORT_RETENTION_HANDLER,
                        fixedDelayDescription(exportDelayMs),
                        true,
                        false,
                        true,
                        null,
                        List.of(),
                        null));
    }

    private static String fixedDelayDescription(long delayMs) {
        if (delayMs % 1000 == 0) {
            return "每 " + (delayMs / 1000) + " 秒（fixedDelay）";
        }
        return "每 " + delayMs + " 毫秒（fixedDelay）";
    }
}
