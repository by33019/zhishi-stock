package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.market.application.ConstituentCriteria;
import cn.zhishi.stock.market.application.SectorCriteria;
import cn.zhishi.stock.market.application.SectorQueryService;
import cn.zhishi.stock.market.application.SectorRankingCriteria;
import cn.zhishi.stock.market.application.SectorRankingQueryService;
import cn.zhishi.stock.market.domain.SectorConstituent;
import cn.zhishi.stock.market.domain.SectorDetail;
import cn.zhishi.stock.market.domain.SectorList;
import cn.zhishi.stock.market.domain.SectorQuote;
import cn.zhishi.stock.market.domain.SectorRanking;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 板块接口（{@code RESTful-API.md} §10 SEC-01 / SEC-02 / SEC-03 / SEC-04 / SEC-06）。
 *
 * <p>参数一律声明为 {@code String} / {@code Integer} 并在用例层校验：非法取值必须返回业务码
 * {@code INVALID_REQUEST}，而枚举绑定失败会被 Spring 转成
 * {@code MethodArgumentTypeMismatchException}，语义上把"取值不在白名单"混同为"参数格式错误"
 * （同 STK-01 / STK-02 / QTE-01 / MKT-04 的处理）。
 *
 * <p>五个端点分属两条路径前缀（{@code /sectors} 与 {@code /sector-rankings}），
 * 因此类级只声明公共前缀 {@code /api/v1}，完整路径写在每个方法上——
 * 用类级 {@code @RequestMapping("/api/v1/sectors")} 会迫使排行端点写一个别扭的相对路径。
 *
 * <p>{@code {sectorId}} 不做格式校验：模拟源用 {@code sim-bk0006}，真实源可能是纯数字，
 * 在这里钉格式会把"这个 ID 不存在"错报成"这个 ID 不合法"。
 */
@RestController
@RequestMapping("/api/v1")
public class SectorController {

    private final SectorQueryService sectorQueryService;
    private final SectorRankingQueryService sectorRankingQueryService;
    private final Clock clock;

    public SectorController(
            SectorQueryService sectorQueryService,
            SectorRankingQueryService sectorRankingQueryService,
            Clock clock) {
        this.sectorQueryService = sectorQueryService;
        this.sectorRankingQueryService = sectorRankingQueryService;
        this.clock = clock;
    }

    /** SEC-01：板块主数据列表。 */
    @GetMapping("/sectors")
    public ApiResponse<SectorList> sectors(
            @RequestParam(required = false) String sectorType,
            @RequestParam(required = false) String parentId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            HttpServletRequest request) {
        return ok(sectorQueryService.list(
                new SectorCriteria(sectorType, parentId, keyword, status)), request);
    }

    /** SEC-02：板块排行。 */
    @GetMapping("/sector-rankings")
    public ApiResponse<SectorRanking> rankings(
            @RequestParam(required = false) String sectorType,
            @RequestParam(required = false) String rankingType,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        return ok(sectorRankingQueryService.rank(
                new SectorRankingCriteria(sectorType, rankingType, page, size)), request);
    }

    /** SEC-03：板块详情。停用板块仍返回 200（契约：「可返回历史状态」）。 */
    @GetMapping("/sectors/{sectorId}")
    public ApiResponse<SectorDetail> detail(
            @PathVariable String sectorId, HttpServletRequest request) {
        return ok(sectorQueryService.detail(sectorId), request);
    }

    /** SEC-04：板块最新行情统计。 */
    @GetMapping("/sectors/{sectorId}/quote")
    public ApiResponse<SectorQuote> quote(
            @PathVariable String sectorId, HttpServletRequest request) {
        return ok(sectorQueryService.quote(sectorId), request);
    }

    /** SEC-06：板块成分股。 */
    @GetMapping("/sectors/{sectorId}/constituents")
    public ApiResponse<PageData<SectorConstituent>> constituents(
            @PathVariable String sectorId,
            @RequestParam(required = false) String effectiveDate,
            @RequestParam(required = false) String rankingType,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        return ok(sectorQueryService.constituents(
                sectorId, new ConstituentCriteria(effectiveDate, rankingType, page, size)), request);
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.success(
                data, TraceIdFilter.current(request), OffsetDateTime.now(clock));
    }
}
