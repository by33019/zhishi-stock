package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.market.application.MarketOverviewQueryService;
import cn.zhishi.stock.market.domain.MarketOverview;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/markets")
public class MarketController {

    private final MarketOverviewQueryService service;
    private final Clock clock;

    public MarketController(MarketOverviewQueryService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @GetMapping("/overview")
    public ApiResponse<MarketOverview> overview(
            @RequestParam(defaultValue = "CN") String market,
            HttpServletRequest request) {
        return ApiResponse.success(
                service.getOverview(market),
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock));
    }
}
