package cn.zhishi.stock.integration.news;

import cn.zhishi.stock.integration.market.SimulatedHashing;
import cn.zhishi.stock.market.domain.Sector;
import cn.zhishi.stock.market.domain.SectorProvider;
import cn.zhishi.stock.market.domain.SectorType;
import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecuritySummary;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import cn.zhishi.stock.market.domain.TradingSessions;
import cn.zhishi.stock.news.domain.NewsFeed;
import cn.zhishi.stock.news.domain.NewsFeedItem;
import cn.zhishi.stock.news.domain.NewsProvider;
import cn.zhishi.stock.news.domain.NewsSource;
import cn.zhishi.stock.news.domain.NewsSourceType;
import cn.zhishi.stock.news.domain.NewsType;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 确定性模拟资讯源。
 *
 * <p>与 {@code SimulatedQuoteProvider} 同层同性质：真实供应商未就位，用一份可复现的
 * 数据把链路跑通，替换实现类即可切换。
 *
 * <h2>为什么它自带一份来源清单</h2>
 * {@code news_source} 是**授权配置表**，真实环境下来源由管理员通过后台登记（ADM-NEWS-03）。
 * 模拟环境里若库里没有任何来源，采集会静默地什么都做不了、展示也永远是空的——
 * 失败没有任何提示。因此模拟实现声明自己的来源，由采集侧确保它们已登记
 * （{@link cn.zhishi.stock.news.domain.NewsSourceStore#ensureAll}，**只插入不更新**）。
 *
 * <h2>为什么刻意产出"不干净"的数据</h2>
 * 真实来源的数据是不整齐的，而单测夹具是按对契约的理解手写的。
 * 本实现刻意让每个交易日的一批数据里同时包含：
 *
 * <ul>
 *   <li>结构化提示（EXPLICIT 关联）与纯文本线索（RULE 关联）；
 *   <li>标题命中与**仅摘要**命中（后者是 CANDIDATE，低置信）；
 *   <li>**跨来源重复对**：同一内容由两家媒体发出 → 一条 ORIGINAL + 一条 DUPLICATE；
 *   <li>**同来源重复投递**：同一 {@code sourceContentId} 出现两次且内容有差异
 *       → 只能靠"来源 ID 幂等"拦住（指纹拦不住，内容不同）；
 *   <li>一条来自**未授权来源**的稿件 → 必须被授权闸门挡在库外；
 *   <li>一条不含任何关联线索的稿件 → 证明"没有关联"是合法结果，不是缺陷。
 * </ul>
 *
 * 这样"去重与授权真的生效了"是可观测的：{@code fetchedCount != insertedCount}。
 */
public class SimulatedNewsProvider implements NewsProvider {

    private static final String MARKET_CODE = "CN";

    /** 单条稿件的来源侧 ID 前缀，与槽位号组合成稳定的 {@code sourceContentId}。 */
    private static final String CONTENT_ID_PREFIX = "sim-content-";

    private static final NewsSource EXCHANGE = source(
            1, "SIM_EXCHANGE", "模拟交易所", NewsSourceType.EXCHANGE, true);
    private static final NewsSource MEDIA_A = source(
            2, "SIM_MEDIA_A", "模拟财经媒体A", NewsSourceType.MEDIA, true);
    /** 授权有效但**不允许进入 AI 上下文**：{@code allow_ai_analysis = 0}（消费侧见 M3-06）。 */
    private static final NewsSource MEDIA_B = source(
            3, "SIM_MEDIA_B", "模拟财经媒体B", NewsSourceType.MEDIA, false);
    /** 授权被暂停：它的稿件必须被挡在库外。 */
    private static final NewsSource MEDIA_SUSPENDED = new NewsSource(
            4,
            "SIM_MEDIA_C",
            "模拟财经媒体C",
            NewsSourceType.MEDIA,
            "https://example.com/media-c",
            NewsSource.AuthorizationStatus.SUSPENDED,
            null,
            null,
            true,
            NewsSource.SourceStatus.ACTIVE,
            null,
            null,
            0);
    private static final NewsSource REGULATOR = source(
            5, "SIM_REGULATOR", "模拟监管机构", NewsSourceType.REGULATOR, true);

    private static final List<NewsSource> SOURCES =
            List.of(EXCHANGE, MEDIA_A, MEDIA_B, MEDIA_SUSPENDED, REGULATOR);

    private final Clock clock;
    private final SecurityMasterProvider securityMasterProvider;
    private final SectorProvider sectorProvider;
    private final TradingCalendarProvider tradingCalendarProvider;

    public SimulatedNewsProvider(
            Clock clock,
            SecurityMasterProvider securityMasterProvider,
            SectorProvider sectorProvider,
            TradingCalendarProvider tradingCalendarProvider) {
        this.clock = clock;
        this.securityMasterProvider = securityMasterProvider;
        this.sectorProvider = sectorProvider;
        this.tradingCalendarProvider = tradingCalendarProvider;
    }

    @Override
    public List<NewsSource> sources() {
        return SOURCES;
    }

    @Override
    public NewsFeed fetch(OffsetDateTime since) {
        OffsetDateTime fetchedAt = OffsetDateTime.now(clock);
        LocalDate tradeDate = TradingSessions.latestTradeDate(
                tradingCalendarProvider, MARKET_CODE, fetchedAt.toLocalDate());
        List<SecuritySummary> securities = securityMasterProvider.findAll(MARKET_CODE);
        List<Sector> sectors = sectorProvider.findAll(MARKET_CODE);
        List<Sector> industries = sectors.stream()
                .filter(sector -> sector.active()
                        && sector.isType(SectorType.INDUSTRY)
                        && sector.levelNo() == 2)
                .toList();
        List<Sector> concepts = sectors.stream()
                .filter(sector -> sector.active() && sector.isType(SectorType.CONCEPT))
                .toList();
        // 标的池不足时返回空批次而不是造一条指向不存在标的的资讯：
        // 关联解析会把它整条丢掉，那样"为什么没有资讯"就变成了一个要读代码才能回答的问题。
        if (securities.size() < 5 || industries.isEmpty() || concepts.isEmpty()) {
            return NewsFeed.empty(fetchedAt);
        }

        // 下标由交易日派生：同一天多次采集得到同一批稿件（幂等的前提），
        // 不同交易日得到不同的标的，因此列表会随日期变化。
        int base = (int) Math.floorMod(
                SimulatedHashing.mix(tradeDate.toEpochDay()), securities.size());
        SecuritySummary first = at(securities, base);
        SecuritySummary second = at(securities, base + 1);
        SecuritySummary third = at(securities, base + 2);
        SecuritySummary fourth = at(securities, base + 3);
        SecuritySummary fifth = at(securities, base + 4);
        Sector industry = industries.get((int) Math.floorMod(
                SimulatedHashing.mix(tradeDate.toEpochDay() + 1), industries.size()));
        Sector concept = concepts.get((int) Math.floorMod(
                SimulatedHashing.mix(tradeDate.toEpochDay() + 2), concepts.size()));

        ZoneId zone = clock.getZone();
        List<NewsFeedItem> items = new ArrayList<>();

        // 槽 0：结构化提示 → EXPLICIT / 1.0 / CONFIRMED
        items.add(new NewsFeedItem(
                EXCHANGE.sourceCode(),
                contentId(0),
                NewsType.ANNOUNCEMENT,
                "【公告】" + first.securityName() + " 发布年度业绩预告",
                "公告显示，公司预计报告期内营业收入同比增长，具体数据以正式报告为准。",
                "交易所信息披露",
                url(EXCHANGE, 0),
                "zh-CN",
                publishedAt(tradeDate, 0, zone),
                List.of(first.securityCode()),
                List.of(),
                null));

        // 槽 1：标题含证券简称 → RULE / 0.8 / CONFIRMED
        items.add(new NewsFeedItem(
                MEDIA_A.sourceCode(),
                contentId(1),
                NewsType.NEWS,
                "机构调研：" + second.securityName() + " 获多家机构集中关注",
                "近一周内公司接待了多家机构调研，交流内容涉及产能与订单情况。",
                "记者甲",
                url(MEDIA_A, 1),
                "zh-CN",
                publishedAt(tradeDate, 1, zone),
                List.of(),
                List.of(),
                null));

        // 槽 2：**仅摘要**含证券简称 → RULE / 0.6 / CANDIDATE（低置信）
        items.add(new NewsFeedItem(
                MEDIA_A.sourceCode(),
                contentId(2),
                NewsType.NEWS,
                "消费板块午后异动",
                "盘面上，" + third.securityName() + " 成交明显放大，板块内个股涨跌互现。",
                "记者甲",
                url(MEDIA_A, 2),
                "zh-CN",
                publishedAt(tradeDate, 2, zone),
                List.of(),
                List.of(),
                null));

        // 槽 3：标题含行业板块名 → RULE / 0.75 / CONFIRMED
        items.add(new NewsFeedItem(
                MEDIA_A.sourceCode(),
                contentId(3),
                NewsType.NEWS,
                "行业观察：" + industry.sectorName() + " 景气度环比改善",
                "上游排产与库存数据显示需求端出现回暖迹象。",
                "记者乙",
                url(MEDIA_A, 3),
                "zh-CN",
                publishedAt(tradeDate, 3, zone),
                List.of(),
                List.of(),
                null));

        // 槽 4：**仅摘要**含概念板块名 → RULE / 0.5 / CANDIDATE
        items.add(new NewsFeedItem(
                MEDIA_B.sourceCode(),
                contentId(4),
                NewsType.NEWS,
                "今日盘面回顾",
                "资金流向显示，" + concept.sectorName() + " 板块获净流入。",
                "记者丙",
                url(MEDIA_B, 4),
                "zh-CN",
                publishedAt(tradeDate, 4, zone),
                List.of(),
                List.of(),
                null));

        // 槽 5：结构化市场 → EXPLICIT / MARKET / CONFIRMED
        items.add(new NewsFeedItem(
                REGULATOR.sourceCode(),
                contentId(5),
                NewsType.NEWS,
                "监管部门就近期市场运行情况答记者问",
                null,
                "监管发布",
                url(REGULATOR, 5),
                "zh-CN",
                publishedAt(tradeDate, 5, zone),
                List.of(),
                List.of(),
                MARKET_CODE));

        // 槽 6：与槽 1 **逐字相同**、来自另一家媒体 → DUPLICATE（内容指纹幂等）
        items.add(new NewsFeedItem(
                MEDIA_B.sourceCode(),
                contentId(6),
                NewsType.NEWS,
                "机构调研：" + second.securityName() + " 获多家机构集中关注",
                "近一周内公司接待了多家机构调研，交流内容涉及产能与订单情况。",
                "记者丙",
                url(MEDIA_B, 6),
                "zh-CN",
                publishedAt(tradeDate, 6, zone),
                List.of(),
                List.of(),
                null));

        // 槽 7：与槽 3 **同一个来源与来源侧稿件 ID**，但内容已被来源更新
        //       → 指纹不同（内容指纹拦不住），只有"来源 ID 幂等"能拦住它。
        items.add(new NewsFeedItem(
                MEDIA_A.sourceCode(),
                contentId(3),
                NewsType.NEWS,
                "行业观察：" + industry.sectorName() + " 景气度环比改善",
                "上游排产与库存数据显示需求端出现回暖迹象。（来源于盘后更新表述）",
                "记者乙",
                url(MEDIA_A, 3),
                "zh-CN",
                publishedAt(tradeDate, 7, zone),
                List.of(),
                List.of(),
                null));

        // 槽 8：不含任何关联线索 → 合法地"没有关联"
        items.add(new NewsFeedItem(
                MEDIA_A.sourceCode(),
                contentId(8),
                NewsType.OTHER,
                "本周全球市场要闻速览",
                null,
                "记者甲",
                url(MEDIA_A, 8),
                "zh-CN",
                publishedAt(tradeDate, 8, zone),
                List.of(),
                List.of(),
                null));

        // 槽 9：标题同时含两只证券简称 → 两条 RULE / 0.8 / CONFIRMED
        items.add(new NewsFeedItem(
                MEDIA_A.sourceCode(),
                contentId(9),
                NewsType.NEWS,
                "同题材联动：" + fourth.securityName() + " 与 " + fifth.securityName() + " 同日异动",
                "两只标的盘中同步走强，成交额均明显高于近期均值。",
                "记者乙",
                url(MEDIA_A, 9),
                "zh-CN",
                publishedAt(tradeDate, 9, zone),
                List.of(),
                List.of(),
                null));

        // 槽 10：来自**未授权**来源 → 授权闸门必须把它挡在库外
        items.add(new NewsFeedItem(
                MEDIA_SUSPENDED.sourceCode(),
                contentId(10),
                NewsType.NEWS,
                "某公司获得大额订单",
                "该来源授权已暂停，本条不应出现在任何查询结果里。",
                "记者丁",
                url(MEDIA_SUSPENDED, 10),
                "zh-CN",
                publishedAt(tradeDate, 10, zone),
                List.of(),
                List.of(),
                null));

        if (since != null) {
            items.removeIf(item -> !item.publishedAt().isAfter(since));
        }
        return new NewsFeed(items, fetchedAt);
    }

    private static SecuritySummary at(List<SecuritySummary> securities, int index) {
        return securities.get(Math.floorMod(index, securities.size()));
    }

    private static String contentId(int slot) {
        return CONTENT_ID_PREFIX + slot;
    }

    private static String url(NewsSource source, int slot) {
        return "https://example.com/news/" + source.sourceCode().toLowerCase(java.util.Locale.ROOT)
                + "/" + slot;
    }

    /**
     * 发布时间落在交易日的交易时段内（09:30 起每 30 分钟一条，最晚 14:30）。
     *
     * <p>刻意不落在盘后：一条"盘中发布"的资讯若带着盘后时间戳，前端会把它排在收盘之后，
     * 而"这条消息在盘中就已知晓"这个事实就丢了。
     */
    private static OffsetDateTime publishedAt(LocalDate tradeDate, int slot, ZoneId zone) {
        return tradeDate.atTime(9, 30).plusMinutes(30L * slot).atZone(zone).toOffsetDateTime();
    }

    private static NewsSource source(
            long id, String code, String name, NewsSourceType type, boolean allowAiAnalysis) {
        return new NewsSource(
                id,
                code,
                name,
                type,
                "https://example.com/" + code.toLowerCase(java.util.Locale.ROOT),
                NewsSource.AuthorizationStatus.AUTHORIZED,
                null,
                null,
                allowAiAnalysis,
                NewsSource.SourceStatus.ACTIVE,
                null,
                null,
                0);
    }
}
