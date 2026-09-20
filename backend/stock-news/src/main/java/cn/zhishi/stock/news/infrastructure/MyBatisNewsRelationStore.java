package cn.zhishi.stock.news.infrastructure;

import cn.zhishi.stock.news.domain.NewsRelation;
import cn.zhishi.stock.news.domain.NewsRelationStore;
import java.util.List;

/**
 * {@link NewsRelationStore} 的 MyBatis 实现。
 *
 * <p>逐条插入而不是批量语句：一次采集的关联量在几十条量级，
 * 而批量插入需要额外的 SQL 拼装或 {@code ExecutorType.BATCH} 会话，
 * 引入的复杂度换不来可观测的收益。真实源接入、量级变化时再改。
 */
public class MyBatisNewsRelationStore implements NewsRelationStore {

    private final NewsRelationMapper mapper;

    public MyBatisNewsRelationStore(NewsRelationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insertAll(List<NewsRelation> relations) {
        for (NewsRelation relation : relations) {
            mapper.insert(
                    relation.relationId(),
                    relation.newsId(),
                    relation.targetType(),
                    relation.targetId(),
                    relation.relationMethod(),
                    relation.confidenceScore(),
                    relation.relationStatus(),
                    relation.reasonSummary());
        }
    }
}
