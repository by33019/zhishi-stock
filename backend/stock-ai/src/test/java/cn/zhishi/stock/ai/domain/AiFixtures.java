package cn.zhishi.stock.ai.domain;

import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketSessionStatus;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.Sector;
import cn.zhishi.stock.market.domain.SectorMember;
import cn.zhishi.stock.market.domain.SectorType;
import cn.zhishi.stock.market.domain.SecuritySummary;
import cn.zhishi.stock.news.domain.NewsEvidence;
import cn.zhishi.stock.news.domain.NewsOriginalAccessStatus;
import cn.zhishi.stock.news.domain.NewsType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * AI 域测试夹具。
 *
 * <p>与 {@code stock-news} 的 {@code NewsFixtures} 不共用：那是资讯域的夹具，
 * 跨模块引用测试代码需要 test-jar，而这里需要的形状（行情快照、市场总览）本来也不同。
 * 代理键与对外标识的对应关系刻意与模拟源一致（{@code 600519} ↔ {@code sim-600519}），
 * 免得"同一只证券"在两个模块里被构造成不同的键。
 */
public final class AiFixtures {

    /** 行情批次对应的数据时间（A 股收盘）。 */
    public static final OffsetDateTime DATA_TIME = OffsetDateTime.parse("2026-09-18T15:00:00+08:00");

    /** 资讯发布时间。 */
    public static final OffsetDateTime PUBLISHED = OffsetDateTime.parse("2026-09-18T10:00:00+08:00");

    public static final String MARKET_CODE = "CN";

    private AiFixtures() {
    }

    // ---------- 行情 ----------

    public static SecuritySummary security(String code, String name) {
        return new SecuritySummary(
                "sim-" + code,
                "SH" + code,
                code,
                name,
                "SH",
                "STOCK",
                "MAIN",
                "LISTED",
                false,
                false,
                2,
                null,
                null);
    }

    public static QuoteSnapshot quote(String code, String name, OffsetDateTime dataTime) {
        return new QuoteSnapshot(
                security(code, name),
                "10.00",
                "10.20",
                "10.80",
                "11.00",
                "10.10",
                "0.80",
                "0.0800",
                "1200000",
                "12960000",
                "0.0123",
                dataTime,
                dataTime,
                "seq-20260918",
                MarketOverview.DataStatus.REALTIME,
                0);
    }

    public static MarketOverview overview(OffsetDateTime dataTime) {
        return new MarketOverview(
                MARKET_CODE,
                MarketSessionStatus.TRADING,
                LocalDate.of(2026, 9, 18),
                dataTime,
                MarketOverview.DataStatus.REALTIME,
                List.of(),
                new MarketOverview.BreadthData(2876, 1924, 300, 49, 82, 3),
                new MarketOverview.TurnoverData("982600000000", "950000000000", List.of()),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                dataTime,
                "seq-20260918");
    }

    // ---------- 板块 ----------

    public static Sector sector(String sectorId, String code, String name) {
        return new Sector(sectorId, code, name, SectorType.INDUSTRY.code(), null, 1, Sector.STATUS_ACTIVE);
    }

    public static SectorMember member(String securityId, String sectorId, boolean primary) {
        return new SectorMember(
                securityId,
                sectorId,
                primary ? SectorMember.RELATION_PRIMARY : SectorMember.RELATION_MEMBER,
                primary,
                LocalDate.of(2026, 1, 1),
                null);
    }

    // ---------- 资讯 ----------

    public static NewsEvidence news(long newsId, String title, OffsetDateTime publishedAt) {
        return new NewsEvidence(
                newsId,
                NewsType.NEWS,
                title,
                "摘要" + newsId,
                "模拟财经媒体A",
                publishedAt,
                "https://example.com/news/" + newsId,
                NewsOriginalAccessStatus.UNKNOWN);
    }

    public static NewsEvidence announcement(long newsId, String title, OffsetDateTime publishedAt) {
        return new NewsEvidence(
                newsId,
                NewsType.ANNOUNCEMENT,
                title,
                "公告摘要" + newsId,
                "模拟交易所",
                publishedAt,
                "https://example.com/notice/" + newsId,
                NewsOriginalAccessStatus.UNKNOWN);
    }

    // ---------- 目标 ----------

    public static AiContextTarget securityTarget(String code, AiTargetRole role) {
        return new AiContextTarget(
                AiTargetType.SECURITY, "sim-" + code, code, "模拟证券" + code, role, Long.parseLong(code));
    }

    public static AiContextTarget sectorTarget(String sectorId, String code, AiTargetRole role) {
        return new AiContextTarget(
                AiTargetType.SECTOR, sectorId, code, "模拟板块" + code, role, (long) code.hashCode());
    }

    public static AiContextTarget marketTarget() {
        return new AiContextTarget(
                AiTargetType.MARKET, MARKET_CODE, MARKET_CODE, MARKET_CODE, AiTargetRole.PRIMARY, 1L);
    }

    // ---------- 哈希 ----------

    /**
     * 确定性哈希桩。
     *
     * <p>刻意**不**复用 {@code SimulatedHashing}（它在 {@code stock-integration}，
     * 而本模块的测试不依赖它）：这里要验证的是"构建器把内容交给 hasher 并原样存下结果"，
     * 不是哈希算法本身。用 {@code TreeMap} 保证与 Map 迭代顺序无关——这正是端口契约要求的。
     */
    public static AiContentHasher hasher() {
        return new AiContentHasher() {
            @Override
            public String hashOf(Map<String, ?> content) {
                return hashOfText(String.valueOf(new TreeMap<>(content)));
            }

            @Override
            public String hashOfText(String text) {
                long value = text == null ? 0L : text.hashCode();
                return String.format("%064x", value & 0xffffffffL);
            }
        };
    }
}
