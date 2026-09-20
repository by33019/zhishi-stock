package cn.zhishi.stock.market.domain;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把"按板块分组的成分关系"转成"某板块的成分证券集合"。
 *
 * <p>STK-02、QTE-01 与 SEC-06 三条链路都要回答"这个板块里有哪些证券"。
 * 各写一遍的话，"板块筛选"与"成分股列表"会各自演化出不同的成分口径，
 * 而这种分叉不会有任何测试变红——直到用户发现榜单里的股票不在成分股列表里。
 *
 * <p>刻意做成静态工具而不是持有索引的对象：调用方每次只查一个板块，
 * 为一次查询构建 39 个板块的索引是白费功夫；关系本身（{@code memberships} 的返回值）
 * 已经是可复用的数据结构，需要重复查询时由调用方自行复用。
 */
public final class SectorMembershipIndex {

    private SectorMembershipIndex() {
    }

    /**
     * 该板块的成分证券 ID 集合；板块不存在或没有关系时返回**空集合**而不是 {@code null}。
     *
     * <p>用 {@code LinkedHashSet} + {@code unmodifiableSet} 而不是 {@code Set.copyOf}：
     * 后者的迭代顺序未定义，会让"未指定排序时的成分顺序"变成不可复现的。
     */
    public static Set<String> securityIdsOf(
            Map<String, List<SectorMember>> memberships, String sectorId) {
        if (sectorId == null) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (SectorMember member : memberships.getOrDefault(sectorId, List.of())) {
            if (member.securityId() != null) {
                ids.add(member.securityId());
            }
        }
        return Collections.unmodifiableSet(ids);
    }
}
