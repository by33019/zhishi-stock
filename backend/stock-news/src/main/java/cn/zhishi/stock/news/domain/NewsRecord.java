package cn.zhishi.stock.news.domain;

import java.util.List;

/**
 * 稿件 + 它的**当前**来源 + 该稿件的全部关联。
 *
 * <p>为什么把三者组合而不是让调用方自己查：
 *
 * <ul>
 *   <li>来源状态会变（被停用、授权到期），而稿件表里只有 {@code sourceId}。
 *       每次判定可见性都必须拿**当前**来源状态，组合体保证不会有人漏查；
 *   <li>关联是稿件可见性的一部分（"与股票确认关联的资讯"要求先有确认关联），
 *       分开查会出现"读了稿件却忘了读关联"的窗口。
 * </ul>
 *
 * <p>三者在此都是**未过滤**的原始数据：可见性判定与关联筛选统一由
 * {@code NewsQueryService} 负责，本记录不做任何判断。
 */
public record NewsRecord(NewsArticle article, NewsSource source, List<NewsRelation> relations) {

    public NewsRecord {
        relations = List.copyOf(relations);
    }

    /** 该稿件的已确认关联（契约 §11.2：只有 {@code CONFIRMED} 可用）。 */
    public List<NewsRelation> confirmedRelations() {
        return relations.stream().filter(NewsRelation::confirmed).toList();
    }
}
