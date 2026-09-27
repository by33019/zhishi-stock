package cn.zhishi.stock.system.ratelimit;

import java.time.Duration;

/**
 * 全站请求限流的端口（契约 §22.1 的基线）。
 *
 * <h2>为什么是全站一个端口，而不是各域各一个</h2>
 * 已知问题 #17 的原话："限流基础设施整体不存在，单为自选做一套会是
 * 一处实现、八处复制"。导出域已经私有了一份（{@code RedisExportRateLimiter}），
 * 本端口是它的泛化——键、上限、窗口全部参数化，语义（固定窗口）与降级
 * （Redis 异常放行）保持同一套口径。导出域的实现保留不动（它带着自己的
 * 业务码与文档），新接入的类别一律走本端口。
 *
 * <p>键由调用方拼装（{@code 类别:维度:标识}），本端口只回答
 * "这个桶在这一窗口里还能不能进"。
 */
public interface RequestRateLimiter {

    /**
     * 记一次并判定。
     *
     * @param key    桶标识（含类别与维度，如 {@code search:ip:1.2.3.4}）
     * @param limit  窗口上限
     * @param window 窗口长度（固定窗口对齐到 window 的整数倍边界）
     */
    RateLimitDecision acquire(String key, int limit, Duration window);
}
