package cn.zhishi.stock.news.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 资讯采集端口。真实供应商适配器与模拟实现同构，替换实现类即可切换。
 *
 * <p>与行情域的 {@code QuoteProvider} 一样，端口**只做取数**，不做去重、不做关联、
 * 不判授权——那些是用例层的事实判断。Provider 的职责边界是"把来源侧的样子搬进来"。
 *
 * <p>端口不接收游标（cursor）：增量采集在 MVP 里表现为"给我最近一段时间的条目"，
 * 由 {@code since} 表达。真实源接入若需要分页游标，应在端口上加参数而不是
 * 让调用方循环调用（循环调用会让"整批同一基准时刻"失效）。
 */
public interface NewsProvider {

    /**
     * 拉取自 {@code since}（不含）以来发布的条目。
     *
     * @param since 增量起点；{@code null} 表示不设下界
     */
    NewsFeed fetch(OffsetDateTime since);

    /**
     * 本 Provider 声明的来源清单，采集前由用例层确保它们已在 {@code news_source} 中登记。
     *
     * <p>默认空列表：真实环境下来源由管理员通过后台接口（ADM-NEWS-03）登记，
     * Provider 只负责取数。模拟实现自带一份清单，否则新环境里"库里没有任何来源"
     * 会让采集与展示同时静默地什么都做不了——失败没有任何提示。
     *
     * <p>登记的语义是**只插入缺失**，见 {@link NewsSourceStore#ensureAll}：
     * Provider 无权改已有来源的授权状态。
     */
    default List<NewsSource> sources() {
        return List.of();
    }
}
