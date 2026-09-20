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
import cn.zhishi.stock.market.domain.TradingCalendarDay;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import cn.zhishi.stock.market.domain.TradingSession;
import cn.zhishi.stock.market.domain.TradingSessions;
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
    private final TradingCalendarProvider tradingCalendarProvider;

    /** 独立使用（定时任务、单测）时的便捷构造：自建一份确定性的整批快照源。 */
    public SimulatedQuoteProvider(Clock clock, Scenario scenario) {
        this(clock, scenario, new SimulatedLimitRuleProvider(),
                SimulatedTradingCalendarProvider.ofCsv(clock, ""));
    }

    /**
     * 便捷构造：整批快照源由本构造**按同一个交易日历实例**自建。
     *
     * <p>之所以把日历一起收进来，是因为总览的 {@code tradeDate} 与榜单预览的
     * {@code tradeDate} 必须来自同一份日历。若两者各自持有一份（例如一份带节假日、
     * 一份不带），非交易日就会算出不同的交易日，而**不会有任何测试报错**——
     * 这与本切片修复的缺陷是同一个成因。
     */
    public SimulatedQuoteProvider(
            Clock clock, Scenario scenario, TradingCalendarProvider tradingCalendarProvider) {
        this(clock, scenario, new SimulatedLimitRuleProvider(), tradingCalendarProvider);
    }

    private SimulatedQuoteProvider(
            Clock clock,
            Scenario scenario,
            LimitRuleProvider limitRuleProvider,
            TradingCalendarProvider tradingCalendarProvider) {
        this(clock, scenario, limitRuleProvider, tradingCalendarProvider,
                defaultBatchProvider(clock, limitRuleProvider, tradingCalendarProvider));
    }

    /** 生产装配用的完整构造：整批快照源与日历都由配置层注入同一实例。 */
    public SimulatedQuoteProvider(
            Clock clock,
            Scenario scenario,
            LimitRuleProvider limitRuleProvider,
            TradingCalendarProvider tradingCalendarProvider,
            QuoteSnapshotBatchProvider quoteSnapshotBatchProvider) {
        this.clock = clock;
        this.scenario = scenario;
        this.limitRuleProvider = limitRuleProvider;
        this.securityQuoteProvider = new SimulatedSecurityQuoteProvider(limitRuleProvider);
        this.quoteSnapshotBatchProvider = quoteSnapshotBatchProvider;
        this.tradingCalendarProvider = tradingCalendarProvider;
    }

    private static QuoteSnapshotBatchProvider defaultBatchProvider(
            Clock clock, LimitRuleProvider limitRuleProvider, TradingCalendarProvider calendar) {
        SecurityQuoteProvider quotes = new SimulatedSecurityQuoteProvider(limitRuleProvider);
        SecurityMasterProvider master =
                new SimulatedSecurityMasterProvider(quotes, calendar, clock);
        return new SimulatedQuoteSnapshotProvider(
                quotes, master, limitRuleProvider, calendar, clock);
    }

    @Override
    public MarketOverview fetch(String marketCode) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        // 口径与个股/整批快照、MKT-02 查询共用同一份规则（TradingSessions）：
        // 非交易日回退到最近有效收盘，并标 CLOSED（契约 §3.6 后）。
        // 修复前这里直接取 now.toLocalDate()，于是周日的快照里 tradeDate 是周日、
        // marketStatus 是 TRADING，而同一份快照的榜单预览却来自周五的批次。
        TradingCalendarDay day = tradingCalendarProvider.find(marketCode, today).orElse(null);
        LocalDate tradeDate =
                TradingSessions.latestTradeDate(tradingCalendarProvider, marketCode, today);
        DataStatus overallStatus = switch (scenario) {
            case NORMAL, CLOSED -> DataStatus.REALTIME;
            case DELAYED, PARTIAL -> DataStatus.DELAYED;
        };
        // Scenario.CLOSED 保留为演示/测试用的强制覆盖；它不再是"收盘"的唯一来源，
        // 非交易日与盘后由日历自然会得到 CLOSED。
        MarketSessionStatus sessionStatus = scenario == Scenario.CLOSED
                ? MarketSessionStatus.CLOSED
                : sessionOf(day, today, now).status();
        OffsetDateTime dataTime = dataTime(marketCode, day, tradeDate, now);
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
                dataTime,
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
     *
     * <p>{@code tradeDate} 由调用方传入且与榜单预览同源——修复前这里用的是"今天"，
     * 于是非交易日会出现"广度按周日算、榜单按周五算"的自相矛盾。
     */
    private BreadthData breadth(LocalDate tradeDate) {
        return BreadthCalculator.calculate(
                securityQuoteProvider.fetchUniverse(MARKET_CODE, tradeDate),
                limitRuleProvider.rules(MARKET_CODE, tradeDate),
                tradeDate);
    }

    /**
     * 快照的数据截止时刻。
     *
     * <p>契约 §3.6 定义它是「该行情本身对应的时间」，因此：
     * <ul>
     *   <li>此刻落在该交易日的某个时段窗口内 → {@code now}，数据正在产生；</li>
     *   <li>其余情况（收盘后、非交易日）→ 该交易日的**收盘时刻**。
     *       周日 15:20 生成的快照里装的是周五的行情，写成周日 15:20 是假的。</li>
     * </ul>
     *
     * <p>判据取"有没有窗口覆盖此刻"而不是比较日期，是因为交易日 20:00 时
     * {@code tradeDate == today} 成立、但市场 15:00 就已收盘。
     */
    private OffsetDateTime dataTime(
            String marketCode, TradingCalendarDay day, LocalDate tradeDate, OffsetDateTime now) {
        OffsetDateTime base = day != null && day.sessionAt(now.toLocalTime()).isPresent()
                ? now
                : SimulatedSessionTimes.sessionEndAt(tradingCalendarProvider, marketCode, tradeDate)
                        .orElse(now);
        // 延迟场景在真实数据时间上再后退 8 分钟，而不是拿 now 后退——
        // 否则非交易日的"延迟"会把数据时间推到根本没有行情的日期上。
        return scenario == Scenario.DELAYED ? base.minusMinutes(8) : base;
    }

    /** 日历查不到该市场时按 CLOSED 兜底，与 MKT-02 的既有口径一致。 */
    private static TradingSession sessionOf(
            TradingCalendarDay day, LocalDate today, OffsetDateTime now) {
        return day == null
                ? TradingSession.CLOSED
                : TradingSessions.currentSession(day, today, now.toLocalTime());
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
