package cn.zhishi.stock.market.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    /**
     * 市场广度四态与涨跌停计数。
     *
     * <p>四态（涨 / 跌 / 平 / 停牌）互斥且完备，{@code totalCount} 由四态相加派生。
     * {@code limitUpCount} 是 {@code riseCount} 的子集，{@code limitDownCount} 是 {@code fallCount} 的子集。
     *
     * <p>{@code totalCount} 刻意<b>不</b>参与 JSON 序列化：快照会被持久化并在读取时反序列化，
     * 派生字段一旦落盘就会在归档里形成一个可能与四态不一致的第二真相。
     */
    public record BreadthData(
            int riseCount,
            int fallCount,
            int flatCount,
            int suspendedCount,
            int limitUpCount,
            int limitDownCount) {

        @JsonIgnore
        public int totalCount() {
            return riseCount + fallCount + flatCount + suspendedCount;
        }
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
