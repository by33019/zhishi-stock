package cn.zhishi.stock.export.application;

/**
 * 导出域的业务码与 HTTP 状态（契约 §9.2 的异常清单 + §23.1 的状态语义）。
 *
 * <p>状态码写在这里而不是 {@code GlobalExceptionHandler} 里，与 {@code AiTaskErrorCode}
 * 同一条理由：同一个域里 400 / 404 / 409 / 422 / 429 都有，在 handler 里 switch
 * 会让新增一个业务码必须同时改两处。
 *
 * <h2>三个"拿不到文件"的码故意分开</h2>
 * {@code EXPORT_NOT_READY}（还在生成）、{@code EXPORT_FAILED}（生成失败）、
 * {@code EXPORT_EXPIRED}（过了保留期）的 HTTP 状态都是 409——
 * 三者都是"这个作业当前的状态做不到你要的事"，但前端给出的下一步完全不同：
 * 等一会儿、重新发起、或重新发起并注意保留期。合并成一个码会让提示变成一句废话。
 */
public enum ExportErrorCode {

    /** 作业不存在，或不属于当前用户（为防水平越权，两种情况不区分）。 */
    NOT_FOUND("EXPORT_NOT_FOUND", 404),
    /** 作业还在排队或生成中，文件尚未就绪。 */
    NOT_READY("EXPORT_NOT_READY", 409),
    /** 作业生成失败；原因在 {@code errorMessage}。 */
    FAILED("EXPORT_FAILED", 409),
    /** 文件已过保留期，记录仍在但不再提供下载。 */
    EXPIRED("EXPORT_EXPIRED", 409),
    /** 筛选结果超过 5,000 行：不生成，请缩小范围（PRD EX-24）。 */
    LIMIT_EXCEEDED("EXPORT_LIMIT_EXCEEDED", 422),
    /** 超过"2 次/分钟"的导出频次（契约 §22.1）。 */
    RATE_LIMITED("EXPORT_RATE_LIMITED", 429),
    /**
     * 导出类型认得但本版本不支持（{@code AI_REPORT}，PRD §5.3 推迟到 V1.1）。
     *
     * <p>与"取值不合法"分开：前者要等版本，后者要改请求，前端提示与用户动作都不同。
     */
    TYPE_UNSUPPORTED("EXPORT_TYPE_UNSUPPORTED", 400),

    /**
     * 生成线程池已满，作业无法被受理执行。
     *
     * <p>这个码不在契约 §9.2 的"常见异常"列表里，是**本实现新增**的：
     * 契约给的那几个码没有一个能表达"服务端此刻忙不过来"——
     * {@code EXPORT_FAILED} 说的是"生成过程出错"（该重试同一个作业没有意义），
     * 而这里要表达的是"稍后再来"（重试是有意义的）。
     * 取 503 与 §23.1 的状态语义一致，前缀也仍在 {@code EXPORT_} 之内。
     * 作业会被落成 {@code FAILED} 并带上这个码，避免它永远停在 {@code QUEUED}。
     */
    BUSY("EXPORT_BUSY", 503);

    private final String externalCode;
    private final int httpStatus;

    ExportErrorCode(String externalCode, int httpStatus) {
        this.externalCode = externalCode;
        this.httpStatus = httpStatus;
    }

    public String externalCode() {
        return externalCode;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
