package cn.zhishi.stock.news.domain;

import java.util.List;

/**
 * 资讯关联仓储端口。
 *
 * <p>只写不读：关联的读取路径统一走 {@link NewsArticleStore#findAll()} 的组合读，
 * 不另开一个"按资讯 id 批量查关联"的入口——多一个入口就多一次"某条链路忘了带上关联"的机会。
 * 后台复核界面（M3-11）需要按 {@code relation_status} 查候选时再扩本端口。
 */
public interface NewsRelationStore {

    /** 批量插入；同一批里若有重复的 {@code (资讯, 目标类型, 目标)}，唯一索引会拒绝。 */
    void insertAll(List<NewsRelation> relations);
}
