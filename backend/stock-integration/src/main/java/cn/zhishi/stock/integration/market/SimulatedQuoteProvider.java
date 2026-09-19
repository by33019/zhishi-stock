package cn.zhishi.stock.integration.market;

import cn.zhishi.stock.market.domain.BreadthCalculator;
import cn.zhishi.stock.market.domain.LimitRuleProvider;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketOverview.BreadthData;
import cn.zhishi.stock.market.domain.MarketOverview.DataStatus;
import cn.zhishi.stock.market.domain.MarketOverview.MarketIndex;
import cn.zhishi.stock.market.domain.MarketOverview.NewsItem;
import cn.zhishi.stock.market.domain.MarketOverview.QuoteRow;
import cn.zhishi.stock.market.domain.MarketOverview.Region;
import cn.zhishi.stock.market.domain.MarketOverview.SectorQuote;
import cn.zhishi.stock.market.domain.MarketOverview.TurnoverData;
import cn.zhishi.stock.market.domain.MarketSessionStatus;
import cn.zhishi.stock.market.domain.QuoteProvider;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.QuoteSnapshotBatchProvider;
import cn.zhishi.stock.market.domain.RankingType;
import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecurityQuoteProvider;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SimulatedQuoteProvider implements QuoteProvider {

    private static final DateTimeFormatter VERSION_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    private static final String MARKET_CODE = "CN";

    /** 行情热榜预览的行数。 */
    private static final int RANKING_PREVIEW_SIZE = 3;

    private final Clock clock;
    private final Scenario scenario;
    private final LimitRuleProvider limitRuleProvider;
    private final SecurityQuoteProvider securityQuoteProvider;
    private final QuoteSnapshotBatchProvider quoteSnapshotBatchProvider;

    /**
     * 独立使用（定时任务、单测）时的便捷构造：自建一份确定性的整批快照源。
     *
     * <p>生产环境由配置层注入**同一份**整批快照源，使总览的榜单预览与 QTE-01 榜单同源。
     * 自建版本使用空节假日表，与配置层注入的实例可能对"最近交易日"给出不同答案，
     * 因此只适用于不关心交易日历差异的场景。
     */
    public SimulatedQuoteProvider(Clock clock, Scenario scenario) {
        this(clock, scenario, new SimulatedLimitRuleProvider());
    }

    private SimulatedQuoteProvider(
            Clock clock, Scenario scenario, LimitRuleProvider limitRuleProvider) {
        this(clock, scenario, limitRuleProvider, defaultBatchProvider(clock, limitRuleProvider));
    }

    public SimulatedQuoteProvider(
            Clock clock,
            Scenario scenario,
            LimitRuleProvider limitRuleProvider,
            QuoteSnapshotBatchProvider quoteSnapshotBatchProvider) {
        this.clock = clock;
        this.scenario = scenario;
        this.limitRuleProvider = limitRuleProvider;
        this.securityQuoteProvider = new SimulatedSecurityQuoteProvider(limitRuleProvider);
        this.quoteSnapshotBatchProvider = quoteSnapshotBatchProvider;
    }

    private static QuoteSnapshotBatchProvider defaultBatchProvider(
            Clock clock, LimitRuleProvider limitRuleProvider) {
        SecurityQuoteProvider quotes = new SimulatedSecurityQuoteProvider(limitRuleProvider);
        TradingCalendarProvider calendar = SimulatedTradingCalendarProvider.ofCsv(clock, "");
        SecurityMasterProvider master =
                new SimulatedSecurityMasterProvider(quotes, calendar, clock);
        return new SimulatedQuoteSnapshotProvider(
                quotes, master, limitRuleProvider, calendar, clock);
    }

    @Override
    public MarketOverview fetch(String marketCode) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate tradeDate = now.toLocalDate();
        DataStatus overallStatus = switch (scenario) {
            case NORMAL, CLOSED -> DataStatus.REALTIME;
            case DELAYED, PARTIAL -> DataStatus.DELAYED;
        };
        MarketSessionStatus sessionStatus = scenario == Scenario.CLOSED
                ? MarketSessionStatus.CLOSED
                : MarketSessionStatus.TRADING;
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
                tradeDate,
                scenario == Scenario.DELAYED ? now.minusMinutes(8) : now,
                overallStatus,
                indices(),
                breadth(tradeDate),
                new TurnoverData(
                        "982645000000",
                        "916218000000",
                        List.of(538.2, 1028.6, 1886.4, 2945.1, 4380.8, 6126.2, 7982.3, 9826.45)),
                scenario == Scenario.PARTIAL ? List.of() : sectors(),
                rankings(quoteSnapshotBatchProvider.fetchBatch(marketCode)),
                news(now),
                componentStatus,
                now,
                "sim-" + marketCode + "-" + VERSION_TIME.format(now) + "-"
                        + scenario.name().toLowerCase(Locale.ROOT));
    }

    /**
     * 广度不再是写死的数字，而是对整批个股行情按限幅规则计数得到的结果。
     *
     * <p>这带来一个可验证的性质：把快照里的广度与同批个股行情重新计一遍，结果必须一致。
     */
    private BreadthData breadth(LocalDate tradeDate) {
        return BreadthCalculator.calculate(
                securityQuoteProvider.fetchUniverse(MARKET_CODE, tradeDate),
                limitRuleProvider.rules(MARKET_CODE, tradeDate),
                tradeDate);
    }

    /**
     * 行情热榜预览：涨幅榜前 3 名。
     *
     * <p>**投影自与 QTE-01 榜单同一批快照**，不另起一套生成逻辑。此前这里是三个写死的常量，
     * {@code securityId} 用的是主数据里不存在的 {@code stock-600519}，导致首页点击个股跳 404，
     * 且预览值与榜单页对不上。投影之后两处必然一致，不需要靠约定维持。
     *
     * <p>筛选条件与榜单接口的默认值保持一致：排除停牌、不排除 ST。
     *
     * <p>{@code sparkline} 只给「开盘 → 最新价」两个**真实**点位：模拟源没有分钟数据，
     * 编一条假的日内路径不如给一条真实的当日方向线。
     */
    private static List<QuoteRow> rankings(List<QuoteSnapshot> batch) {
        return batch.stream()
                .filter(RankingType.GAINERS::hasSortKey)
                .filter(snapshot -> !snapshot.security().isSuspended())
                .sorted(RankingType.GAINERS.order())
                .limit(RANKING_PREVIEW_SIZE)
                .map(SimulatedQuoteProvider::toQuoteRow)
                .toList();
    }

    private static QuoteRow toQuoteRow(QuoteSnapshot snapshot) {
        return new QuoteRow(
                snapshot.security().securityId(),
                snapshot.security().securityCode(),
                snapshot.security().securityName(),
                snapshot.security().exchangeCode(),
                snapshot.latestPrice(),
                snapshot.changeAmount(),
                snapshot.changeRate(),
                snapshot.tradeVolume(),
                snapshot.tradeAmount(),
                snapshot.turnoverRate(),
                directionLine(snapshot));
    }

    /** 开盘 → 最新价；任一缺失时返回空数组，不伪造点位。 */
    private static List<Double> directionLine(QuoteSnapshot snapshot) {
        BigDecimal open = decimalOrNull(snapshot.openPrice());
        BigDecimal latest = decimalOrNull(snapshot.latestPrice());
        if (open == null || latest == null) {
            return List.of();
        }
        return List.of(open.doubleValue(), latest.doubleValue());
    }

    private static BigDecimal decimalOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
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
