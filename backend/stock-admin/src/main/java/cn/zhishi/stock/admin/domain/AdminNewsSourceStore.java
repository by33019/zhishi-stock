package cn.zhishi.stock.admin.domain;

import cn.zhishi.stock.news.domain.NewsSource;
import java.util.List;
import java.util.Optional;

/**
 * 资讯来源的后台读写端口（契约 §17.1 ADM-NEWS-01~04）。
 *
 * <h2>为什么后台自带一个端口，而不扩资讯域的 {@code NewsSourceStore}</h2>
 * 资讯域的端口形状是为**采集**设计的（{@code ensureAll} 只插不改、按 code 找），
 * 注释里写明"授权状态是人工决定（后台 ADM-NEWS-04），采集侧无权改"。
 * 后台需要的是分页、按条件筛、乐观锁更新——两种形状塞进一个端口，
 * 采集侧的实现就得为用不到的能力留空壳。与 {@code AdminUserStore} 之于
 * {@code sys_user} 同一条路：表是共享的，端口按读写方各自的用例切。
 *
 * <p>返回值复用资讯域的 {@link NewsSource}（同一批列、同一套枚举），
 * 但 SQL 与行映射是后台自己的——分页与过滤是本端口的存在理由。
 */
public interface AdminNewsSourceStore {

    List<NewsSource> page(AdminNewsSourceQuery query);

    long count(AdminNewsSourceQuery query);

    Optional<NewsSource> find(long sourceId);

    Optional<NewsSource> findByCode(String sourceCode);

    /**
     * 新增来源。{@code id} 由仓储侧的生成器分配（与 {@code AdminUserStore} 同源），
     * {@code version} 从 0 起步；{@code authorizationStatus} 是用例层推导完的结果
     * （"什么算有效授权"只允许有 {@code AdminNewsSourceService} 一个出处）。
     *
     * <p>违反 {@code uk_news_source_code} 时抛出 {@code DuplicateKeyException}，
     * 由用例层转成 {@code NEWS_SOURCE_CODE_EXISTS}。
     */
    NewsSource insert(NewAdminNewsSource command, NewsSource.AuthorizationStatus authorizationStatus);

    /**
     * 乐观锁更新（CAS）：{@code WHERE id = ? AND version = ?}，命中则 {@code version + 1}。
     *
     * <p>{@code authorizationStatus} 由用例层**先解析完**再传入（客户端显式值、
     * 区间重推、暂停保持三类规则的最终结果），存储层原样落列，不做任何二次推导——
     * "什么算有效授权"只允许有 {@code AdminNewsSourceService} 一个出处。
     *
     * <p>返回空表示**没有行被更新**——可能是"来源不存在"也可能是"版本过期"，
     * 区分这两件事需要回读，由用例层负责（与 {@code AdminUserService} 同一分工）。
     */
    Optional<NewsSource> update(
            long sourceId,
            int expectedVersion,
            AdminNewsSourcePatch patch,
            NewsSource.AuthorizationStatus authorizationStatus);
}
