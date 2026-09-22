package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.ai.application.AiFeedbackService;
import cn.zhishi.stock.ai.application.AiFeedbackView;
import cn.zhishi.stock.ai.application.AiReportDetail;
import cn.zhishi.stock.ai.application.AiReportQueryService;
import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 报告接口（契约 §13.3 HIS-06 / HIS-08 / HIS-09）。
 *
 * <h2>为什么单独一个控制器，而不是塞进 {@code AiTaskController}</h2>
 * 路径同在 {@code /api/v1/ai} 下，但资源不同：任务是"排队中/运行中"的过程对象，
 * 报告是定稿后不可变的结果对象，反馈是挂在报告上的用户标注。M3-08 还要往这里挂
 * HIS-07（证据），全部挂到任务控制器上会让三套生命周期混在一个类里。
 *
 * <h2>归属校验在用例层</h2>
 * 与 {@code AiTaskController} 同一处置：别人的报告与不存在的报告返回同一句
 * {@code AI_REPORT_NOT_FOUND}，因此这里不因为多写一次判断而泄露"这个 ID 存在"（契约 §23.1）。
 *
 * <h2>读写两类的幂等要求不同</h2>
 * HIS-06 是纯读、不需要 {@code Idempotency-Key}；HIS-08 是 PUT——**方法本身**就是幂等的
 * （同一份 body 重复提交得到同一结果），契约也没有为它要求幂等键。HIS-09 同理：
 * 重复删除的结果都是"现在没有反馈"。
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiReportController {

    private final AiReportQueryService reports;
    private final AiFeedbackService feedbacks;
    private final Clock clock;

    public AiReportController(
            AiReportQueryService reports, AiFeedbackService feedbacks, Clock clock) {
        this.reports = reports;
        this.feedbacks = feedbacks;
        this.clock = clock;
    }

    /** HIS-06：获取本人结构化最终报告。 */
    @GetMapping("/reports/{reportId}")
    public ApiResponse<AiReportDetail> get(
            Authentication authentication,
            @PathVariable long reportId,
            HttpServletRequest request) {
        long userId = principal(authentication).userId();
        return success(reports.get(reportId, userId), request);
    }

    /** HIS-08：创建或替换本人对这份报告的唯一反馈。 */
    @PutMapping("/reports/{reportId}/feedback")
    public ApiResponse<AiFeedbackView> saveFeedback(
            Authentication authentication,
            @PathVariable long reportId,
            @RequestBody FeedbackRequest body,
            HttpServletRequest request) {
        long userId = principal(authentication).userId();
        return success(
                feedbacks.save(
                        reportId, userId, body.feedbackType(), body.reasonCode(), body.detail()),
                request);
    }

    /**
     * HIS-09：删除本人反馈。
     *
     * <p>本来就没有反馈时返回 {@code deleted=false} 而不是 404——契约该接口的响应字段
     * 就是 {@code deleted}，而"本来就没有"与"刚被你删掉"对调用方而言结果相同。
     */
    @DeleteMapping("/reports/{reportId}/feedback")
    public ApiResponse<DeletionResult> deleteFeedback(
            Authentication authentication,
            @PathVariable long reportId,
            HttpServletRequest request) {
        long userId = principal(authentication).userId();
        return success(new DeletionResult(feedbacks.delete(reportId, userId)), request);
    }

    private AccessTokenPrincipal principal(Authentication authentication) {
        return (AccessTokenPrincipal) authentication.getPrincipal();
    }

    private <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return ApiResponse.success(data, TraceIdFilter.current(request), OffsetDateTime.now(clock));
    }

    /**
     * HIS-08 的请求体。
     *
     * <p>三项都声明为 {@code String}：枚举绑定失败会被 Spring 转成
     * {@code MethodArgumentTypeMismatchException}，语义上把"取值不在白名单"混同为
     * "参数格式错误"。由用例层解析，与 STK-01 / NEWS-01 / AI-02 同一处理方式。
     *
     * @param feedbackType {@code HELPFUL} / {@code NOT_HELPFUL}，必填
     * @param reasonCode   差评原因，可空
     * @param detail       补充说明，可空，最多 300 字符
     */
    public record FeedbackRequest(String feedbackType, String reasonCode, String detail) {
    }

    /** HIS-09 的响应体。 */
    public record DeletionResult(boolean deleted) {
    }
}
