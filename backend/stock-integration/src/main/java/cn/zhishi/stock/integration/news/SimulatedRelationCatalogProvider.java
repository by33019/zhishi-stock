package cn.zhishi.stock.integration.news;

import cn.zhishi.stock.market.domain.Sector;
import cn.zhishi.stock.market.domain.SectorIdentity;
import cn.zhishi.stock.market.domain.SectorIdentityProvider;
import cn.zhishi.stock.market.domain.SectorProvider;
import cn.zhishi.stock.market.domain.SecurityIdentity;
import cn.zhishi.stock.market.domain.SecurityIdentityProvider;
import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecuritySummary;
import cn.zhishi.stock.news.domain.RelationCatalog;
import cn.zhishi.stock.news.domain.RelationCatalogProvider;
import java.util.List;
import java.util.Map;

/**
 * 由模拟主数据装配关联解析目录。
 *
 * <p>证券与板块的**身份解析**都委托给各自的 {@code *IdentityProvider}，
 * 本类不自己拼代理键——"字符串 ID ↔ bigint"的构词规则只允许有一处定义，
 * 抄一份到这里就会在某个将来悄悄指向另一个标的，且不会有测试变红。
 */
public class SimulatedRelationCatalogProvider implements RelationCatalogProvider {

    private static final String MARKET_CODE = "CN";

    private final SecurityMasterProvider securityMasterProvider;
    private final SecurityIdentityProvider securityIdentityProvider;
    private final SectorProvider sectorProvider;
    private final SectorIdentityProvider sectorIdentityProvider;

    public SimulatedRelationCatalogProvider(
            SecurityMasterProvider securityMasterProvider,
            SecurityIdentityProvider securityIdentityProvider,
            SectorProvider sectorProvider,
            SectorIdentityProvider sectorIdentityProvider) {
        this.securityMasterProvider = securityMasterProvider;
        this.securityIdentityProvider = securityIdentityProvider;
        this.sectorProvider = sectorProvider;
        this.sectorIdentityProvider = sectorIdentityProvider;
    }

    @Override
    public RelationCatalog load() {
        List<SecuritySummary> securities = securityMasterProvider.findAll(MARKET_CODE);
        Map<String, SecurityIdentity> securityIdentities = securityIdentityProvider.resolveAll(
                securities.stream().map(SecuritySummary::securityId).toList());

        List<Sector> sectors = sectorProvider.findAll(MARKET_CODE);
        Map<String, SectorIdentity> sectorIdentities = sectorIdentityProvider.resolveAll(
                sectors.stream().map(Sector::sectorId).toList());

        return new RelationCatalog(
                List.copyOf(securityIdentities.values()),
                List.copyOf(sectorIdentities.values()));
    }
}
