package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.ai.application.AiReportDetail;
import cn.zhishi.stock.ai.application.AiReportQueryService;
import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 报告接口（契约 §13.3 HIS-06）。
 *
 * <h2>为什么单独一个控制器，而不是塞进 {@code AiTaskController}</h2>
 * 路径同在 {@code /api/v1/ai} 下，但资源不同：任务是"排队中/运行中"的过程对象，
 * 报告是定稿后不可变的结果对象。M3-08 还要往这里挂 HIS-07（证据）与 HIS-08 / HIS-09（反馈），
 * 全部挂到任务控制器上会让"读写两套生命周期"混在一个类里。
 * （这与 {@code NewsController} 把 STK-10 / SEC-07 收进来的理由相反——那几接口同属资讯域，
 * 这里则是同域不同资源。）
 *
 * <h2>归属校验在用例层</h2>
 * 与 {@code AiTaskController} 同一处置：别人的报告与不存在的报告返回同一句
 * {@code AI_REPORT_NOT_FOUND}，因此这里不因为多写一次判断而泄露"这个 ID 存在"（契约 §23.1）。
 *
 * <h2>不需要 {@code Idempotency-Key}</h2>
 * 纯读接口：重复请求本来就应当返回同样的结果，不存在"重复消耗"的风险。
 * 契约只给写接口要求幂等键，这里是照它执行而不是顺手加上。
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiReportController {

    private final AiReportQueryService reports;
    private final Clock clock;

    public AiReportController(AiReportQueryService reports, Clock clock) {
        this.reports = reports;
        this.clock = clock;
    }

    /** HIS-06：获取本人结构化最终报告。 */
    @GetMapping("/reports/{reportId}")
    public ApiResponse<AiReportDetail> get(
            Authentication authentication,
            @PathVariable long reportId,
            HttpServletRequest request) {
        long userId = ((AccessTokenPrincipal) authentication.getPrincipal()).userId();
        return ApiResponse.success(
                reports.get(reportId, userId),
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock));
    }
}
