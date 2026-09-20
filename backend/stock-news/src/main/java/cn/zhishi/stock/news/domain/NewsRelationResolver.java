package cn.zhishi.stock.news.domain;

import cn.zhishi.stock.market.domain.SectorIdentity;
import cn.zhishi.stock.market.domain.SecurityIdentity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关联解析：把一条来源条目变成一组"这条资讯关于谁"的关联。
 *
 * <h2>两条产关联的路径，必须可区分</h2>
 *
 * <ul>
 *   <li><b>结构化提示</b>（{@code NewsFeedItem.relatedSecurityCodes} 等）→
 *       {@link NewsRelationMethod#EXPLICIT}，置信度 {@code 1.00000}：
 *       来源说了就算，不存在"置信度多少"这个问题；
 *   <li><b>文本匹配</b>（标题/摘要里出现证券简称或板块名）→
 *       {@link NewsRelationMethod#RULE}，置信度按"命中的是标题还是摘要"分档。
 * </ul>
 *
 * <h2>为什么"仅摘要命中"算低置信</h2>
 * 标题是编辑对"这条稿件关于谁"的判断，摘要里顺带提到一家公司可能只是同题材类比。
 * 这不是拍脑袋的阈值，而是**编辑判断权的建模**：
 *
 * <table>
 *   <caption>规则表</caption>
 *   <tr><th>#</th><th>触发</th><th>method</th><th>confidence</th><th>status</th></tr>
 *   <tr><td>R1</td><td>结构化证券代码，且在目录中</td><td>EXPLICIT</td><td>1.00000</td><td>CONFIRMED</td></tr>
 *   <tr><td>R2</td><td>结构化 marketCode</td><td>EXPLICIT</td><td>1.00000</td><td>CONFIRMED</td></tr>
 *   <tr><td>R3</td><td><b>标题</b>含证券简称</td><td>RULE</td><td>0.80000</td><td>CONFIRMED</td></tr>
 *   <tr><td>R4</td><td><b>仅摘要</b>含证券简称</td><td>RULE</td><td>0.60000</td><td>CANDIDATE</td></tr>
 *   <tr><td>R5</td><td><b>标题</b>含板块名</td><td>RULE</td><td>0.75000</td><td>CONFIRMED</td></tr>
 *   <tr><td>R6</td><td><b>仅摘要</b>含板块名</td><td>RULE</td><td>0.50000</td><td>CANDIDATE</td></tr>
 * </table>
 *
 * <p>归类一律走 {@link NewsConfidence#classify(BigDecimal)}，不在本类里再写一遍 {@code 0.7}。
 *
 * <h2>两条必须实现的不变量</h2>
 *
 * <ol>
 *   <li><b>同一目标只产出一条关联</b>，取置信度最高者。
 *       {@code uk_news_relation_target} 是唯一索引，"哪条留下"若取决于插入顺序，
 *       就是典型的"看起来对、其实不确定"；
 *   <li><b>最长匹配优先</b>。模拟证券名是 {@code 模拟证券600519}，而板块名里有一个
 *       {@code 证券}——不做最长匹配的话，每条提到某只证券的资讯都会顺带关联上"证券"板块。
 *       被更长匹配**完全覆盖**的匹配一律丢弃。
 * </ol>
 *
 * <p>本类是无 I/O、无时钟的纯函数：目录索引在构造时建一次，{@link #resolve} 可反复调用。
 */
public final class NewsRelationResolver {

    /** 短于 2 个字符的名称不参与匹配：单字名称的命中几乎全是噪音。 */
    private static final int MIN_NAME_LENGTH = 2;

    private static final BigDecimal EXPLICIT_CONFIDENCE = new BigDecimal("1.00000");
    private static final BigDecimal TITLE_SECURITY_CONFIDENCE = new BigDecimal("0.80000");
    private static final BigDecimal SUMMARY_SECURITY_CONFIDENCE = new BigDecimal("0.60000");
    private static final BigDecimal TITLE_SECTOR_CONFIDENCE = new BigDecimal("0.75000");
    private static final BigDecimal SUMMARY_SECTOR_CONFIDENCE = new BigDecimal("0.50000");

    /**
     * 一条待落库的关联（还没有 {@code relationId} 与 {@code newsId}——那两个由采集服务分配）。
     *
     * <p>刻意不复用 {@link NewsRelation}：后者是"库里已有的事实"，两个 id 都是它的一部分。
     * 用一个 {@code relationId = 0} 的占位对象冒充，会让"这个 id 是真的吗"变成需要推理的问题。
     */
    public record ResolvedRelation(
            NewsTargetType targetType,
            long targetId,
            NewsRelationMethod relationMethod,
            BigDecimal confidenceScore,
            NewsRelationStatus relationStatus,
            String reasonSummary) {
    }

    /** 可被匹配的名称及其目标。 */
    private record NamedTarget(NewsTargetType targetType, long targetId, String name) {
    }

    /** 目标唯一键：同一 (类型, id) 只允许一条关联。 */
    private record TargetKey(NewsTargetType targetType, long targetId) {
    }

    /** 文本里的一次命中：{@code [start, end)}。 */
    private record Span(int start, int end, NamedTarget target) {

        int length() {
            return end - start;
        }

        /** 是否被 {@code other} 严格包含（更长的匹配压过它）。 */
        boolean coveredBy(Span other) {
            return other.start() <= start && other.end() >= end && other.length() > length();
        }
    }

    private final RelationCatalog catalog;
    private final List<NamedTarget> targets;

    public NewsRelationResolver(RelationCatalog catalog) {
        this.catalog = catalog;
        this.targets = buildTargets(catalog);
    }

    /** 解析一条来源条目的全部关联；先证券后板块，同类按置信度降序。 */
    public List<ResolvedRelation> resolve(NewsFeedItem item) {
        Map<TargetKey, ResolvedRelation> best = new LinkedHashMap<>();

        // R1 / R2：来源侧结构化提示，无需推断
        for (String securityCode : item.relatedSecurityCodes()) {
            catalog.securityByCode(securityCode).ifPresent(identity -> keepHighest(
                    best,
                    explicit(
                            NewsTargetType.SECURITY,
                            identity.storageId(),
                            "来源侧结构化字段给出证券代码 " + securityCode)));
        }
        NewsMarketTargets.storageIdOf(item.marketCode()).ifPresent(storageId -> keepHighest(
                best,
                explicit(
                        NewsTargetType.MARKET,
                        storageId,
                        "来源侧结构化字段给出市场 " + item.marketCode())));

        // R3~R6：文本匹配。标题优先；摘要只补标题没命中的目标，keepHighest 保证不会降档。
        for (NamedTarget target : matchIn(item.title())) {
            keepHighest(best, textMatch(target, true));
        }
        for (NamedTarget target : matchIn(item.summary())) {
            keepHighest(best, textMatch(target, false));
        }

        return sorted(List.copyOf(best.values()));
    }

    private static ResolvedRelation explicit(
            NewsTargetType targetType, long targetId, String reason) {
        return new ResolvedRelation(
                targetType,
                targetId,
                NewsRelationMethod.EXPLICIT,
                EXPLICIT_CONFIDENCE,
                NewsConfidence.classify(EXPLICIT_CONFIDENCE),
                reason);
    }

    private static ResolvedRelation textMatch(NamedTarget target, boolean inTitle) {
        boolean security = target.targetType() == NewsTargetType.SECURITY;
        BigDecimal confidence = inTitle
                ? (security ? TITLE_SECURITY_CONFIDENCE : TITLE_SECTOR_CONFIDENCE)
                : (security ? SUMMARY_SECURITY_CONFIDENCE : SUMMARY_SECTOR_CONFIDENCE);
        String where = inTitle ? "标题" : "仅摘要";
        String kind = security ? "证券简称" : "板块名";
        return new ResolvedRelation(
                target.targetType(),
                target.targetId(),
                NewsRelationMethod.RULE,
                confidence,
                NewsConfidence.classify(confidence),
                where + "含" + kind + "「" + target.name() + "」");
    }

    /** 只保留每个目标置信度最高的那条。 */
    private static void keepHighest(
            Map<TargetKey, ResolvedRelation> best, ResolvedRelation candidate) {
        TargetKey key = new TargetKey(candidate.targetType(), candidate.targetId());
        ResolvedRelation existing = best.get(key);
        if (existing == null
                || candidate.confidenceScore().compareTo(existing.confidenceScore()) > 0) {
            best.put(key, candidate);
        }
    }

    /**
     * 文本里命中的全部目标（已去重），并做最长匹配过滤。
     *
     * <p>用 {@link Set} 而不是 {@link List}：同一目标可能在标题里出现两遍，
     * 这里就去重，避免上层再处理一遍。
     */
    private Set<NamedTarget> matchIn(String text) {
        Set<NamedTarget> matched = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return matched;
        }
        List<Span> spans = new ArrayList<>();
        for (NamedTarget target : targets) {
            int from = 0;
            while (true) {
                int at = text.indexOf(target.name(), from);
                if (at < 0) {
                    break;
                }
                spans.add(new Span(at, at + target.name().length(), target));
                from = at + 1;
            }
        }
        for (Span span : spans) {
            boolean covered = false;
            for (Span other : spans) {
                if (other != span && span.coveredBy(other)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                matched.add(span.target());
            }
        }
        return matched;
    }

    private static List<NamedTarget> buildTargets(RelationCatalog catalog) {
        List<NamedTarget> targets = new ArrayList<>();
        for (SecurityIdentity identity : catalog.securities()) {
            addTarget(targets, NewsTargetType.SECURITY, identity.storageId(),
                    identity.summary().securityName());
        }
        for (SectorIdentity identity : catalog.sectors()) {
            addTarget(targets, NewsTargetType.SECTOR, identity.storageId(),
                    identity.sector().sectorName());
        }
        return List.copyOf(targets);
    }

    private static void addTarget(
            List<NamedTarget> targets, NewsTargetType type, long targetId, String name) {
        if (name != null && name.length() >= MIN_NAME_LENGTH) {
            targets.add(new NamedTarget(type, targetId, name));
        }
    }

    private static List<ResolvedRelation> sorted(List<ResolvedRelation> relations) {
        List<ResolvedRelation> copy = new ArrayList<>(relations);
        copy.sort((left, right) -> {
            int byType = left.targetType().compareTo(right.targetType());
            if (byType != 0) {
                return byType;
            }
            return right.confidenceScore().compareTo(left.confidenceScore());
        });
        return List.copyOf(copy);
    }
}
