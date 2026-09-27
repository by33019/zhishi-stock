package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.market.application.SecurityQuoteBatchQueryService;
import cn.zhishi.stock.market.application.SecurityQuoteBatchQueryService.SecurityQuoteBatchQueryResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批量行情（契约 §8 STK-05 {@code POST /quotes/securities/batch-query}，PUBLIC）。
 *
 * <h2>为什么自成一个控制器</h2>
 * 路径前缀是 {@code /quotes} 而不是 {@code /securities}——契约按"读法"分资源：
 * 证券资料走 STK-01~04 的 {@code /securities}，行情快照的批量入口是独立的
 * {@code /quotes}。挂在 SecurityController 下就得用反直觉的
 * {@code /securities/../quotes}，或者在类级前缀上做例外。
 *
 * <h2>上限 50 在用例层</h2>
 * 契约写明"去重后 1 至 50 个"；这是业务边界而不是格式错误，
 * 由 {@code SecurityQuoteBatchQueryService} 抛 400，控制器只做形状校验。
 */
@RestController
@RequestMapping("/api/v1/quotes")
public class QuotesController {

    private final SecurityQuoteBatchQueryService batchQuery;
    private final Clock clock;

    public QuotesController(SecurityQuoteBatchQueryService batchQuery, Clock clock) {
        this.batchQuery = batchQuery;
        this.clock = clock;
    }

    /** STK-05：批量查询首屏行情。 */
    @PostMapping("/securities/batch-query")
    public ResponseEntity<ApiResponse<SecurityQuoteBatchQueryResult>> batchQuery(
            @Valid @RequestBody BatchQuoteRequest body,
            HttpServletRequest request) {
        SecurityQuoteBatchQueryResult result = batchQuery.query(body.securityIds());
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.success(
                        result,
                        TraceIdFilter.current(request),
                        OffsetDateTime.now(clock)));
    }

    /** STK-05 的请求体。 */
    public record BatchQuoteRequest(@NotEmpty List<String> securityIds) {
    }
}
