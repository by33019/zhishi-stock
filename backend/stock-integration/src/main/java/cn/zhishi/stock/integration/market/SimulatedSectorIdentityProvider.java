package cn.zhishi.stock.integration.market;

import cn.zhishi.stock.market.domain.Sector;
import cn.zhishi.stock.market.domain.SectorIdentity;
import cn.zhishi.stock.market.domain.SectorIdentityProvider;
import cn.zhishi.stock.market.domain.SectorProvider;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 确定性模拟的板块身份解析：代理键就是板块序号。
 *
 * <p>索引**投影**自 {@link SectorProvider}，不重新生成板块全集——板块主数据与资讯关联
 * 两处对同一个板块的 {@code sectorId} 因此必然一致。
 *
 * <p>代理键取序号（{@code 1} 起）而不是另生成一个 Snowflake：板块主数据与证券主数据一样
 * **不落库**（{@code stock_sector} 继续空置），库里没有 {@code stock_sector.id} 可用；
 * 序号是板块集合里唯一且稳定的自然键，可读、可调试，且不引入任何新的生成逻辑。
 * 真实板块数据接入时应改用 {@code stock_sector.id}，届时只需替换本类并做一次数据迁移。
 *
 * <p>构词规则（前缀与宽度）统一在 {@link SimulatedSectorIds}，与产出方共用同一份定义。
 */
public class SimulatedSectorIdentityProvider implements SectorIdentityProvider {

    /** 板块主数据是市场无关的全集，与 {@code SectorProvider} 的既有调用口径一致。 */
    private static final String MARKET_CODE = "CN";

    private final SectorProvider sectorProvider;

    public SimulatedSectorIdentityProvider(SectorProvider sectorProvider) {
        this.sectorProvider = sectorProvider;
    }

    @Override
    public Optional<SectorIdentity> resolve(String sectorId) {
        if (SimulatedSectorIds.storageIdOf(sectorId).isEmpty()) {
            // 格式不认识就直接拒绝，不去索引里"碰运气"——宁可让调用方收到 404，
            // 也不要接受一个将来可能撞上别的板块的字符串。
            return Optional.empty();
        }
        return Optional.ofNullable(index().get(sectorId));
    }

    @Override
    public Map<String, SectorIdentity> resolveAll(Collection<String> sectorIds) {
        if (sectorIds == null || sectorIds.isEmpty()) {
            return Map.of();
        }
        Map<String, SectorIdentity> index = index();
        Map<String, SectorIdentity> resolved = new LinkedHashMap<>();
        for (String sectorId : sectorIds) {
            SectorIdentity identity = index.get(sectorId);
            if (identity != null) {
                resolved.put(sectorId, identity);
            }
        }
        return Map.copyOf(resolved);
    }

    @Override
    public Map<Long, SectorIdentity> findByStorageIds(Collection<Long> storageIds) {
        if (storageIds == null || storageIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, SectorIdentity> byStorage = indexByStorageId();
        Map<Long, SectorIdentity> resolved = new LinkedHashMap<>();
        for (Long storageId : storageIds) {
            SectorIdentity identity = storageId == null ? null : byStorage.get(storageId);
            if (identity != null) {
                resolved.put(storageId, identity);
            }
        }
        return Map.copyOf(resolved);
    }

    private Map<String, SectorIdentity> index() {
        Map<String, SectorIdentity> index = new HashMap<>();
        for (Sector sector : sectorProvider.findAll(MARKET_CODE)) {
            index.put(sector.sectorId(), identityOf(sector));
        }
        return index;
    }

    private Map<Long, SectorIdentity> indexByStorageId() {
        Map<Long, SectorIdentity> index = new HashMap<>();
        for (SectorIdentity identity : index().values()) {
            index.put(identity.storageId(), identity);
        }
        return index;
    }

    /**
     * 主数据给出的 {@code sectorId} 必须能被反解成代理键，否则就是模拟源自身的缺陷。
     *
     * <p>与 {@link SimulatedSecurityIdentityProvider} 一样刻意抛异常而不是跳过：
     * 跳过会让"某个板块永远无法关联资讯"变成一个静默的空白。
     */
    private static SectorIdentity identityOf(Sector sector) {
        long storageId = SimulatedSectorIds.storageIdOf(sector.sectorId())
                .orElseThrow(() -> new IllegalStateException(
                        "板块主数据给出了无法解析的 sectorId：" + sector.sectorId()));
        return new SectorIdentity(storageId, sector);
    }
}
