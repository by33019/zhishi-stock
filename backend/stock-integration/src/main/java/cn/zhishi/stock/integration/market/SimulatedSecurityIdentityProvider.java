package cn.zhishi.stock.integration.market;

import cn.zhishi.stock.market.domain.SecurityIdentity;
import cn.zhishi.stock.market.domain.SecurityIdentityProvider;
import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecuritySummary;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 确定性模拟的证券身份解析：代理键就是证券代码本身。
 *
 * <p>索引**投影**自 {@link SecurityMasterProvider}，不重新生成证券全集——
 * 主数据、行情、板块成分三处对同一只证券的 {@code securityId} 因此必然一致。
 *
 * <p>代理键取证券代码（{@code 600519}）而不是另生成一个 Snowflake：
 * 证券主数据按 M2-04 的取舍**不落库**，库里没有 {@code stock_security.id} 可用；
 * 代码是主数据里唯一且稳定的自然键，可读、可调试，且不引入任何新的生成逻辑。
 * 真实主数据接入时应改用 {@code stock_security.id}，届时只需替换本类并做一次数据迁移。
 *
 * <p>构词规则（前缀与代码宽度）统一在 {@link SimulatedSecurityIds}，与产出方共用同一份定义。
 */
public class SimulatedSecurityIdentityProvider implements SecurityIdentityProvider {

    /** 证券主数据是市场无关的全集，与 {@code SecurityQueryService} 同口径。 */
    private static final String MARKET_CODE = "CN";

    private final SecurityMasterProvider securityMasterProvider;

    public SimulatedSecurityIdentityProvider(SecurityMasterProvider securityMasterProvider) {
        this.securityMasterProvider = securityMasterProvider;
    }

    @Override
    public Optional<SecurityIdentity> resolve(String securityId) {
        if (SimulatedSecurityIds.storageIdOf(securityId).isEmpty()) {
            // 格式不认识就直接拒绝，不去索引里"碰运气"——宁可让调用方收到 404，
            // 也不要接受一个将来可能撞上别的证券的字符串。
            return Optional.empty();
        }
        return Optional.ofNullable(index().get(securityId));
    }

    @Override
    public Map<String, SecurityIdentity> resolveAll(Collection<String> securityIds) {
        if (securityIds == null || securityIds.isEmpty()) {
            return Map.of();
        }
        Map<String, SecurityIdentity> index = index();
        Map<String, SecurityIdentity> resolved = new LinkedHashMap<>();
        for (String securityId : securityIds) {
            SecurityIdentity identity = index.get(securityId);
            if (identity != null) {
                resolved.put(securityId, identity);
            }
        }
        return Map.copyOf(resolved);
    }

    @Override
    public Map<Long, SecurityIdentity> findByStorageIds(Collection<Long> storageIds) {
        if (storageIds == null || storageIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, SecurityIdentity> byStorage = indexByStorageId();
        Map<Long, SecurityIdentity> resolved = new LinkedHashMap<>();
        for (Long storageId : storageIds) {
            SecurityIdentity identity = storageId == null ? null : byStorage.get(storageId);
            if (identity != null) {
                resolved.put(storageId, identity);
            }
        }
        return Map.copyOf(resolved);
    }

    /**
     * 每次调用重建索引：模拟主数据每次都是同一份，索引本身不构成需要缓存的状态。
     *
     * <p>不做缓存还有一层原因——真实主数据是会变的，一旦缓存就得处理失效；
     * 而现在 5149 条的投影成本与 STK-01 搜索同量级，不值得引入缓存这个新状态。
     */
    private Map<String, SecurityIdentity> index() {
        Map<String, SecurityIdentity> index = new HashMap<>();
        for (SecuritySummary summary : securityMasterProvider.findAll(MARKET_CODE)) {
            index.put(summary.securityId(), identityOf(summary));
        }
        return index;
    }

    private Map<Long, SecurityIdentity> indexByStorageId() {
        Map<Long, SecurityIdentity> index = new HashMap<>();
        for (SecurityIdentity identity : index().values()) {
            index.put(identity.storageId(), identity);
        }
        return index;
    }

    /**
     * 主数据给出的 {@code securityId} 必须能被反解成代理键，否则就是模拟源自身的缺陷。
     *
     * <p>这里刻意抛异常而不是跳过：跳过会让"某只证券永远无法加入自选"变成一个静默的空白，
     * 而 {@link SimulatedSecurityIdentityProviderTest} 会对全量 5149 只断言可往返。
     */
    private static SecurityIdentity identityOf(SecuritySummary summary) {
        long storageId = SimulatedSecurityIds.storageIdOf(summary.securityId())
                .orElseThrow(() -> new IllegalStateException(
                        "证券主数据给出了无法解析的 securityId：" + summary.securityId()));
        return new SecurityIdentity(storageId, summary);
    }
}
