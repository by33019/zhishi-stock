package cn.zhishi.stock.market.domain;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 证券身份解析端口：对外 {@code securityId} ↔ 持久化代理键。
 *
 * <h2>为什么必须存在</h2>
 * 契约（{@code RESTful-API.md} §4.1）与前端全程用字符串 {@code securityId}，
 * 而 {@code user_watchlist_item.security_id} 是 {@code bigint}（V5，M1 定下，不改表结构）。
 * 于是任何"把证券存进业务表"的模块都需要一次映射，而且**只能有一处**：
 * 各模块各写一遍 {@code "sim-" + code} 这类规则，改一处就会让自选项指向另一只证券，
 * 且不会有任何测试变红（M2-11 的"写死的标识符只要需要被别处解析，就是缺陷"）。
 *
 * <h2>为什么端口定义在行情域</h2>
 * 证券身份是**行情域的事实**，不是消费方的事实。消费方（自选）只应拿到
 * "一个能存进 bigint 的代理键 + 一个可回显的摘要"，不该知道 ID 的构词规则；
 * 实现落在 {@code stock-integration}，与其它外部数据源适配器同层。
 *
 * <p>端口**不接收 marketCode**：与 {@link SecurityMasterProvider} 一致，
 * 证券主数据是市场无关的全集（当前只有 CN）。
 */
public interface SecurityIdentityProvider {

    /** 解析单个对外标识；格式不认识或不在主数据中时返回空（不编造代理键）。 */
    Optional<SecurityIdentity> resolve(String securityId);

    /** 批量解析；无法解析的键**不出现在结果里**，而不是映射成空值。 */
    Map<String, SecurityIdentity> resolveAll(Collection<String> securityIds);

    /** 按代理键批量取回，用于把库里已有的自选项还原成对外标识与摘要。 */
    Map<Long, SecurityIdentity> findByStorageIds(Collection<Long> storageIds);
}
