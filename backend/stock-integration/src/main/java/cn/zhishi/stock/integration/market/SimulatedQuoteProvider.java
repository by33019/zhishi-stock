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
import cn.zhishi.stock.market.domain.MarketOverview.TurnoverData;
import cn.zhishi.stock.market.domain.MarketSessionStatus;
import cn.zhishi.stock.market.domain.QuoteBatch;
import cn.zhishi.stock.market.domain.QuoteProvider;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.QuoteSnapshotBatchProvider;
import cn.zhishi.stock.market.domain.RankingType;
import cn.zhishi.stock.market.domain.Sector;
import cn.zhishi.stock.market.domain.SectorMember;
import cn.zhishi.stock.market.domain.SectorProvider;
import cn.zhishi.stock.market.domain.SectorQuote;
import cn.zhishi.stock.market.domain.SectorQuoteCalculator;
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

    /** 热点板块预览的行数。 */
    private static final int SECTOR_PREVIEW_SIZE = 3;

    private final Clock clock;
    private final Scenario scenario;
    private final LimitRuleProvider limitRuleProvider;
    private final SecurityQuoteProvider securityQuoteProvider;
    private final QuoteSnapshotBatchProvider quoteSnapshotBatchProvider;
    private final TradingCalendarProvider tradingCalendarProvider;
    private final SectorProvider sectorProvider;

    /** 独立使用（定时任务、单测）时的便捷构造：自建一份确定性的整批快照源。 */
    public SimulatedQuoteProvider(Clock clock, Scenario scenario) {
        this(clock, scenario, new SimulatedLimitRuleProvider(),
                SimulatedTradingCalendarProvider.ofCsv(clock, ""));
    }

    /**
     * 便捷构造：整批快照源与板块源都由本构造**按同一个交易日历实例**自建。
     *
     * <p>之所以把日历一起收进来，是因为总览的 {@code tradeDate}、榜单预览与板块预览
     * 必须来自同一份日历。若各自持有一份（例如一份带节假日、一份不带），非交易日就会算出
     * 不同的交易日，而**不会有任何测试报错**——这与本切片修复的缺陷是同一个成因。
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
                defaultSources(clock, limitRuleProvider, tradingCalendarProvider));
    }

    /**
     * 生产装配用的完整构造：整批快照源、日历与板块源都由配置层注入同一批实例。
     *
     * <p>{@code sectorProvider} 必须与 SEC-02 / SEC-03 用的是**同一个**板块源，
     * 否则总览的板块预览会给出别处解析不了的 {@code sectorId}（首页卡片 404）。
     */
    public SimulatedQuoteProvider(
            Clock clock,
            Scenario scenario,
            LimitRuleProvider limitRuleProvider,
            TradingCalendarProvider tradingCalendarProvider,
            QuoteSnapshotBatchProvider quoteSnapshotBatchProvider,
            SectorProvider sectorProvider) {
        this.clock = clock;
        this.scenario = scenario;
        this.limitRuleProvider = limitRuleProvider;
        this.securityQuoteProvider = new SimulatedSecurityQuoteProvider(limitRuleProvider);
        this.quoteSnapshotBatchProvider = quoteSnapshotBatchProvider;
        this.tradingCalendarProvider = tradingCalendarProvider;
        this.sectorProvider = sectorProvider;
    }

    /** 便捷构造用的数据源集合，使两个自建源能共用同一份证券主数据。 */
    private record SimulatedSources(
            QuoteSnapshotBatchProvider batch, SectorProvider sectors) {
    }

    /**
     * 自建一套确定性的整批快照源与板块源。
     *
     * <p>两者**共用同一个** {@link SecurityMasterProvider} 实例。板块成分是投影自证券主数据的，
     * 两份主数据实例虽然逐位相同，但共用一份可以让"板块成分指向的证券"与
     * "整批快照里的证券"在构造上就是同一个全集——若各自一份而将来某一方换了日历，
     * 成分与快照的连接会静默失配，板块预览会变成空列表而不报错。
     */
    private static SimulatedSources defaultSources(
            Clock clock, LimitRuleProvider limitRuleProvider, TradingCalendarProvider calendar) {
        SecurityQuoteProvider quotes = new SimulatedSecurityQuoteProvider(limitRuleProvider);
        SecurityMasterProvider master =
                new SimulatedSecurityMasterProvider(quotes, calendar, clock);
        return new SimulatedSources(
                new SimulatedQuoteSnapshotProvider(quotes, master, limitRuleProvider, calendar, clock),
                new SimulatedSectorProvider(master));
    }

    private SimulatedQuoteProvider(
            Clock clock,
            Scenario scenario,
            LimitRuleProvider limitRuleProvider,
            TradingCalendarProvider tradingCalendarProvider,
            SimulatedSources sources) {
        this(clock, scenario, limitRuleProvider, tradingCalendarProvider,
                sources.batch(), sources.sectors());
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

        // 整批快照只取一次：榜单预览与板块预览必须是**同一批**，
        // 否则两个预览段会各自持有一个"合法"的版本号。
        QuoteBatch batch = QuoteBatch.of(quoteSnapshotBatchProvider.fetchBatch(marketCode));

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
                scenario == Scenario.PARTIAL ? List.of() : sectors(batch),
                rankings(batch.snapshots()),
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

    /**
     * 热点板块预览：涨幅榜前 3 名。
     *
     * <p>**与 SEC-02 板块排行走同一条取数路径**：同一个 {@link SectorProvider}、同一个
     * {@link QuoteBatch}、同一个 {@link SectorQuoteCalculator}、同一个 {@link RankingType#GAINERS}
     * 排序口径，差别只在取前 3 行而不是分页。
     *
     * <p>此前这里是三个写死的常量，{@code sectorId} 用的是板块源里不存在的
     * {@code bk-ai} / {@code bk-chip} / {@code bk-broker}，而总览页把它们当作主键跳转
     * {@code /sectors/{sectorId}}，于是**首页三张卡片点进去全部 404**；
     * 三个数字也与「板块分析」页对不上。投影之后两处必然一致，不需要靠约定维持。
     *
     * <p>{@code dataTime} / {@code dataStatus} 沿用整批快照的口径（而非总览自身的
     * {@code dataTime}），因为这样本路径与 SEC-03 的 {@code statistics(sector)} 逐字相同。
     * 这两个值不会外泄——预览段的契约里没有它们。
     */
    private List<MarketOverview.SectorQuote> sectors(QuoteBatch batch) {
        Map<String, List<SectorMember>> memberships = sectorProvider.memberships(MARKET_CODE, null);
        return sectorProvider.findAll(MARKET_CODE).stream()
                // 停用板块不进当前排行（契约 §10 SEC-03 说明），与 SEC-02 一致
                .filter(Sector::active)
                .map(sector -> SectorQuoteCalculator.calculate(
                        sector,
                        batch.ofMembers(memberships.getOrDefault(sector.sectorId(), List.of())),
                        batch.dataTime(),
                        batch.dataStatus()))
                // 无有效排序键的板块不进预览：先过滤再排序，比较器因此不必处理 null
                .filter(RankingType.GAINERS::hasSortKey)
                .sorted(RankingType.GAINERS.sectorOrder())
                .limit(SECTOR_PREVIEW_SIZE)
                .map(SimulatedQuoteProvider::toSectorQuote)
                .toList();
    }

    /**
     * 13 字段的板块统计 → 总览预览段的 7 字段。
     *
     * <p>两个记录同名但字段集不同，且**刻意不合并**：{@link SectorQuote} 服务
     * SEC-02 / SEC-03 / SEC-04 / SEC-06，而预览段在契约里就没有 {@code sectorType} /
     * {@code averagePrice} / {@code tradeVolume} / {@code laggingStock}。
     * 本文件因此不导入 {@code MarketOverview.SectorQuote}、改写成限定名——
     * 两个同名记录同时出现在一个文件里，显式限定比隐式导入更不容易读错。
     *
     * <p>{@code leadingStock} 在预览段只有名称、没有可跳转的 {@code securityId}
     * （前端也不提供跳转），因此取名称；领涨股缺失时给 {@code null}，不编造。
     */
    private static MarketOverview.SectorQuote toSectorQuote(SectorQuote quote) {
        return new MarketOverview.SectorQuote(
                quote.sectorId(),
                quote.sectorCode(),
                quote.sectorName(),
                quote.changeRate(),
                quote.tradeAmount(),
                quote.leadingStock() == null
                        ? null
                        : quote.leadingStock().security().securityName(),
                quote.companyCount());
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
