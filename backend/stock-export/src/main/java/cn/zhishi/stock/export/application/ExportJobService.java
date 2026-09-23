package cn.zhishi.stock.export.application;

import cn.zhishi.stock.export.domain.ExportAuditEvent;
import cn.zhishi.stock.export.domain.ExportAuditLog;
import cn.zhishi.stock.export.domain.ExportDataSource;
import cn.zhishi.stock.export.domain.ExportFileStore;
import cn.zhishi.stock.export.domain.ExportFileWriter;
import cn.zhishi.stock.export.domain.ExportJob;
import cn.zhishi.stock.export.domain.ExportJobStatus;
import cn.zhishi.stock.export.domain.ExportJobStore;
import cn.zhishi.stock.export.domain.ExportPolicy;
import cn.zhishi.stock.export.domain.ExportRateLimiter;
import cn.zhishi.stock.export.domain.ExportRequest;
import cn.zhishi.stock.export.domain.ExportTable;
import cn.zhishi.stock.export.domain.ExportType;
import cn.zhishi.stock.export.domain.StockRankingColumn;
import cn.zhishi.stock.market.domain.RankingType;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 导出作业用例（契约 §9.2 EXP-01~EXP-04）。
 *
 * <h2>为什么是"异步 + 轮询"，而不是同步下载</h2>
 * 契约把四个端点定义成一套作业模型（202 + 查状态 + 下载 + 删除），
 * 而不是一个"点一下直接吐文件"的接口。这不是排版偏好：导出上限是 5,000 行，
 * 而模拟市场的证券全集就是 5,149 只——**整市场导出必然接近上限**，
 * 取数 + 写 xlsx 不是一个适合压在 HTTP 请求里的时长，
 * 何况真实数据源接入后取数还要更久。照契约做作业模型，前端只需轮询，不必面对超时。
 *
 * <h2>越权只有一处判据</h2>
 * 所有按 id 的操作都走 {@link #require}，它把"不存在"与"不属于本人"合并成同一个 404。
 * 分开报会让攻击者用一个 id 就能判断它是否存在（同 HIS-01~HIS-09 的口径）。
 *
 * <h2>生成失败的可见性</h2>
 * 生成在另一个线程上跑，异常不会回到发起请求的人手上。所以**失败必须落进记录**
 * （{@code status=FAILED} + {@code error}），否则用户看到的是一个永远停在
 * {@code RUNNING} 的作业，而没有任何地方能告诉他为什么。
 */
public class ExportJobService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExportJobService.class);

    /** 文件名里的时间戳。用纯 ASCII 的名字，避免 {@code Content-Disposition} 的编码问题。 */
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private final ExportJobStore jobs;
    private final ExportFileStore files;
    private final ExportDataSource dataSource;
    private final ExportFileWriter writer;
    private final ExportRateLimiter rateLimiter;
    private final ExportAuditLog auditLog;
    private final Supplier<String> exportIdGenerator;
    private final Executor executor;
    private final Clock clock;

    public ExportJobService(
            ExportJobStore jobs,
            ExportFileStore files,
            ExportDataSource dataSource,
            ExportFileWriter writer,
            ExportRateLimiter rateLimiter,
            ExportAuditLog auditLog,
            Supplier<String> exportIdGenerator,
            Executor executor,
            Clock clock) {
        this.jobs = jobs;
        this.files = files;
        this.dataSource = dataSource;
        this.writer = writer;
        this.rateLimiter = rateLimiter;
        this.auditLog = auditLog;
        this.exportIdGenerator = exportIdGenerator;
        this.executor = executor;
        this.clock = clock;
    }

    // ---------- EXP-01 ----------

    /**
     * 受理一次导出。
     *
     * <p>校验与限流都在**这里同步做**：参数错误必须当场 400、
     * 超频必须当场 429，而不是受理成功之后在作业里报 {@code FAILED}——
     * 那样调用方拿到的是 202，却永远等不到文件。
     */
    public ExportJobAccepted create(long userId, ExportRequest request) {
        ExportRequest validated = validate(request);
        rateLimiter.acquire(userId);

        OffsetDateTime now = OffsetDateTime.now(clock);
        ExportJob job = ExportJob.queued(
                exportIdGenerator.get(), userId, validated, now, ExportPolicy.FILE_TTL);
        jobs.save(job);

        try {
            executor.execute(() -> generate(job.exportId()));
        } catch (RejectedExecutionException exception) {
            // 线程池满：作业已经落库，若就此返回 500，它会永远停在 QUEUED。
            // 落一个明确的失败态再报错，让"等不到文件"这件事有据可查。
            jobs.save(job.failed(
                    ExportErrorCode.BUSY.externalCode(), "导出服务繁忙，请稍后重试"));
            throw new ExportException(ExportErrorCode.BUSY, "导出服务繁忙，请稍后重试");
        }

        return new ExportJobAccepted(
                job.exportId(), job.request().exportType(), job.status().name(),
                job.createdAt(), job.expiresAt());
    }

    // ---------- EXP-02 ----------

    /** 查询本人作业状态。**读时就地判过期**（见 {@link ExportPolicy#RECORD_TTL} 的说明）。 */
    public ExportJobView status(long userId, String exportId) {
        ExportJob job = require(userId, exportId);
        if (job.status() != ExportJobStatus.EXPIRED && job.pastRetentionAt(OffsetDateTime.now(clock))) {
            job = expire(job);
        }
        return ExportJobView.of(job);
    }

    // ---------- EXP-03 ----------

    /** 取文件内容。三种"拿不到"分别报 {@code NOT_READY} / {@code FAILED} / {@code EXPIRED}。 */
    public ExportDownload download(long userId, String exportId) {
        ExportJob job = require(userId, exportId);
        if (job.status() != ExportJobStatus.EXPIRED && job.pastRetentionAt(OffsetDateTime.now(clock))) {
            job = expire(job);
        }
        // 就地判过期会重建记录，于是 job 不再是"事实上的最终变量"。
        // 取一个 final 副本给下面的 lambda 用，而不是把重赋值塞进 lambda 里。
        ExportJob current = job;
        switch (current.status()) {
            case EXPIRED -> throw ExportException.expired();
            case FAILED -> throw ExportException.failed(current.errorMessage());
            case QUEUED, RUNNING -> throw ExportException.notReady();
            case COMPLETED -> {
                // 落下去继续取文件：COMPLETED 但文件不在，说明存储被动过（卷重建、手工清理）。
                // 把它当成过期是**谎报**（保留期还没到），所以标 FAILED 并让调用方重新发起。
            }
        }
        byte[] content = files.read(exportId, current.fileName()).orElseThrow(() -> {
            jobs.save(current.failed(
                    ExportErrorCode.FAILED.externalCode(), "导出文件不可读，请重新发起导出"));
            LOGGER.error("导出文件缺失：exportId={}，fileName={}", exportId, current.fileName());
            return ExportException.failed("导出文件不可读，请重新发起导出");
        });
        return new ExportDownload(content, current.fileName(), current.dataCutoffAt());
    }

    // ---------- EXP-04 ----------

    /**
     * 删除本人作业与文件。
     *
     * <p>契约要求"已过期时保持幂等成功"。记录比文件多活 24 小时（{@link ExportPolicy#RECORD_TTL}），
     * 所以文件已过期的作业**仍然查得到**，删除仍会成功返回 {@code true}——
     * 这正是那条要求能落地的前提。若两者 TTL 相同，过期作业连记录都没了，
     * 删除就只能报 404，与契约冲突。
     *
     * <p>不存在的 id 仍报 404（而同 HIS-04：列表里看不到的东西，删除接口也不该说"删好了"）。
     */
    public boolean delete(long userId, String exportId) {
        ExportJob job = require(userId, exportId);
        files.delete(exportId);
        jobs.delete(exportId);
        LOGGER.debug("导出已删除：exportId={}，status={}", exportId, job.status());
        return true;
    }

    // ---------- 生成 ----------

    /**
     * 生成文件。跑在 {@code executor} 上，**任何异常都必须落成 {@code FAILED}**。
     *
     * <p>刻意不把 {@code ExportException}（如行数超限）和未知异常合并成一种：
     * 前者要保留业务码，前端据此提示"缩小范围"；后者只该说"生成失败"，
     * 因为把内部异常的分类暴露给用户只会误导。
     */
    public void generate(String exportId) {
        ExportJob job = jobs.find(exportId).orElse(null);
        if (job == null) {
            // 受理后被删掉了（用户手快点了删除）。没有可推进的对象，静默结束是对的。
            LOGGER.debug("导出作业已不存在，跳过生成：exportId={}", exportId);
            return;
        }
        job = job.running();
        jobs.save(job);
        try {
            ExportTable table = dataSource.tableOf(job.request());
            job = job.dataReady(table.rowCount(), table.dataTime());
            jobs.save(job);

            byte[] content = writer.write(table);
            String fileName = fileNameOf(job, table);
            files.write(exportId, fileName, content);
            jobs.save(job.completed(fileName, table.rowCount(), table.dataTime()));
            LOGGER.info("导出完成：exportId={}，rows={}，fileName={}",
                    exportId, table.rowCount(), fileName);
        } catch (ExportException exception) {
            failWith(job, exception.code().externalCode(), exception.getMessage());
        } catch (RuntimeException exception) {
            LOGGER.error("导出生成异常：exportId={}", exportId, exception);
            failWith(job, ExportErrorCode.FAILED.externalCode(), "生成过程出错，请稍后重试");
        }
    }

    private void failWith(ExportJob job, String code, String message) {
        jobs.save(job.failed(code, message));
        // 生成结果也要审计：没有它，"谁在什么时候导了但没导成"在 sys_log 上是个空洞。
        auditLog.record(new ExportAuditEvent(
                job.userId(), null, "EXPORT_GENERATE", null, null,
                ExportAuditEvent.FAILURE, "exportId=" + job.exportId() + " code=" + code,
                null, null));
    }

    /** 文件名带榜单类型与生成时间：用户下载目录里堆了十几个文件时，这一点足以区分它们。 */
    private String fileNameOf(ExportJob job, ExportTable table) {
        String rankingType = job.request().filters().rankingType();
        String suffix = rankingType == null
                ? ""
                : "-" + rankingType.trim().toLowerCase(Locale.ROOT);
        return "stock-ranking" + suffix + "-" + FILE_STAMP.format(OffsetDateTime.now(clock)) + ".xlsx";
    }

    /**
     * 把一个已到期的作业推进为 {@code EXPIRED}，并顺手删掉文件。
     *
     * <p><b>删除失败不阻断状态推进。</b>这个方法是**读路径**（EXP-02 / EXP-03）的一部分，
     * 而"清理失败"与"读不到状态"是两件事：让后者跟着前者一起失败，
     * 等于一块删不掉的旧文件就能把查询接口打成 500。抛出去的是 ERROR 日志，
     * 而未删掉的文件不会丢失干净的收尾——清理任务按 {@code expiresAt} 取的是全部到期的作业，
     * 与状态无关，因此它会一遍遍重试到成功，届时状态与磁盘才真正一致。
     */
    public ExportJob expire(ExportJob job) {
        try {
            files.delete(job.exportId());
        } catch (RuntimeException exception) {
            LOGGER.error("导出文件清理失败，仍标记为已过期：exportId={}", job.exportId(), exception);
        }
        ExportJob expired = job.expired();
        jobs.save(expired);
        return expired;
    }

    // ---------- 校验与归属 ----------

    private ExportRequest validate(ExportRequest request) {
        if (request == null || request.exportType() == null) {
            // 兜底：正常路径上 Web 层已经把 exportType 解析成枚举（那里能区分"没传"与"取值非法"，
            // 提示更准确）。这里只保证"拿不到类型"不会让作业落进一个无法判断的状态。
            throw new InvalidExportRequestException(
                    "exportType 必填，可选值：" + ExportType.expectedCodes());
        }
        ExportType supplied = request.exportType();
        if (!supplied.supported()) {
            throw ExportException.typeUnsupported(supplied.label());
        }
        String rankingType = request.filters().rankingType();
        if (rankingType == null || RankingType.fromCode(rankingType).isEmpty()) {
            // 同步挡住而不是留给异步生成：参数错了要让调用方当场拿到 400，
            // 而不是受理成功之后再在作业里报 FAILED——那时他已经等了一轮轮询。
            throw new InvalidExportRequestException(
                    "rankingType 必须为 " + String.join("、", RankingType.codes()) + " 之一");
        }
        StockRankingColumn.firstUnknown(request.columns()).ifPresent(unknown -> {
            throw new InvalidExportRequestException(
                    "columns 含白名单之外的字段：" + unknown
                            + "；可选字段：" + String.join("、", StockRankingColumn.keys()));
        });
        return new ExportRequest(
                supplied,
                request.filters(),
                StockRankingColumn.resolve(request.columns()).stream()
                        .map(StockRankingColumn::key)
                        .toList());
    }

    private ExportJob require(long userId, String exportId) {
        ExportJob job = jobs.find(exportId).orElse(null);
        if (job == null || job.userId() != userId) {
            throw ExportException.notFound();
        }
        return job;
    }
}
