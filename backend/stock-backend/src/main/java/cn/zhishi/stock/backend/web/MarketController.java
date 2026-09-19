package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.market.application.MarketOverviewQueryService;
import cn.zhishi.stock.market.application.MarketStatusQueryService;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketStatus;
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
    private final Clock clock;

    public MarketController(
            MarketOverviewQueryService overviewService,
            MarketStatusQueryService statusService,
            Clock clock) {
        this.overviewService = overviewService;
        this.statusService = statusService;
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
}
