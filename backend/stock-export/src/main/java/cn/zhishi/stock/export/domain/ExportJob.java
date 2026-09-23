package cn.zhishi.stock.export.domain;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 一次导出作业。
 *
 * <h2>它同时是"状态"和"做这件事需要的一切"</h2>
 * 作业状态存在 Redis、由异步线程推进，而异步线程手上只有这个记录——
 * 所以 {@link ExportRequest} 必须原样留在里面（生成时要用它取数据、失败排查时要用它复现），
 * 而不是留在创建它的那次请求的栈上。
 *
 * <h2>状态推进写在记录里，不写在服务里</h2>
 * {@link #running()} / {@link #completed} / {@link #failed} 都返回新记录并**清掉不属于新状态的字段**：
 * 失败的作业不该还留着一个 {@code fileName}，否则"文件在哪"会有两个答案
 * （{@code status} 说失败、{@code fileName} 说有一个文件）。
 *
 * <h2>{@code progress} 不是估算出来的百分比</h2>
 * 生成恰好有两个可观测阶段：**取数**与**写出文件**。{@code progress} 就是
 * "已完成阶段数 / 2"，因此它只会出现 0 / 50 / 100 三个值。
 * 画一条平滑动效需要一个能估算的进度，而这个流程里没有任何东西能给出它——
 * 编一个"73%"是纯粹的虚构，且会在真正卡住时把排查引向错误的方向。
 *
 * @param exportId     作业对外标识（字符串，避免 Snowflake 在前端丢精度）
 * @param userId       发起人。所有读写都必须按它过滤，越权只在这一处挡
 * @param request      原始请求，供生成与排查使用
 * @param status       当前状态
 * @param progress     已完成阶段数占两阶段的百分比：0 / 50 / 100
 * @param fileName     已完成文件的下载文件名（含扩展名）
 * @param rowCount     数据行数（不含表头与说明）
 * @param dataCutoffAt 数据截止时间：这份文件里的数字属于哪一刻
 * @param createdAt    受理时间
 * @param expiresAt    文件过期时间（{@code createdAt} + 保留期）
 * @param errorCode    失败时的业务码
 * @param errorMessage 失败原因（对外文案，不含堆栈）
 */
public record ExportJob(
        String exportId,
        long userId,
        ExportRequest request,
        ExportJobStatus status,
        int progress,
        String fileName,
        int rowCount,
        OffsetDateTime dataCutoffAt,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        String errorCode,
        String errorMessage) {

    /** 取数阶段完成后的进度。 */
    public static final int PROGRESS_DATA_READY = 50;

    /** 受理一个新作业，状态为 {@code QUEUED}（EXP-01 的响应就是它）。 */
    public static ExportJob queued(
            String exportId, long userId, ExportRequest request,
            OffsetDateTime now, Duration fileTtl) {
        return new ExportJob(
                exportId, userId, request, ExportJobStatus.QUEUED,
                0, null, 0, null, now, now.plus(fileTtl), null, null);
    }

    /** 真正开始生成。数据截止时间与行数要等到取数完成才知道，所以这里只改状态。 */
    public ExportJob running() {
        return copy(ExportJobStatus.RUNNING, 0, null, 0, null, errorCode, errorMessage);
    }

    /** 取数完成：数据已拿到手，接下来只剩写出文件。 */
    public ExportJob dataReady(int rows, OffsetDateTime cutoffAt) {
        return copy(
                ExportJobStatus.RUNNING, PROGRESS_DATA_READY, null, rows, cutoffAt,
                errorCode, errorMessage);
    }

    /**
     * 生成成功。
     *
     * <p>{@code dataCutoffAt} 取自**数据本身**（快照的 {@code dataTime}），
     * 不是"生成那一刻"——契约与 PRD §10 都要求导出的数字能对上一个明确的数据时间，
     * 用生成时间填会让一份基于昨日快照的文件看起来是"刚才的数据"。
     */
    public ExportJob completed(String completedFileName, int completedRows, OffsetDateTime cutoffAt) {
        return copy(
                ExportJobStatus.COMPLETED, 100, completedFileName, completedRows,
                cutoffAt, null, null);
    }

    /** 生成失败。清掉 {@code fileName}，避免"失败却有一个文件"这种自相矛盾的记录。 */
    public ExportJob failed(String code, String message) {
        return copy(ExportJobStatus.FAILED, progress, null, rowCount, dataCutoffAt, code, message);
    }

    /**
     * 标记为已过期。
     *
     * <p>正常路径上**文件先被清掉、然后才调它**，否则会出现"状态说过期、文件还在磁盘上"。
     * 但这不是一条硬保证：清理本身可能失败（权限、卷异常），而读路径不能因为一次
     * 清理失败就不可用（见 {@code ExportJobService#expire}）。此时状态领先于磁盘，
     * 由清理任务按到期时间反复重试把两者拉回一致——它取的是"已到期"这个客观条件，
     * 与状态无关，因此不会因为状态已是 {@code EXPIRED} 就跳过。
     */
    public ExportJob expired() {
        return copy(ExportJobStatus.EXPIRED, 100, null, rowCount, dataCutoffAt, null, null);
    }

    /** 文件此刻是否已过保留期。**过期判定只有这一处。** */
    public boolean pastRetentionAt(OffsetDateTime now) {
        return !now.isBefore(expiresAt);
    }

    private ExportJob copy(
            ExportJobStatus nextStatus,
            int nextProgress,
            String nextFileName,
            int nextRowCount,
            OffsetDateTime nextCutoffAt,
            String nextErrorCode,
            String nextErrorMessage) {
        return new ExportJob(
                exportId, userId, request, nextStatus, nextProgress,
                nextFileName, nextRowCount, nextCutoffAt,
                createdAt, expiresAt, nextErrorCode, nextErrorMessage);
    }
}
