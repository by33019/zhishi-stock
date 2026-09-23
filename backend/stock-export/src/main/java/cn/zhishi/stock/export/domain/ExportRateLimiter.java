package cn.zhishi.stock.export.domain;

/**
 * 导出限流端口（契约 §22.1：榜单导出 2 次/分钟，维度为用户）。
 *
 * <p>做成端口而不是在用例里直接动 Redis：限流是否生效、窗口怎么算，
 * 是可以用桩断言的行为（超限必须抛 429，且**计数发生在真正创建作业时**，
 * 而不是在幂等重放时）。
 */
public interface ExportRateLimiter {

    /** 记一次并检查；超限时抛出 429 业务异常。 */
    void acquire(long userId);
}
