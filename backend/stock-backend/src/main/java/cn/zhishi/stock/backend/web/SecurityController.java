package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.market.application.SecurityListCriteria;
import cn.zhishi.stock.market.application.SecurityQueryService;
import cn.zhishi.stock.market.domain.SecuritySearchResult;
import cn.zhishi.stock.market.domain.SecuritySummary;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 证券主数据接口（STK-01 / STK-02）。
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
    private final Clock clock;

    public SecurityController(SecurityQueryService securityQueryService, Clock clock) {
        this.securityQueryService = securityQueryService;
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
}
