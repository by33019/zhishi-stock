package cn.zhishi.stock.market.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 整批快照的公共视图：批次属性 + 按证券索引 + 按成分关系取数。
 *
 * <p>榜单（QTE-01）、板块排行（SEC-02）、板块详情（SEC-03/04/06）与总览的板块预览段（MKT-01）
 * 都要回答同样三个问题："这批快照的版本与时间是什么"、"某只证券的快照在哪"、
 * "这个板块的成分股快照有哪些"。各写一遍的话，四处的"批次属性取首行"与
 * "缺快照的成分如何处理"会各自演化，而这种分叉不会有任何测试变红。
 *
 * <h2>为什么它属于 {@code domain} 而不是 {@code application}</h2>
 * 它只依赖领域类型（{@link QuoteSnapshot} / {@link SectorMember} /
 * {@link MarketOverview.DataStatus}），是一个**纯视图**，没有任何用例编排。
 * 它最初落在 {@code application} 只是因为前三个消费方都在那里；
 * 当第 4 个消费方——摄入侧的 {@code SimulatedQuoteProvider}——出现时，
 * 它够不到一个包私有的上层类，于是要么把语义抄第 4 遍，要么把本类下沉一层。
 * 选择后者：下沉不构成依赖倒置，而"缺快照的成分如何处理"从此只有一份。
 *
 * <p>索引在工厂方法里建一次：逐只线性查找会让 5149 只证券的取数退化成 O(n²)。
 */
public final class QuoteBatch {

    private final List<QuoteSnapshot> snapshots;
    private final Map<String, QuoteSnapshot> bySecurityId;

    private QuoteBatch(List<QuoteSnapshot> snapshots, Map<String, QuoteSnapshot> bySecurityId) {
        this.snapshots = snapshots;
        this.bySecurityId = bySecurityId;
    }

    public static QuoteBatch of(List<QuoteSnapshot> snapshots) {
        List<QuoteSnapshot> frozen = List.copyOf(snapshots);
        Map<String, QuoteSnapshot> index = new HashMap<>();
        for (QuoteSnapshot snapshot : frozen) {
            // putIfAbsent：同 ID 重复时保留先出现的那条，与其它链路"取第一条"的行为一致
            index.putIfAbsent(snapshot.security().securityId(), snapshot);
        }
        return new QuoteBatch(frozen, Map.copyOf(index));
    }

    public List<QuoteSnapshot> snapshots() {
        return snapshots;
    }

    /** 某只证券在该批次里的快照；不在批次内时返回空。 */
    public Optional<QuoteSnapshot> snapshotOf(String securityId) {
        return Optional.ofNullable(bySecurityId.get(securityId));
    }

    /**
     * 按成分关系取出快照，顺序与关系给出的顺序一致。
     *
     * <p>缺快照的成分被**跳过**：宁可在成分列表里少一行，也不要一行没有行情的"证券"。
     * 这与 M2-06 整批装配里"缺主数据的行情被跳过"是同一条取舍。
     */
    public List<QuoteSnapshot> ofMembers(List<SectorMember> members) {
        List<QuoteSnapshot> result = new ArrayList<>(members.size());
        for (SectorMember member : members) {
            QuoteSnapshot snapshot = bySecurityId.get(member.securityId());
            if (snapshot != null) {
                result.add(snapshot);
            }
        }
        return List.copyOf(result);
    }

    /** 整批共用的快照版本；批次为空时返回空串——编造版本号会让人误以为有数据。 */
    public String version() {
        return snapshots.isEmpty() ? "" : snapshots.get(0).sequence();
    }

    public OffsetDateTime dataTime() {
        return snapshots.isEmpty() ? null : snapshots.get(0).dataTime();
    }

    public MarketOverview.DataStatus dataStatus() {
        return snapshots.isEmpty()
                ? MarketOverview.DataStatus.UNAVAILABLE
                : snapshots.get(0).dataStatus();
    }
}
