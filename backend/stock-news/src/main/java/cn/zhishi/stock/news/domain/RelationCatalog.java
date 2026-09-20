package cn.zhishi.stock.news.domain;

import cn.zhishi.stock.market.domain.SectorIdentity;
import cn.zhishi.stock.market.domain.SecurityIdentity;
import java.util.List;
import java.util.Optional;

/**
 * 关联解析用的目录：可被匹配的证券与板块，带各自的持久化代理键。
 *
 * <p>为什么带 {@link SecurityIdentity} / {@link SectorIdentity} 而不是
 * {@code SecuritySummary} / {@code Sector}：解析出来的关联要立刻落库，
 * 而 {@code target_id} 是 bigint。让解析器自己再去解析一遍代理键，
 * 等于把"字符串 ↔ 代理键"这条桥接规则又抄一份。
 */
public record RelationCatalog(List<SecurityIdentity> securities, List<SectorIdentity> sectors) {

    public RelationCatalog {
        securities = List.copyOf(securities);
        sectors = List.copyOf(sectors);
    }

    public static RelationCatalog empty() {
        return new RelationCatalog(List.of(), List.of());
    }

    public Optional<SecurityIdentity> securityByCode(String securityCode) {
        if (securityCode == null) {
            return Optional.empty();
        }
        return securities.stream()
                .filter(identity -> securityCode.equals(identity.summary().securityCode()))
                .findFirst();
    }

    public Optional<SectorIdentity> sectorById(String sectorId) {
        if (sectorId == null) {
            return Optional.empty();
        }
        return sectors.stream()
                .filter(identity -> sectorId.equals(identity.sectorId()))
                .findFirst();
    }

    public Optional<SecurityIdentity> securityById(String securityId) {
        if (securityId == null) {
            return Optional.empty();
        }
        return securities.stream()
                .filter(identity -> securityId.equals(identity.securityId()))
                .findFirst();
    }
}
