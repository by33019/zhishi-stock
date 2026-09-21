package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiContextTarget;
import cn.zhishi.stock.ai.domain.AiTargetType;
import cn.zhishi.stock.market.domain.SectorIdentity;
import cn.zhishi.stock.market.domain.SectorIdentityProvider;
import cn.zhishi.stock.market.domain.SecurityIdentity;
import cn.zhishi.stock.market.domain.SecurityIdentityProvider;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把任务目标还原成带**对外标识**的形状。
 *
 * <h2>为什么必须有这么一个东西</h2>
 * {@code ai_task_target} 只存 bigint 代理键与代码 / 名称快照，**不存对外标识**
 * （{@code sim-600519}）——那正是「库里只有一种 ID」的代价。于是任何
 * "从库里读回任务、然后拿它的目标去取数或对外展示"的地方，都会拿到一份
 * {@code targetId} 为空的目标。
 *
 * <p>这份还原逻辑原先只写在 {@code AiTaskService} 里，于是：
 * <ul>
 *   <li>创建与查询（走它）是好的；
 *   <li><b>执行器</b>（没走它）把空标识喂给 {@code AiContextBuilder}，
 *       {@code batch.snapshotOf(null)} 在不可变 Map 上抛
 *       {@code NullPointerException: Cannot invoke "Object.hashCode()" because "pk" is null}，
 *       任务以 {@code AI_CONTEXT_BUILD_FAILED} 失败——**每一个任务都会失败**；
 *   <li><b>重试与追问</b>（把 {@code original.targets()} 原样往下传）同样没走它，
 *       会在核心行情检查那一步失败。
 * </ul>
 * 三类缺陷都不会让单测变红：单测里的 {@code AiContextBuilder} 是 Mockito 桩，
 * 无论喂进去什么目标都返回同一份预置结果。所以这里把它收成**唯一**实现，
 * 并让上面三处都走它。
 *
 * <h2>还原必须走 IdentityProvider，不能拼字符串</h2>
 * 在存储层或这里拼 {@code "sim-" + code} 就是第二份构词规则，
 * 而两份规则分歧不会报错，只会让前端拼出的跳转链接 404（M2-06 / M2-11 各踩过一次）。
 *
 * <p>主数据里查不到的（如已退市的证券）保留在列表里、对外标识留 {@code null}：
 * 契约要求降级不失败，而前端只在有标识时才渲染跳转链接。
 */
public class AiTargetHydrator {

    private final SecurityIdentityProvider securities;
    private final SectorIdentityProvider sectors;

    public AiTargetHydrator(
            SecurityIdentityProvider securities, SectorIdentityProvider sectors) {
        this.securities = securities;
        this.sectors = sectors;
    }

    public List<AiContextTarget> hydrate(List<AiContextTarget> targets) {
        Set<Long> securityIds = new LinkedHashSet<>();
        Set<Long> sectorIds = new LinkedHashSet<>();
        for (AiContextTarget target : targets) {
            if (target.storageId() == null) {
                continue;
            }
            switch (target.targetType()) {
                case SECURITY -> securityIds.add(target.storageId());
                case SECTOR -> sectorIds.add(target.storageId());
                case MARKET -> {
                    // 无需查询：市场目标的对外标识就是市场代码本身
                }
            }
        }
        Map<Long, SecurityIdentity> resolvedSecurities = securities.findByStorageIds(securityIds);
        Map<Long, SectorIdentity> resolvedSectors = sectors.findByStorageIds(sectorIds);

        List<AiContextTarget> hydrated = new ArrayList<>(targets.size());
        for (AiContextTarget target : targets) {
            hydrated.add(switch (target.targetType()) {
                case SECURITY -> new AiContextTarget(
                        AiTargetType.SECURITY,
                        identityOf(resolvedSecurities.get(target.storageId())),
                        target.targetCode(),
                        target.targetName(),
                        target.targetRole(),
                        target.storageId());
                case SECTOR -> new AiContextTarget(
                        AiTargetType.SECTOR,
                        identityOf(resolvedSectors.get(target.storageId())),
                        target.targetCode(),
                        target.targetName(),
                        target.targetRole(),
                        target.storageId());
                case MARKET -> new AiContextTarget(
                        AiTargetType.MARKET,
                        target.targetCode(),
                        target.targetCode(),
                        target.targetName(),
                        target.targetRole(),
                        target.storageId());
            });
        }
        return List.copyOf(hydrated);
    }

    /** 主数据里没有这个代理键时返回 {@code null}——降级保留，不编造标识。 */
    private static String identityOf(SecurityIdentity identity) {
        return identity == null ? null : identity.securityId();
    }

    private static String identityOf(SectorIdentity identity) {
        return identity == null ? null : identity.sectorId();
    }
}
