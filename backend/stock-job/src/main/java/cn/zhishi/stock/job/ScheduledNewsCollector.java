package cn.zhishi.stock.job;

import cn.zhishi.stock.news.application.NewsIngestionService;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 新闻/公告增量采集（架构 §"新闻/公告增量采集 每 2 分钟"）。
 *
 * <h2>为什么不传游标</h2>
 * {@link NewsIngestionService#ingest} 的下界为 {@code null}。把"上次采到哪里"记在
 * 调度侧或 Provider 侧，等于让**两个模块各自维护一份进度**；而重投本来就会被
 * {@code uk_stock_news_source_content} 挡住（来源 ID 幂等），内容改写过的重投
 * 则由内容指纹判为 {@code DUPLICATE}。因此游标不是必需的，且少一处状态就少一处不一致。
 *
 * <h2>为什么失败要在这里留痕</h2>
 * {@link NewsIngestionService#recordSyncFailure} 标了 {@code REQUIRES_NEW}：
 * 它必须在采集事务回滚**之后**执行，所以只能由调度方在 {@code ingest} 抛出之后调用。
 * 写进 {@code ingest} 内部会跟着回滚，症状是"每次失败都不留痕迹"，
 * 而 NEWS-03 的同步状态永远报 OK。
 *
 * <h2>为什么记录完还要抛出去</h2>
 * Spring 的 fixed-delay 任务由 {@code LOG_AND_SUPPRESS_ERROR_HANDLER} 包裹，
 * 抛异常只会记一条 ERROR 然后照常跑下一轮，不会中断调度。只写库不抛日志是哑的——
 * 运维得先知道去查 NEWS-03 才看得到失败。
 */
@Component
public class ScheduledNewsCollector {

    private final NewsIngestionService ingestion;

    private final Clock clock;

    public ScheduledNewsCollector(NewsIngestionService ingestion, Clock clock) {
        this.ingestion = ingestion;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${stock.news.collect-initial-delay-ms:1000}",
            fixedDelayString = "${stock.news.collect-delay-ms:120000}")
    public void collect() {
        try {
            ingestion.ingest(null);
        } catch (RuntimeException exception) {
            markFailureWithoutMaskingTheCause(exception);
            throw exception;
        }
    }

    /**
     * 写失败标记本身失败时，把次要异常挂到原始异常上再抛原始异常。
     *
     * <p>两个失败最常一起出现的方式就是"数据库不可用"——那也正是采集失败的原因。
     * 若让标记的异常直接覆盖原始异常，日志里只剩一个次要错误，排查方向会被带偏。
     */
    private void markFailureWithoutMaskingTheCause(RuntimeException cause) {
        try {
            ingestion.recordSyncFailure(OffsetDateTime.now(clock));
        } catch (RuntimeException markerFailure) {
            cause.addSuppressed(markerFailure);
        }
    }
}
