package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiContextBuildResult;
import cn.zhishi.stock.ai.domain.AiContextBuilder;
import cn.zhishi.stock.ai.domain.AiContextTarget;
import cn.zhishi.stock.ai.domain.AiEvidenceType;
import cn.zhishi.stock.ai.domain.AiScene;
import cn.zhishi.stock.ai.domain.AiSceneCatalog;
import cn.zhishi.stock.ai.domain.AiSceneDefinition;
import cn.zhishi.stock.ai.domain.AiTargetRole;
import cn.zhishi.stock.ai.domain.AiTargetType;
import cn.zhishi.stock.market.domain.Sector;
import cn.zhishi.stock.market.domain.SectorIdentity;
import cn.zhishi.stock.market.domain.SectorIdentityProvider;
import cn.zhishi.stock.market.domain.SecurityIdentity;
import cn.zhishi.stock.market.domain.SecurityIdentityProvider;
import cn.zhishi.stock.news.domain.NewsMarketTargets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AI-02 上下文预览用例：把一次请求翻译成「将使用哪些数据、能不能生成」。
 *
 * <h2>它只做三件事</h2>
 * <ol>
 *   <li><b>校验</b>：场景、区间、目标规则。契约 §13.1 的「场景目标规则」表在这里落地。</li>
 *   <li><b>解析</b>：把请求里的对外标识换成带主数据摘要的目标
 *       （{@link SecurityIdentityProvider} / {@link SectorIdentityProvider} / {@link NewsMarketTargets}）。
 *   <li><b>委托取数</b>：真正取数与固化交给 {@link AiContextBuilder}，
 *       本类**不碰行情、不碰资讯**，也不派生任何统计量。
 * </ol>
 *
 * <h2>校验失败抛异常，数据缺失不抛</h2>
 * 目标或区间不合法 → 400（请求错了，用户要改）。核心行情缺失 → {@code canGenerate=false}
 * 并给出 {@code limitations}。区分标准是"错在请求"还是"错在数据"：
 * 预览的用途正是「在正式消耗配额前展示将使用的数据摘要」（契约 §13.1），
 * 数据缺失时报 400 会让用户看不到"为什么不能生成"。
 *
 * <h2>校验顺序</h2>
 * 场景 → 区间 → 目标数量 → 逐个目标（类型 → 角色 → 重复 → 主数据解析）→ 主目标唯一性。
 * 顺序本身是接口的一部分：同一份非法请求必须稳定地报同一条错误，
 * 否则前端提示会在两次提交之间跳动。数量先于逐项检查，是为了让"传了 4 个目标"
 * 直接得到数量错误，而不是先抱怨第 4 个目标解析不到。
 */
public class AiContextPreviewService {

    private final AiSceneCatalog catalog;
    private final AiContextBuilder contextBuilder;
    private final SecurityIdentityProvider securities;
    private final SectorIdentityProvider sectors;

    public AiContextPreviewService(
            AiSceneCatalog catalog,
            AiContextBuilder contextBuilder,
            SecurityIdentityProvider securities,
            SectorIdentityProvider sectors) {
        this.catalog = catalog;
        this.contextBuilder = contextBuilder;
        this.securities = securities;
        this.sectors = sectors;
    }

    /**
     * 生成预览。
     *
     * @throws InvalidAiContextQueryException 场景码或分析区间不合法（→ 400）
     * @throws InvalidAiTargetException 目标违反场景规则（→ 400，业务码 {@code AI_TARGET_INVALID}）
     */
    public AiContextPreview preview(AiContextPreviewRequest request) {
        AiSceneDefinition definition = definitionOf(request.scene());
        validateRange(request, definition);
        List<AiContextTarget> targets = resolveTargets(request.targets(), definition);

        AiContextBuildResult result = contextBuilder.build(
                targets, request.analysisStartAt(), request.analysisEndAt());

        return new AiContextPreview(
                targets,
                result.dataCutoffs(),
                newsCountOf(result),
                result.limitations(),
                result.coreDataAvailable());
    }

    // ---------- 场景 ----------

    /**
     * 场景码 → 场景定义。
     *
     * <p>"码不认识"与"目录里没配这个场景"都归到同一个错误：对客户端而言两者都是
     * "这个场景不能用"。但**不能**返回兜底场景——那会让用户看到一个他并没有选择的场景。
     */
    private AiSceneDefinition definitionOf(String scene) {
        AiScene parsed = AiScene.fromCode(scene)
                .orElseThrow(() -> InvalidAiContextQueryException.unknownScene(scene));
        return catalog.find(parsed)
                .orElseThrow(() -> InvalidAiContextQueryException.unknownScene(scene));
    }

    // ---------- 区间 ----------

    /**
     * 区间校验：成对、有序、不超上限。
     *
     * <p>"只给一个端点"必须拒绝而不是补默认值：半截区间的语义没有定义
     * （是"从该时刻到现在"还是"只分析该时刻"？），猜一个就是编造。
     */
    private static void validateRange(AiContextPreviewRequest request, AiSceneDefinition definition) {
        OffsetDateTime start = request.analysisStartAt();
        OffsetDateTime end = request.analysisEndAt();
        if ((start == null) != (end == null)) {
            throw InvalidAiContextQueryException.invalidRange(
                    "分析区间必须成对给出：只给一个端点时，区间是从该时刻到现在还是只分析该时刻没有定义");
        }
        if (start == null) {
            return;
        }
        if (end.isBefore(start)) {
            throw InvalidAiContextQueryException.invalidRange(
                    "分析区间终点早于起点：" + end + " < " + start);
        }
        long spanDays = Duration.between(start, end).toDays();
        int maxCustomDays = definition.defaultRange().maxCustomDays();
        if (!definition.defaultRange().allowsCustomSpan(spanDays)) {
            throw InvalidAiContextQueryException.invalidRange(
                    "分析区间跨度为 " + spanDays + " 天，超过上限 " + maxCustomDays + " 天");
        }
    }

    // ---------- 目标 ----------

    private List<AiContextTarget> resolveTargets(
            List<AiTargetRequest> requests, AiSceneDefinition definition) {
        if (!definition.allowsTargetCount(requests.size())) {
            throw InvalidAiTargetException.invalid("场景 " + definition.scene()
                    + " 需要 " + definition.minTargets() + " 至 " + definition.maxTargets()
                    + " 个目标，实际 " + requests.size() + " 个");
        }

        List<AiContextTarget> targets = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int primaryCount = 0;
        for (AiTargetRequest item : requests) {
            AiTargetType targetType = AiTargetType.fromCode(item.targetType())
                    .orElseThrow(() -> InvalidAiTargetException.invalid(
                            "不支持的目标类型：" + item.targetType()));
            if (!definition.allowsTargetType(targetType)) {
                throw InvalidAiTargetException.invalid("场景 " + definition.scene()
                        + " 不接受 " + targetType + " 类型的目标");
            }
            AiTargetRole targetRole = AiTargetRole.fromCode(item.targetRole())
                    .orElseThrow(() -> InvalidAiTargetException.invalid(
                            "不支持的目标角色：" + item.targetRole()));
            // 契约 §13.1：CONTEXT 角色仅允许服务端生成
            if (targetRole == AiTargetRole.CONTEXT) {
                throw InvalidAiTargetException.invalid(
                        "目标角色 CONTEXT 仅允许服务端生成，不接受客户端传入");
            }
            if (targetRole == AiTargetRole.PRIMARY) {
                primaryCount++;
            }
            // 重复判据用「类型 + 标识」而不是只比标识：同一个 id 在不同类型下是不同对象，
            // 而 V6 的 uk_ai_task_target 正是这两列的组合
            if (!seen.add(targetType.name() + "/" + item.targetId())) {
                throw InvalidAiTargetException.invalid(
                        "目标重复出现：" + targetType + " " + item.targetId());
            }
            targets.add(resolveOne(targetType, item.targetId(), targetRole));
        }

        if (definition.requiresSinglePrimary() && primaryCount != 1) {
            throw InvalidAiTargetException.invalid("场景 " + definition.scene()
                    + " 需要恰好 1 个 PRIMARY 目标，实际 " + primaryCount + " 个");
        }
        return List.copyOf(targets);
    }

    /**
     * 解析单个目标：把对外标识换成主数据摘要。
     *
     * <p>解析不到就报 400，**不**降级成一个"名字为空"的目标：那会让用户在报告里
     * 看到一只没有名字的证券，而问题其实出在他传错了标识。
     *
     * <p>市场目标的代理键复用资讯域的 {@link NewsMarketTargets}：
     * "哪个市场代码合法"与"它对应哪个代理键"是同一个事实，两处各写一份必然分叉。
     */
    private AiContextTarget resolveOne(
            AiTargetType targetType, String targetId, AiTargetRole targetRole) {
        if (targetId == null || targetId.isBlank()) {
            throw InvalidAiTargetException.invalid("目标标识不得为空");
        }
        return switch (targetType) {
            case SECURITY -> {
                SecurityIdentity identity = securities.resolve(targetId)
                        .orElseThrow(() -> InvalidAiTargetException.invalid(
                                "证券标识无法解析：" + targetId));
                yield new AiContextTarget(
                        AiTargetType.SECURITY,
                        identity.securityId(),
                        identity.summary().securityCode(),
                        identity.summary().securityName(),
                        targetRole,
                        identity.storageId());
            }
            case SECTOR -> {
                SectorIdentity identity = sectors.resolve(targetId)
                        .orElseThrow(() -> InvalidAiTargetException.invalid(
                                "板块标识无法解析：" + targetId));
                Sector sector = identity.sector();
                yield new AiContextTarget(
                        AiTargetType.SECTOR,
                        identity.sectorId(),
                        sector.sectorCode(),
                        sector.sectorName(),
                        targetRole,
                        identity.storageId());
            }
            case MARKET -> {
                long storageId = NewsMarketTargets.storageIdOf(targetId)
                        .orElseThrow(() -> InvalidAiTargetException.invalid(
                                "不支持的市场代码：" + targetId));
                yield new AiContextTarget(
                        AiTargetType.MARKET,
                        NewsMarketTargets.CN,
                        NewsMarketTargets.CN,
                        NewsMarketTargets.CN,
                        targetRole,
                        storageId);
            }
        };
    }

    // ---------- 资讯条数 ----------

    /**
     * 可进入 AI 上下文的资讯条数。
     *
     * <p>由**已经构建好的证据**计数得出，不另起一次查询：另起一次就多了一个
     * "预览说 20 条、报告里只有 18 条"的窗口，而这类不一致不会报错。
     *
     * <p>公告也算资讯：它与媒体报道同属"事件线索"，契约 §13.5 把它们放在同一节里。
     */
    private static int newsCountOf(AiContextBuildResult result) {
        return (int) result.evidenceCandidates().stream()
                .filter(candidate -> candidate.evidenceType() == AiEvidenceType.NEWS
                        || candidate.evidenceType() == AiEvidenceType.ANNOUNCEMENT)
                .count();
    }
}
