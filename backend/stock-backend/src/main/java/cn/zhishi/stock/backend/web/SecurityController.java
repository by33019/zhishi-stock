package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.market.application.SecurityDetailQueryService;
import cn.zhishi.stock.market.application.SecurityListCriteria;
import cn.zhishi.stock.market.application.SecurityQueryService;
import cn.zhishi.stock.market.domain.KlineSeries;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.SecuritySearchResult;
import cn.zhishi.stock.market.domain.SecuritySummary;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 证券主数据与个股行情接口（STK-01 / STK-02 / STK-04 / STK-07）。
 *
 * <p>参数一律声明为 {@code String} / {@code Integer} 并在用例层校验：
 * 非法取值必须返回业务码 {@code INVALID_REQUEST}，而枚举绑定失败会被 Spring
 * 转成 {@code MethodArgumentTypeMismatchException}，语义上把"取值不在白名单"
 * 混同为"参数格式错误"（同 MKT-04 的处理）。
 */
@RestController
@RequestMapping("/api/v1/securities")
public class SecurityController {

    private final SecurityQueryService securityQueryService;
    private final SecurityDetailQueryService securityDetailQueryService;
    private final Clock clock;

    public SecurityController(
            SecurityQueryService securityQueryService,
            SecurityDetailQueryService securityDetailQueryService,
            Clock clock) {
        this.securityQueryService = securityQueryService;
        this.securityDetailQueryService = securityDetailQueryService;
        this.clock = clock;
    }

    /** STK-01：搜索建议。 */
    @GetMapping("/search")
    public ApiResponse<SecuritySearchResult> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String types,
            @RequestParam(required = false) String exchangeCodes,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        return ApiResponse.success(
                securityQueryService.search(q, types, exchangeCodes, limit),
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock));
    }

    /** STK-02：证券主数据列表。 */
    @GetMapping
    public ApiResponse<PageData<SecuritySummary>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String securityType,
            @RequestParam(required = false) String exchangeCode,
            @RequestParam(required = false) String boardCode,
            @RequestParam(required = false) String listingStatus,
            @RequestParam(required = false) String sectorId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            HttpServletRequest request) {
        SecurityListCriteria criteria = new SecurityListCriteria(
                keyword, securityType, exchangeCode, boardCode,
                listingStatus, sectorId, page, size, sort);
        return ApiResponse.success(
                securityQueryService.list(criteria),
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock));
    }

    /**
     * STK-04：个股行情快照。
     *
     * <p>{@code securityId} 是系统稳定主键，不用 {@code securityCode} 代替——
     * 不同交易所可能存在相同代码空间。
     */
    @GetMapping("/{securityId}/quote")
    public ApiResponse<QuoteSnapshot> quote(
            @PathVariable String securityId,
            HttpServletRequest request) {
        return ApiResponse.success(
                securityDetailQueryService.getQuote(securityId),
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock));
    }

    /** STK-07：日 / 周 / 月 K 线。 */
    @GetMapping("/{securityId}/klines")
    public ApiResponse<KlineSeries> klines(
            @PathVariable String securityId,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String adjustment,
            HttpServletRequest request) {
        return ApiResponse.success(
                securityDetailQueryService.getKlines(
                        securityId, period, startDate, endDate, adjustment),
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock));
    }
}
