package cn.zhishi.stock.admin.application;

import cn.zhishi.stock.admin.domain.AdminNewsRelationEntry;
import cn.zhishi.stock.admin.domain.AdminNewsRelationQuery;
import cn.zhishi.stock.admin.domain.AdminNewsRelationStore;
import cn.zhishi.stock.admin.domain.AdminNewsRelationView;
import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.market.domain.SectorIdentity;
import cn.zhishi.stock.market.domain.SectorIdentityProvider;
import cn.zhishi.stock.market.domain.SecurityIdentity;
import cn.zhishi.stock.market.domain.SecurityIdentityProvider;
import cn.zhishi.stock.news.domain.NewsMarketTargets;
import cn.zhishi.stock.news.domain.NewsRelationStatus;
import cn.zhishi.stock.news.domain.NewsTargetType;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资讯关联审核用例（契约 §17.2 ADM-NEWS-05~08）。
 *
 * <h2>默认查看 CANDIDATE</h2>
 * 关联审核的工作对象是低置信候选（规则 R4 / R6 产出的
 * {@code confidence < 0.70}），因此查询条件的缺省状态是 {@code CANDIDATE}
 * 而不是"全部"。要翻已确认或已拒绝的历史，显式传对应状态——让最常见的
 * 操作路径最短，把少见路径留在参数里。
 *
 * <h2>目标标识的换算只在边界发生</h2>
 * 表里的 {@code target_id} 是 bigint 代理键；入参（ADM-NEWS-07）与出参
 * （ADM-NEWS-05/06/08）都是对外标识。SECURITY / SECTOR 走行情域的身份端口，
 * MARKET 走资讯域的编码规则（{@code NewsMarketTargets}）——与前台资讯查询
 * 是同一套桥接，后台不复述"sim- 前缀"这类构词知识。
 *
 * <h2>审核后刷新前台缓存？没有缓存可刷</h2>
 * 契约 §17.2 ADM-NEWS-06 提到"审核后刷新相关前台资讯缓存"。资讯域自
 * M3-04 起的既定决策是**不建缓存**（直读 MySQL），前台的下一次查询自然
 * 看到复核结果。这里无需任何失效动作，注释即实现。
 */
public class AdminNewsRelationService {

    private final AdminNewsRelationStore store;
    private final SecurityIdentityProvider securityIdentities;
    private final SectorIdentityProvider sectorIdentities;
    private final Clock clock;

    public AdminNewsRelationService(
            AdminNewsRelationStore store,
            SecurityIdentityProvider securityIdentities,
            SectorIdentityProvider sectorIdentities,
            Clock clock) {
        this.store = store;
        this.securityIdentities = securityIdentities;
        this.sectorIdentities = sectorIdentities;
        this.clock = clock;
    }

    /** ADM-NEWS-05：关联分页，缺省看 CANDIDATE，按创建时间倒序。 */
    public PageData<AdminNewsRelationView> list(AdminNewsRelationQuery query) {
        AdminNewsRelationQuery resolved = withResolvedDefaults(query);
        long total = store.count(resolved);
        List<AdminNewsRelationEntry> entries =
                total == 0 ? List.of() : store.page(resolved);
        List<AdminNewsRelationView> views = entries.stream().map(this::toView).toList();
        return PageData.of(views, resolved.page(), resolved.size(), total);
    }

    /** ADM-NEWS-06：人工确认或拒绝候选关系。 */
    @Transactional
    public AdminNewsRelationView review(
            long relationId,
            NewsRelationStatus decision,
            String reasonSummary,
            long reviewerId) {
        if (decision == null || decision == NewsRelationStatus.CANDIDATE) {
            throw AdminException.invalidRequest(
                    "复核结论只能是 CONFIRMED 或 REJECTED，收到：CANDIDATE");
        }
        AdminNewsRelationEntry current = requireRelation(relationId);
        if (current.reviewedAt() != null) {
            throw AdminException.newsRelationAlreadyReviewed(
                    relationId, current.relationStatus().name());
        }
        store.review(relationId, decision, blankToNull(reasonSummary), reviewerId, now());
        return toView(requireRelation(relationId));
    }

    /** ADM-NEWS-07：手工建立可解释关联（MANUAL + CONFIRMED，无机器分数）。 */
    @Transactional
    public AdminNewsRelationView createManual(
            long newsId,
            NewsTargetType targetType,
            String targetId,
            String reasonSummary,
            long reviewerId) {
        if (targetType == null) {
            throw AdminException.invalidRequest("targetType 不能为空");
        }
        if (targetId == null || targetId.isBlank()) {
            throw AdminException.invalidRequest("targetId 不能为空");
        }
        if (!store.newsExists(newsId)) {
            throw AdminException.newsNotFound(newsId);
        }
        AdminNewsRelationStore.AdminNewsRelationTarget target =
                new AdminNewsRelationStore.AdminNewsRelationTarget(
                        targetType, requireStorageId(targetType, targetId));
        try {
            return toView(store.insertManual(
                    newsId, target, blankToNull(reasonSummary), reviewerId, now()));
        } catch (DuplicateKeyException exception) {
            // 同一新闻 + 同一目标已有关联（含复核拒绝过的）：契约要求幂等成功，
            // 返回已有一条，而不是让"重复点击"变成 409。
            return store.findByTarget(newsId, targetType, target.targetId())
                    .map(this::toView)
                    .orElseThrow(() -> AdminException.invalidRequest("关联创建失败，请重试"));
        }
    }

    /**
     * ADM-NEWS-08：删除关联 = 置 REJECTED（保留审计，不物理删除）。
     *
     * <p>已经是 REJECTED 的关联再删一次是幂等成功——同样的删除意图重复到达，
     * 答案都是"它现在不在生效关联里"。已 CONFIRMED 的关联被删除会留下
     * 复核人/时间/理由，这正是"保留审计优先"的字面执行。
     */
    @Transactional
    public AdminNewsRelationView delete(long relationId, String reasonSummary, long reviewerId) {
        AdminNewsRelationEntry current = requireRelation(relationId);
        if (current.relationStatus() != NewsRelationStatus.REJECTED) {
            store.review(
                    relationId, NewsRelationStatus.REJECTED,
                    blankToNull(reasonSummary), reviewerId, now());
        }
        return toView(requireRelation(relationId));
    }

    // ---------- 内部 ----------

    private AdminNewsRelationEntry requireRelation(long relationId) {
        return store.find(relationId)
                .orElseThrow(() -> AdminException.newsRelationNotFound(relationId));
    }

    /** 对外标识 → 关联表代理键；解析不到一律报错（不存在"存个空关联"的形态）。 */
    private long requireStorageId(NewsTargetType targetType, String targetId) {
        return switch (targetType) {
            case SECURITY -> securityIdentities.resolve(targetId)
                    .map(SecurityIdentity::storageId)
                    .orElseThrow(() -> AdminException.newsRelationTargetInvalid(targetId));
            case SECTOR -> sectorIdentities.resolve(targetId)
                    .map(SectorIdentity::storageId)
                    .orElseThrow(() -> AdminException.newsRelationTargetInvalid(targetId));
            case MARKET -> NewsMarketTargets.storageIdOf(targetId)
                    .orElseThrow(() -> AdminException.newsRelationTargetInvalid(targetId));
        };
    }

    /** 条目 → 对外视图：目标三件套按类型批量解析，解析不出保持 null（不编造）。 */
    private AdminNewsRelationView toView(AdminNewsRelationEntry entry) {
        AdminNewsRelationView view = AdminNewsRelationView.of(entry);
        return switch (entry.targetType()) {
            case SECURITY -> securityIdentities
                    .findByStorageIds(Set.of(entry.targetId()))
                    .values().stream().findFirst()
                    .map(identity -> view.withTarget(
                            identity.securityId(),
                            identity.summary().securityCode(),
                            identity.summary().securityName()))
                    .orElse(view);
            case SECTOR -> sectorIdentities
                    .findByStorageIds(Set.of(entry.targetId()))
                    .values().stream().findFirst()
                    .map(identity -> view.withTarget(
                            identity.sectorId(),
                            identity.sector().sectorCode(),
                            identity.sector().sectorName()))
                    .orElse(view);
            case MARKET -> NewsMarketTargets.marketCodeOf(entry.targetId())
                    .map(marketCode -> view.withTarget(marketCode, marketCode, marketCode))
                    .orElse(view);
        };
    }

    private AdminNewsRelationQuery withResolvedDefaults(AdminNewsRelationQuery query) {
        AdminNewsRelationQuery withStatus = query.relationStatus() == null
                ? new AdminNewsRelationQuery(
                        NewsRelationStatus.CANDIDATE, query.targetType(), query.newsId(),
                        query.minConfidence(), query.startedAt(), query.endedAt(),
                        query.page(), query.size())
                : query;

        OffsetDateTime end = withStatus.endedAt() == null
                ? OffsetDateTime.now(clock)
                : withStatus.endedAt();
        OffsetDateTime start = withStatus.startedAt() == null
                ? end.minusDays(AdminNewsRelationQuery.DEFAULT_RANGE_DAYS)
                : withStatus.startedAt();
        if (end.isBefore(start)) {
            throw AdminException.invalidRequest("结束时间不能早于开始时间");
        }
        if (Duration.between(start, end).toDays() > AdminNewsRelationQuery.MAX_RANGE_DAYS) {
            throw AdminException.invalidRequest(
                    "关联查询跨度不能超过 " + AdminNewsRelationQuery.MAX_RANGE_DAYS + " 天");
        }
        return withStatus.withRange(start, end);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
