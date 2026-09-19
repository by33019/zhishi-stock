package cn.zhishi.stock.market.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public record MarketOverview(
        String marketCode,
        MarketSessionStatus marketStatus,
        LocalDate tradeDate,
        OffsetDateTime dataTime,
        DataStatus dataStatus,
        List<MarketIndex> indices,
        BreadthData breadth,
        TurnoverData turnover,
        List<SectorQuote> sectors,
        List<QuoteRow> rankings,
        List<NewsItem> news,
        Map<String, DataStatus> componentStatus,
        OffsetDateTime lastSuccessfulSyncAt,
        String snapshotVersion) {

    public enum DataStatus {
        REALTIME,
        DELAYED,
        STALE,
        UNAVAILABLE
    }

    public MarketOverview asStale() {
        Map<String, DataStatus> staleComponents = new LinkedHashMap<>();
        componentStatus.forEach((component, status) -> staleComponents.put(
                component,
                status == DataStatus.UNAVAILABLE ? DataStatus.UNAVAILABLE : DataStatus.STALE));
        return new MarketOverview(
                marketCode,
                marketStatus,
                tradeDate,
                dataTime,
                DataStatus.STALE,
                indices,
                breadth,
                turnover,
                sectors,
                rankings,
                news,
                staleComponents,
                lastSuccessfulSyncAt,
                snapshotVersion);
    }

    public record MarketIndex(
            String indexId,
            String indexCode,
            String indexName,
            String latestPoint,
            String changeAmount,
            String changeRate,
            Region region,
            List<Double> sparkline) {
    }

    public enum Region {
        DOMESTIC,
        OVERSEAS
    }

    public record BreadthData(
            int riseCount,
            int fallCount,
            int flatCount,
            int limitUpCount,
            int limitDownCount) {
    }

    public record TurnoverData(String amount, String previousAmount, List<Double> points) {
    }

    public record SectorQuote(
            String sectorId,
            String sectorCode,
            String sectorName,
            String changeRate,
            String tradeAmount,
            String leadingStock,
            int companyCount) {
    }

    public record QuoteRow(
            String securityId,
            String securityCode,
            String securityName,
            String exchangeCode,
            String latestPrice,
            String changeAmount,
            String changeRate,
            String tradeVolume,
            String tradeAmount,
            String turnoverRate,
            List<Double> sparkline) {
    }

    public record NewsItem(
            String newsId,
            String newsType,
            String title,
            String summary,
            String sourceName,
            OffsetDateTime publishedAt,
            List<String> relatedSymbols) {
    }
}
