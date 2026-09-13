package cn.zhishi.stock.integration.market;

import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketOverview.BreadthData;
import cn.zhishi.stock.market.domain.MarketOverview.DataStatus;
import cn.zhishi.stock.market.domain.MarketOverview.MarketIndex;
import cn.zhishi.stock.market.domain.MarketOverview.NewsItem;
import cn.zhishi.stock.market.domain.MarketOverview.QuoteRow;
import cn.zhishi.stock.market.domain.MarketOverview.Region;
import cn.zhishi.stock.market.domain.MarketOverview.SectorQuote;
import cn.zhishi.stock.market.domain.MarketOverview.SessionStatus;
import cn.zhishi.stock.market.domain.MarketOverview.TurnoverData;
import cn.zhishi.stock.market.domain.QuoteProvider;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SimulatedQuoteProvider implements QuoteProvider {

    private static final DateTimeFormatter VERSION_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    private final Clock clock;
    private final Scenario scenario;

    public SimulatedQuoteProvider(Clock clock, Scenario scenario) {
        this.clock = clock;
        this.scenario = scenario;
    }

    @Override
    public MarketOverview fetch(String marketCode) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        DataStatus overallStatus = switch (scenario) {
            case NORMAL, CLOSED -> DataStatus.REALTIME;
            case DELAYED, PARTIAL -> DataStatus.DELAYED;
        };
        SessionStatus sessionStatus = scenario == Scenario.CLOSED
                ? SessionStatus.CLOSED
                : SessionStatus.TRADING;
        Map<String, DataStatus> componentStatus = new LinkedHashMap<>();
        componentStatus.put("indices", scenario == Scenario.DELAYED
                ? DataStatus.DELAYED
                : DataStatus.REALTIME);
        componentStatus.put("breadth", DataStatus.REALTIME);
        componentStatus.put("turnover", DataStatus.REALTIME);
        componentStatus.put("sectors", scenario == Scenario.PARTIAL
                ? DataStatus.UNAVAILABLE
                : DataStatus.REALTIME);
        componentStatus.put("rankings", DataStatus.REALTIME);
        componentStatus.put("news", DataStatus.REALTIME);

        return new MarketOverview(
                marketCode,
                sessionStatus,
                now.toLocalDate(),
                scenario == Scenario.DELAYED ? now.minusMinutes(8) : now,
                overallStatus,
                indices(),
                new BreadthData(2876, 1924, 164, 82, 7),
                new TurnoverData(
                        "982645000000",
                        "916218000000",
                        List.of(538.2, 1028.6, 1886.4, 2945.1, 4380.8, 6126.2, 7982.3, 9826.45)),
                scenario == Scenario.PARTIAL ? List.of() : sectors(),
                rankings(),
                news(now),
                componentStatus,
                now,
                "sim-" + marketCode + "-" + VERSION_TIME.format(now) + "-"
                        + scenario.name().toLowerCase(Locale.ROOT));
    }

    private static List<MarketIndex> indices() {
        return List.of(
                new MarketIndex("idx-sh", "000001", "上证指数", "3188.42", "18.36", "0.0058",
                        Region.DOMESTIC, List.of(3161.1, 3170.5, 3168.2, 3180.6, 3188.42)),
                new MarketIndex("idx-sz", "399001", "深证成指", "10487.31", "83.12", "0.0080",
                        Region.DOMESTIC, List.of(10392.4, 10412.8, 10430.3, 10462.2, 10487.31)),
                new MarketIndex("idx-hs300", "000300", "沪深300", "3836.94", "21.40", "0.0056",
                        Region.DOMESTIC, List.of(3808.1, 3819.5, 3822.8, 3831.2, 3836.94)),
                new MarketIndex("idx-hsi", "HSI", "恒生指数", "26388.16", "214.33", "0.0082",
                        Region.OVERSEAS, List.of(26110.2, 26188.7, 26240.1, 26302.4, 26388.16)));
    }

    private static List<SectorQuote> sectors() {
        return List.of(
                new SectorQuote("bk-ai", "BK-AI", "人工智能", "0.0342", "126800000000", "中科曙光", 68),
                new SectorQuote("bk-chip", "BK-CHIP", "半导体", "0.0286", "105400000000", "北方华创", 81),
                new SectorQuote("bk-broker", "BK-BROKER", "证券", "0.0231", "87600000000", "东方财富", 50));
    }

    private static List<QuoteRow> rankings() {
        return List.of(
                new QuoteRow("stock-600519", "600519", "贵州茅台", "SH", "1468.00", "31.32", "0.0218", "2630000", "3862000000", "0.0043", List.of(1442.0, 1451.0, 1460.0, 1468.0)),
                new QuoteRow("stock-300750", "300750", "宁德时代", "SZ", "306.82", "12.40", "0.0421", "23800000", "7215000000", "0.0186", List.of(294.4, 298.2, 302.5, 306.82)),
                new QuoteRow("stock-601318", "601318", "中国平安", "SH", "58.37", "0.78", "0.0135", "54200000", "3140000000", "0.0072", List.of(57.6, 57.9, 58.1, 58.37)));
    }

    private static List<NewsItem> news(OffsetDateTime now) {
        return List.of(new NewsItem(
                "news-sim-1",
                "NEWS",
                "模拟行情：A 股主要指数震荡上行",
                "本条内容由开发环境确定性模拟数据生成，不代表真实市场行情。",
                "知势模拟资讯",
                now.minusMinutes(12),
                List.of("SH.000001")));
    }

    public enum Scenario {
        NORMAL,
        CLOSED,
        DELAYED,
        PARTIAL
    }
}
