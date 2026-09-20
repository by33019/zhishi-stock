package cn.zhishi.stock.market.domain;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 板块身份解析端口：对外 {@code sectorId} ↔ 持久化代理键。
 *
 * <h2>为什么必须存在</h2>
 * {@code stock_news_relation.target_id} 是 {@code bigint}（V4，不改表结构），
 * 而契约与前端全程用字符串 {@code sectorId}（{@code sim-bk0001}）。
 * 资讯关联表既挂证券也挂板块，于是"把板块存进业务表"与"回显板块"都需要同一次映射，
 * 而且**只能有一处**：各模块各写一遍 {@code "sim-bk" + 补零} 这类规则，
 * 改一处就会让资讯关联指向另一个板块，且不会有任何测试变红
 * （M2-11 的"写死的标识符只要需要被别处解析，就是缺陷"）。
 *
 * <h2>为什么端口定义在行情域</h2>
 * 与 {@link SecurityIdentityProvider} 一致：板块身份是**行情域的事实**，
 * 不是消费方的事实。消费方只应拿到"一个能存进 bigint 的代理键 + 一个可回显的板块"。
 */
public interface SectorIdentityProvider {

    /** 解析单个对外标识；格式不认识或不在主数据中时返回空（不编造代理键）。 */
    Optional<SectorIdentity> resolve(String sectorId);

    /** 批量解析；无法解析的键**不出现在结果里**，而不是映射成空值。 */
    Map<String, SectorIdentity> resolveAll(Collection<String> sectorIds);

    /** 按代理键批量取回，用于把库里已有的关联还原成对外标识与板块名。 */
    Map<Long, SectorIdentity> findByStorageIds(Collection<Long> storageIds);
}
