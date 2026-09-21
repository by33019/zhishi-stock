package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiContextTarget;
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
 * 「一份请求是否合法、它指向什么」的唯一实现。
 *
 * <h2>为什么必须抽出来</h2>
 * AI-02（预览）与 AI-03（创建）接收的是**同一份**场景 / 区间 / 目标参数。
 * 复制一份解析逻辑的后果是"什么请求算合法"出现两份知识，而口径分歧**不会报错**：
 * 预览说可以生成、创建时说目标非法，用户看到的是"按钮能点但点了报错"。
 * 这正是 M2-10 立下的规矩——同一个事实只允许一处实现。
 *
 * <h2>校验顺序是接口的一部分</h2>
 * 场景 → 区间 → 目标数量 → 逐个目标（类型 → 角色 → 重复 → 主数据解析）→ 主目标唯一性。
 * 顺序固定才能让同一份非法请求稳定地报同一条错误，否则前端提示会在两次提交之间跳动。
 * 数量先于逐项检查，是为了让"传了 4 个目标"直接得到数量错误，
 * 而不是先抱怨第 4 个目标解析不到。
 *
 * <h2>校验失败抛异常，数据缺失不抛</h2>
 * 目标或区间不合法 → 400（请求错了，用户要改）。
 * 核心行情缺失不在本类处理：那是**数据**问题，预览返回 {@code canGenerate=false}、
 * 创建返回 503，两者对"错在请求"与"错在数据"的区分是一致的。
 */
public class AiTaskRequestResolver {

    private final AiSceneCatalog catalog;
    private final SecurityIdentityProvider securities;
    private final SectorIdentityProvider sectors;

    public AiTaskRequestResolver(
            AiSceneCatalog catalog,
            SecurityIdentityProvider securities,
            SectorIdentityProvider sectors) {
        this.catalog = catalog;
        this.securities = securities;
        this.sectors = sectors;
    }

    /**
     * 解析一份请求。
     *
     * @throws InvalidAiContextQueryException 场景码或分析区间不合法（→ 400）
     * @throws InvalidAiTargetException 目标违反场景规则（→ 400，业务码 {@code AI_TARGET_INVALID}）
     */
    public AiResolvedRequest resolve(
            String scene,
            List<AiTargetRequest> targets,
            OffsetDateTime analysisStartAt,
            OffsetDateTime analysisEndAt) {
        AiSceneDefinition definition = definitionOf(scene);
        validateRange(analysisStartAt, analysisEndAt, definition);
        List<AiContextTarget> resolved = resolveTargets(targets, definition);
        return new AiResolvedRequest(
                definition.scene(), definition, resolved, analysisStartAt, analysisEndAt);
    }

    /**
     * 场景码 → 场景定义。
     *
     * <p>"码不认识"与"目录里没配这个场景"都归到同一个错误：对客户端而言两者都是
     * "这个场景不能用"。但**不能**返回兜底场景——那会让用户看到一个他并没有选择的场景。
     */
    public AiSceneDefinition definitionOf(String scene) {
        AiScene parsed = AiScene.fromCode(scene)
                .orElseThrow(() -> InvalidAiContextQueryException.unknownScene(scene));
        return catalog.find(parsed)
                .orElseThrow(() -> InvalidAiContextQueryException.unknownScene(scene));
    }

    /**
     * 问题长度校验（仅创建 / 追问入口调用，预览没有这个问题字段）。
     *
     * <p>上限取自场景定义而不是写死 500：将来某个场景收窄上限时，
     * 写死的那一份不会跟着变，而表现是"数据库拒绝了但接口说没问题"。
     */
    public void validateQuestion(AiSceneDefinition definition, String question) {
        if (question == null || question.isBlank()) {
            return;
        }
        int max = definition.questionMaxLength();
        if (question.length() > max) {
            throw InvalidAiContextQueryException.invalid(
                    "问题长度 " + question.length() + " 超过上限 " + max + " 个字符");
        }
    }

    /**
     * 区间校验：成对、有序、不超上限。
     *
     * <p>"只给一个端点"必须拒绝而不是补默认值：半截区间的语义没有定义
     * （是"从该时刻到现在"还是"只分析该时刻"？），猜一个就是编造。
     *
     * <p>公开是为了让 AI-07（重试）与 AI-08（追问）能对**覆盖后的区间**复用同一份校验：
     * 那两个入口不重新解析目标（它们复用已固化的目标），但区间是客户端可以改的，
     * 改完必须走同一个判据。另写一份判据会让"预览说超限、重试却放行"成为可能。
     */
    public void validateRange(
            OffsetDateTime start, OffsetDateTime end, AiSceneDefinition definition) {
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
}
