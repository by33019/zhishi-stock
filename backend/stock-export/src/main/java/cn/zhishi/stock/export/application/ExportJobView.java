package cn.zhishi.stock.export.application;

import cn.zhishi.stock.export.domain.ExportJob;
import cn.zhishi.stock.export.domain.ExportJobStatus;
import cn.zhishi.stock.export.domain.ExportType;
import java.time.OffsetDateTime;

/**
 * EXP-02 的响应体（契约 §9.2 逐字给出的 8 个字段，外加发起时间）。
 *
 * <p>{@code fileName} / {@code rowCount} / {@code error} 在未就绪时是 {@code null}，
 * 而**不是空串或 0**：空串会让"文件名是空"与"还没有文件名"看起来一样，
 * 0 会让"零行结果"与"还没跑"看起来一样。这三种情况的处置完全不同。
 */
public record ExportJobView(
        String exportId,
        ExportType exportType,
        String status,
        int progress,
        String fileName,
        Integer rowCount,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        String error) {

    public static ExportJobView of(ExportJob job) {
        return new ExportJobView(
                job.exportId(),
                job.request().exportType(),
                job.status().name(),
                job.progress(),
                job.fileName(),
                job.status() == ExportJobStatus.COMPLETED ? job.rowCount() : null,
                job.createdAt(),
                job.expiresAt(),
                job.errorMessage());
    }
}
