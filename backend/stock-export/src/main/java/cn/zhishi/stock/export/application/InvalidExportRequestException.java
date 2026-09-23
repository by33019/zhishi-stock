package cn.zhishi.stock.export.application;

/**
 * 导出请求本身不合法（400 / {@code INVALID_REQUEST}）。
 *
 * <p>与 {@link ExportException} 分开：这个异常的语义是"请求写错了，改一改再发"，
 * 而 {@code ExportException} 是"请求没问题，但当前状态/额度不允许"。
 * 前端据此决定是就地标红表单，还是给一个可重试的提示。
 */
public class InvalidExportRequestException extends RuntimeException {

    private final String code;

    public InvalidExportRequestException(String message) {
        this("INVALID_REQUEST", message);
    }

    public InvalidExportRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
