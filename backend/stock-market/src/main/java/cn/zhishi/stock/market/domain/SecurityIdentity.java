package cn.zhishi.stock.market.domain;

/**
 * 证券身份：对外契约的字符串 {@code securityId} 与持久化代理键的绑定。
 *
 * <p>存在的理由见 {@link SecurityIdentityProvider}：自选表把证券存成 {@code bigint}，
 * 而契约与前端全程用字符串 {@code securityId}，两者之间需要一次**唯一**的映射。
 * 映射结果同时带上 {@link SecuritySummary}——调用方拿到代理键时几乎总要回显摘要，
 * 分成两次查询会让"代理键有效但摘要查不到"这种不一致状态有机会出现。
 *
 * @param storageId 可存进 {@code bigint} 列的代理键
 * @param summary   该证券的主数据摘要（含对外 {@code securityId}）
 */
public record SecurityIdentity(long storageId, SecuritySummary summary) {

    /** 对外契约的字符串标识，等价于 {@code summary().securityId()}。 */
    public String securityId() {
        return summary.securityId();
    }
}
