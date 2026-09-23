package cn.zhishi.stock.export.domain;

import java.time.Duration;

/**
 * 导出功能的契约数字，**每一条只有一处定义**。
 *
 * <p>这些值来自契约正文而不是拍脑袋：改一处就得同时改另一处的常量，
 * 最容易出现的形态是"校验用 5000、文档写 2000"，且两边都不会报错。
 * 放在一个类里让"契约给的上限是多少"成为可 grep 的事实。
 */
public final class ExportPolicy {

    /** 榜单导出最多 5,000 行（契约 §9.2 EXP-01、§22.1；PRD §7.9 与 EX-24）。 */
    public static final int MAX_ROWS = 5_000;

    /**
     * 单次导出的文件保留期（契约 §9.2："默认 24 小时过期"）。
     *
     * <p>契约明说导出**不作为永久业务事实**，所以这里刻意不给"永久保留"的开关：
     * 真要长期归档，应该走"新增 export_job 表"那条路，而不是把这个时长调大。
     */
    public static final Duration FILE_TTL = Duration.ofHours(24);

    /**
     * 作业记录的存活时长，**刻意长于文件**（48 小时 > 24 小时）。
     *
     * <p>若两者相同，"文件已过期"与"这个导出任务从不存在"在数据上就无法区分，
     * 而契约要求前者返回 {@code EXPORT_EXPIRED}、后者返回 404。
     * 记录多活一天，就是为了让"过期"这个结论有据可依。
     */
    public static final Duration RECORD_TTL = Duration.ofHours(48);

    /** 榜单导出限流：2 次/分钟（契约 §22.1）。 */
    public static final int RATE_LIMIT_PER_MINUTE = 2;

    /** 限流计数窗口。 */
    public static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(1);

    private ExportPolicy() {
    }
}
