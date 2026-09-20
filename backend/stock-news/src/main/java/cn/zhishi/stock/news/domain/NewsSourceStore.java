package cn.zhishi.stock.news.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 资讯来源仓储端口。
 *
 * <p>{@code findAll} 而不是"按需逐个查"：来源总量是**几十条**量级（授权媒体、交易所、
 * 公司与监管机构），一次全取后在用例层做授权判定，比每条稿件都回表查一次来源状态便宜得多，
 * 也让"一次采集里所有稿件看到的是同一份来源状态"由接口形状保证。
 */
public interface NewsSourceStore {

    /** 全部来源（含已停用——是否可用由 {@link NewsSource#usableOn} 判定）。 */
    List<NewsSource> findAll();

    /** 按来源编码取一条；未登记返回空。 */
    Optional<NewsSource> findByCode(String sourceCode);

    /** 按代理键取一条，用于把稿件上的 {@code source_id} 还原成当前来源状态。 */
    Optional<NewsSource> findById(long sourceId);

    /**
     * 登记尚未存在的来源，**只插入、不更新**。
     *
     * <p>"不更新"是刻意的：授权状态与运行状态是人工决定（后台 ADM-NEWS-04），
     * 采集侧无权改它——否则一个 Provider 就能把自己声明的 {@code AUTHORIZED} 写回库里，
     * 把"停用某个来源"这件事在下一次采集后静默撤销。
     */
    void ensureAll(Collection<NewsSource> sources);

    /** 批量记录"本次采集对该来源成功"。 */
    void recordSyncSuccess(Collection<Long> sourceIds, java.time.OffsetDateTime at);

    /** 批量记录"本次采集对该来源失败"。 */
    void recordSyncFailure(Collection<Long> sourceIds, java.time.OffsetDateTime at);
}
