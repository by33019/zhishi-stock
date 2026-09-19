package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.market.application.RankingCriteria;
import cn.zhishi.stock.market.application.StockRankingQueryService;
import cn.zhishi.stock.market.domain.StockRanking;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 榜单接口（QTE-01）。
 *
 * <p>参数一律声明为 {@code String} / {@code Integer} / {@code Boolean} 并在用例层校验：
 * 非法取值必须返回业务码 {@code INVALID_REQUEST}，而枚举绑定失败会被 Spring 转成
 * {@code MethodArgumentTypeMismatchException}，语义上把"取值不在白名单"混同为"参数格式错误"
 * （同 STK-01 / STK-02 / MKT-04 的处理）。
 */
@RestController
@RequestMapping("/api/v1/stock-rankings")
public class RankingController {

    private final StockRankingQueryService stockRankingQueryService;
    private final Clock clock;

    public RankingController(StockRankingQueryService stockRankingQueryService, Clock clock) {
        this.stockRankingQueryService = stockRankingQueryService;
        this.clock = clock;
    }

    /** QTE-01：涨幅榜 / 跌幅榜 / 成交额榜。 */
    @GetMapping
    public ApiResponse<StockRanking> rankings(
            @RequestParam(required = false) String rankingType,
            @RequestParam(required = false) String exchangeCodes,
            @RequestParam(required = false) String boardCodes,
            @RequestParam(required = false) String sectorId,
            @RequestParam(required = false) Boolean excludeSt,
            @RequestParam(required = false) Boolean excludeSuspended,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        RankingCriteria criteria = new RankingCriteria(
                rankingType, exchangeCodes, boardCodes, sectorId,
                excludeSt, excludeSuspended, page, size);
        return ApiResponse.success(
                stockRankingQueryService.rank(criteria),
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock));
    }
}
