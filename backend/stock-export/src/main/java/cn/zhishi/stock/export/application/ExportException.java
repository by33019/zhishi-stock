package cn.zhishi.stock.export.application;

import cn.zhishi.stock.export.domain.ExportPolicy;

/** 导出域的业务异常。业务码与 HTTP 状态都由 {@link ExportErrorCode} 携带。 */
public class ExportException extends RuntimeException {

    private final transient ExportErrorCode code;

    public ExportException(ExportErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ExportErrorCode code() {
        return code;
    }

    public static ExportException notFound() {
        // 不存在与不属于本人共用一句话：可区分的文案等于告诉攻击者"这个 id 是存在的"。
        return new ExportException(ExportErrorCode.NOT_FOUND, "导出任务不存在或已清理");
    }

    public static ExportException notReady() {
        return new ExportException(ExportErrorCode.NOT_READY, "导出文件尚未生成完成，请稍后重试");
    }

    public static ExportException failed(String reason) {
        return new ExportException(ExportErrorCode.FAILED, "导出任务生成失败：" + reason);
    }

    public static ExportException expired() {
        return new ExportException(ExportErrorCode.EXPIRED, "导出文件已过保留期，请重新发起导出");
    }

    public static ExportException rateLimited() {
        return new ExportException(
                ExportErrorCode.RATE_LIMITED,
                "导出过于频繁：每分钟最多 " + ExportPolicy.RATE_LIMIT_PER_MINUTE + " 次，请稍后重试");
    }

    public static ExportException typeUnsupported(String label) {
        return new ExportException(
                ExportErrorCode.TYPE_UNSUPPORTED,
                label + "导出尚未交付（AI 报告导出按 PRD 安排在 V1.1）；本版本支持：行情榜单");
    }

    public static ExportException limitExceeded(int rows) {
        return new ExportException(
                ExportErrorCode.LIMIT_EXCEEDED,
                "筛选结果共 " + rows + " 行，超过单次导出的 "
                        + ExportPolicy.MAX_ROWS
                        + " 行上限；请缩小榜单范围（交易所 / 板块 / 是否含 ST 与停牌）后重试");
    }
}
