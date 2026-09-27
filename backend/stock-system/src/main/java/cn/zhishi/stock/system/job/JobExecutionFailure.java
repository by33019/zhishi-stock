package cn.zhishi.stock.system.job;

/**
 * 带**脱敏结论**的任务失败异常。
 *
 * <h2>为什么需要它</h2>
 * 执行体抛出的异常会被 {@code JobExecutionRecorder} 写成一条 FAILED 记录。
 * 记录器只能看到异常类型与 message，而"这段 message 是不是第三方原始响应"
 * 只有抛异常的那段代码知道。契约 ADM-JOB-04 明确要求不落第三方完整原始响应，
 * 因此给"我知道该怎么安全地描述这次失败"的执行体一个显式出口：
 * 抛这个类型，记录器就用这里给定的三个字段，不再去读 message。
 *
 * <h2>不抛这个类型时的口径</h2>
 * 记录器把 {@code errorCategory} 记为 {@code UNEXPECTED}、{@code errorCode} 记异常类名、
 * {@code errorSummary} 记截断后的 message。项目的异常 message 都是自撰的中文描述，
 * 但执行体若确实可能带上第三方内容，就应当包一层这个类型——
 * 那是唯一能保证摘要安全的做法，靠"事后裁剪"做不到。
 */
public class JobExecutionFailure extends RuntimeException {

    /** 错误类别，用于聚合统计。控制在 32 字符内（{@code error_category} 列宽）。 */
    private final transient String errorCategory;

    /** 错误码。控制在 64 字符内（{@code error_code} 列宽）。 */
    private final transient String errorCode;

    /** 脱敏后的错误摘要。控制在 1000 字符内（{@code error_summary} 列宽）。 */
    private final transient String safeSummary;

    public JobExecutionFailure(
            String errorCategory, String errorCode, String safeSummary, Throwable cause) {
        super(safeSummary, cause);
        this.errorCategory = errorCategory;
        this.errorCode = errorCode;
        this.safeSummary = safeSummary;
    }

    public String errorCategory() {
        return errorCategory;
    }

    public String errorCode() {
        return errorCode;
    }

    public String safeSummary() {
        return safeSummary;
    }
}
