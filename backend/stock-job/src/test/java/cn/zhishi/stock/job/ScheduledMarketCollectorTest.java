package cn.zhishi.stock.job;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import cn.zhishi.stock.market.application.MarketIngestionService;
import org.junit.jupiter.api.Test;

class ScheduledMarketCollectorTest {

    @Test
    void triggersTheSameMarketIngestionUseCase() {
        MarketIngestionService ingestion = mock(MarketIngestionService.class);

        new ScheduledMarketCollector(ingestion).collect();

        verify(ingestion).collect("CN");
    }
}
