package cn.zhishi.stock.admin.domain;

import cn.zhishi.stock.news.domain.NewsRelationStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 资讯关联的后台读写端口（契约 §17.2 ADM-NEWS-05~08）。
 *
 * <h2>为什么后台自带一个端口，而不扩资讯域的 {@code NewsRelationStore}</h2>
 * 与 {@link AdminNewsSourceStore} 同一条理由：资讯域的端口是为采集设计的
 * （只有 {@code insertAll}，注释写明"后台复核界面需要按 relation_status 查时再扩"）。
 * 但"扩"的方向不是往采集端口上加后台用例，而是后台按自己的用例另立端口——
 * 采集侧的实现是内存缓存语义（{@code findAll} 全量），塞进分页只会让两边都不像话。
 *
 * <p>表是共享的：这里写入的 {@code relation_status} / {@code reviewed_by} /
 * {@code reviewed_at}，前台资讯查询（CONFIRMED 可见、CANDIDATE 不可见）下一次
 * 查询即可看到——资讯域没有缓存层（M3-04 的既定决策），不存在"审核后要刷新缓存"。
 */
public interface AdminNewsRelationStore {

    List<AdminNewsRelationEntry> page(AdminNewsRelationQuery query);

    long count(AdminNewsRelationQuery query);

    Optional<AdminNewsRelationEntry> find(long relationId);

    /** 关联目标的新闻是否存在（ADM-NEWS-07 校验用；不存在是 404 而不是空关联）。 */
    boolean newsExists(long newsId);

    /**
     * 手工建立关联（{@code relation_method='MANUAL'}、{@code status='CONFIRMED'}、
     * 置信度 NULL——人的判断没有机器分数）。{@code relationId} 由仓储侧生成器分配。
     *
     * <p>违反 {@code uk_news_relation_target}（同新闻 + 同目标）时抛出
     * {@code DuplicateKeyException}，由用例层转成幂等成功（回读已有记录），
     * 与资讯采集侧对并发的处置同一口径。
     */
    AdminNewsRelationEntry insertManual(
            long newsId,
            AdminNewsRelationTarget target,
            String reasonSummary,
            long reviewedBy,
            OffsetDateTime reviewedAt);

    /** 按目标回读已有关联（幂等回放与 {@code findByTarget} 唯一索引配套）。 */
    Optional<AdminNewsRelationEntry> findByTarget(
            long newsId,
            cn.zhishi.stock.news.domain.NewsTargetType targetType,
            long targetId);

    /**
     * 复核或拒绝（ADM-NEWS-06 / ADM-NEWS-08 共用一条写路径）。
     *
     * <p>{@code reviewedBy} / {@code reviewedAt} 由调用方给定（不是 SQL 的 NOW()）：
     * "谁在什么时候审的"由用例层的 Clock 决定，测试才钉得住。
     */
    boolean review(
            long relationId,
            NewsRelationStatus relationStatus,
            String reasonSummary,
            long reviewedBy,
            OffsetDateTime reviewedAt);

    /**
     * 手工关联的目标（对外标识已解析成代理键之后的形状）。
     *
     * @param targetType 目标类型
     * @param targetId   关联表代理键（SECURITY / SECTOR 来自身份端口，MARKET 来自编码规则）
     */
    record AdminNewsRelationTarget(
            cn.zhishi.stock.news.domain.NewsTargetType targetType, long targetId) {
    }
}
