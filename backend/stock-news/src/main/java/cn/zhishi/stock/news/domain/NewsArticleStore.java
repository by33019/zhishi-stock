package cn.zhishi.stock.news.domain;

import java.util.List;
import java.util.Optional;

/**
 * 稿件仓储端口（读 + 写）。
 *
 * <h2>为什么读出来的是 {@link NewsRecord} 而不是 {@link NewsArticle}</h2>
 * 一条资讯能不能展示，由它自己、它**当前**的来源状态、它的关联三者共同决定。
 * 分开查会留出一个"读了稿件却忘了读来源"的窗口，而那个窗口不会报错，
 * 只会让一条未授权来源的资讯出现在列表里。合成一个读口子，漏读就不可能发生。
 *
 * <h2>为什么 {@link #findAll()} 是全量取数</h2>
 * 与榜单、板块预览一样：整批取数后在用例层筛/排/分页，
 * "所有可见性判据只写一遍"因此由接口形状保证。
 *
 * <p><b>已知局限</b>：资讯表会随采集持续增长，全量取数只在模拟源的数据量下可行。
 * 真实源接入时必须把过滤下推到 SQL——索引已经就绪
 * （{@code idx_stock_news_publish}、{@code idx_news_relation_target_status}），
 * 届时本端口的形状会变，这正是它的进化点。
 */
public interface NewsArticleStore {

    /** 全部稿件及其来源与关联。 */
    List<NewsRecord> findAll();

    /** 按主键取一条（NEWS-02）。 */
    Optional<NewsRecord> find(long newsId);

    /**
     * 内容指纹幂等：找库里同指纹的**主记录** id。
     *
     * <p>只找 {@code dedup_status = ORIGINAL} 的：重复稿不能当主记录，
     * 否则 {@code canonical_news_id} 会指向另一条重复稿，形成链而不是星形。
     */
    Optional<Long> findOriginalNewsIdByFingerprint(String fingerprint);

    /** 来源 ID 幂等：该 {@code (来源, 来源侧稿件 id)} 是否已入库。 */
    boolean existsBySourceContent(long sourceId, String sourceContentId);

    /** 插入一条稿件；撞唯一索引时原样抛 {@code DuplicateKeyException}，由用例层决定它意味着什么。 */
    void insert(NewsArticle article);
}
