package cn.zhishi.stock.export.application;

import cn.zhishi.stock.export.domain.ExportType;
import java.time.OffsetDateTime;

/**
 * EXP-01 的响应体（HTTP 202）。
 *
 * <p>契约只要求 {@code exportId} / {@code status} / {@code createdAt} / {@code expiresAt}。
 * 这里**不多给字段**：多给一个 {@code fileName} 就会让人以为"创建完就能下载"，
 * 而文件此刻并不存在。
 */
public record ExportJobAccepted(
        String exportId,
        ExportType exportType,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt) {
}
