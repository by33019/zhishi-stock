package cn.zhishi.stock.market.domain;

/**
 * 板块身份：对外契约的字符串 {@code sectorId} 与持久化代理键的绑定。
 *
 * <p>与 {@link SecurityIdentity} 同因同形。存在的理由是 {@code stock_news_relation.target_id}
 * 是 {@code bigint}，而板块的对外标识是字符串 {@code sim-bk0001}——
 * 资讯关联表既存证券也存板块，两者都需要一次**唯一**的映射。
 *
 * @param storageId 可存进 {@code bigint} 列的代理键
 * @param sector    该板块的主数据
 */
public record SectorIdentity(long storageId, Sector sector) {

    /** 对外契约的字符串标识，等价于 {@code sector().sectorId()}。 */
    public String sectorId() {
        return sector.sectorId();
    }
}
