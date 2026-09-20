package cn.zhishi.stock.news.domain;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;

/**
 * "某几只证券最近有多少条资讯"的计数端口，供自选域回显 {@code latestNewsCount}。
 *
 * <p>为什么是**一次全取**而不是 {@code count(securityId)}：WAT-11 一次要回显整页自选，
 * 逐个查是 N+1。端口按集合入参，实现一次算完。
 *
 * <p>为什么端口定义在资讯域：资讯数是资讯域的事实。消费方（自选）只应拿到一个能问数的口子，
 * 不该知道"资讯"是怎么存的。
 *
 * <p><b>未命中的证券不出现在结果里</b>，而不是映射成 {@code 0}：这条约定沿用 M3-03
 * （{@code latestNewsCount} 恒为 {@code null} 而不是 {@code 0}），消费方对外也写 {@code null}。
 *
 * <p>要注意它的**代价**：实现无论资讯源是否可用，都只返回"有条数"的证券，
 * 于是"未知"没有任何代码路径产生——"0 条"与"不知道"事实上被合并了。
 * 这是已记录的口径缺陷（spec §8.4 / 已知问题 #20）；要真正区分，
 * 需要让 WAT-11 也带上资讯域的新鲜度，那是给契约加字段。
 */
public interface NewsCountProvider {

    /**
     * @param securityIds 对外 {@code securityId} 集合
     * @param since       计数起点（不含）；{@code null} 表示不设下界
     * @return 键为 {@code securityId}；没有任何资讯的证券**不出现**在返回值里
     */
    Map<String, Integer> countSince(Collection<String> securityIds, OffsetDateTime since);
}
