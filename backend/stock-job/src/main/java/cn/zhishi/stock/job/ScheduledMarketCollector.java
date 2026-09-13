package cn.zhishi.stock.job;

import cn.zhishi.stock.market.application.MarketIngestionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduledMarketCollector {

    private final MarketIngestionService ingestion;

    public ScheduledMarketCollector(MarketIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @Scheduled(
            initialDelayString = "${stock.market.collect-initial-delay-ms:1000}",
            fixedDelayString = "${stock.market.collect-delay-ms:60000}")
    public void collect() {
        ingestion.collect("CN");
    }
}
