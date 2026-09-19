package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.market.application.MarketBreadthQueryService;
import cn.zhishi.stock.market.application.MarketOverviewQueryService;
import cn.zhishi.stock.market.application.MarketStatusQueryService;
import cn.zhishi.stock.market.application.TurnoverTrendQueryService;
import cn.zhishi.stock.market.domain.MarketBreadth;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketStatus;
import cn.zhishi.stock.market.domain.TurnoverTrend;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/markets")
public class MarketController {

    private final MarketOverviewQueryService overviewService;
    private final MarketStatusQueryService statusService;
    private final MarketBreadthQueryService breadthService;
    private final TurnoverTrendQueryService turnoverTrendService;
    private final Clock clock;

    public MarketController(
            MarketOverviewQueryService overviewService,
            MarketStatusQueryService statusService,
            MarketBreadthQueryService breadthService,
            TurnoverTrendQueryService turnoverTrendService,
            Clock clock) {
        this.overviewService = overviewService;
        this.statusService = statusService;
        this.breadthService = breadthService;
        this.turnoverTrendService = turnoverTrendService;
        this.clock = clock;
    }

    @GetMapping("/overview")
    public ApiResponse<MarketOverview> overview(
            @RequestParam(defaultValue = "CN") String market,
            HttpServletRequest request) {
        return ApiResponse.success(
                overviewService.getOverview(market),
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock));
    }

    @GetMapping("/{marketCode}/status")
    public ApiResponse<MarketStatus> status(
            @PathVariable String marketCode,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            HttpServletRequest request) {
        return ApiResponse.success(
                statusService.getStatus(marketCode, date),
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock));
    }

    /** MKT-03：同一快照口径的市场广度，可选 {@code snapshotTime} 做时间回溯。 */
    @GetMapping("/{marketCode}/breadth")
    public ApiResponse<MarketBreadth> breadth(
            @PathVariable String marketCode,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime snapshotTime,
            HttpServletRequest request) {
        return ApiResponse.success(
                breadthService.getBreadth(marketCode, snapshotTime),
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock));
    }

    /**
     * MKT-04：市场成交量额趋势。
     *
     * <p>{@code range} 与 {@code interval} 刻意声明为 {@code String} 而非枚举：
     * 非法取值必须返回业务码 {@code INVALID_REQUEST}，而枚举绑定失败会被 Spring
     * 转成 {@code MethodArgumentTypeMismatchException}，语义上把"取值不在白名单"
     * 混同为"参数格式错误"。校验由用例层承担。
     */
    @GetMapping("/{marketCode}/turnover-trend")
    public ApiResponse<TurnoverTrend> turnoverTrend(
            @PathVariable String marketCode,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String interval,
            HttpServletRequest request) {
        return ApiResponse.success(
                turnoverTrendService.getTrend(marketCode, range, interval),
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock));
    }
}
