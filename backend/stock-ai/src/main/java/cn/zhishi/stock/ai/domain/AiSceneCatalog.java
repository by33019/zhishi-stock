package cn.zhishi.stock.ai.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI 场景目录（AI-01 的数据源）。
 *
 * <h2>为什么是具体类而不是端口</h2>
 * 它是一份**纯静态规则表**：没有外部依赖、不需要按环境替换实现。
 * 为"将来可能配置化"提前引入接口，只会多一层没人替换的间接。
 * 与 {@code TradingSessions}（纯函数工具类）同一处理方式。
 * 真正需要可变的是**启用哪些场景**（契约 §13.1「返回当前启用的场景」），
 * 那是运营配置，属于 M3-11 的后台能力——届时这里会变成从配置读，而不是现在猜一个接口形状。
 *
 * <h2>规则的唯一来源</h2>
 * 五个场景的目标类型、目标数量区间来自契约 §13.1 的「场景目标规则」表；
 * 问题长度上限 500 同时出现在 PRD（「0-500 字问题」）与
 * {@code ai_task} 的 {@code ck_ai_task_question_length}；
 * 分析区间档位来自 PRD「1/5/20 个交易日或自定义范围」「自定义范围最长 1 年」。
 * 这些数字**只在这里写一次**，测试逐行钉住——改规则必须同步改测试，
 * 不会出现"实现悄悄放宽了约束而没人发现"。
 */
public class AiSceneCatalog {

    /** 全部场景共用的区间档位。共用一份，避免各场景各有一套默认值。 */
    private static final AiAnalysisRange STANDARD_RANGE = new AiAnalysisRange(
            List.of(
                    AiAnalysisRange.Preset.LAST_1_TRADING_DAY,
                    AiAnalysisRange.Preset.LAST_5_TRADING_DAYS,
                    AiAnalysisRange.Preset.LAST_20_TRADING_DAYS),
            AiAnalysisRange.Preset.LAST_5_TRADING_DAYS,
            365);

    private static final int QUESTION_MAX_LENGTH = 500;

    private static final List<AiSceneDefinition> DEFINITIONS = List.of(
            new AiSceneDefinition(
                    AiScene.MARKET,
                    "市场解读",
                    "解读市场整体表现、广度结构与资金流向，说明当前环境的主要特征与不确定性。",
                    List.of(AiTargetType.MARKET),
                    1,
                    1,
                    STANDARD_RANGE,
                    QUESTION_MAX_LENGTH),
            new AiSceneDefinition(
                    AiScene.SECTOR,
                    "板块解读",
                    "解释板块热度来源、成分股贡献与集中度风险，区分板块事实与个股线索。",
                    List.of(AiTargetType.SECTOR),
                    1,
                    1,
                    STANDARD_RANGE,
                    QUESTION_MAX_LENGTH),
            new AiSceneDefinition(
                    AiScene.STOCK,
                    "个股研究",
                    "结合区间行情、量价特征与已确认的资讯事件，研究单只证券近期表现。",
                    List.of(AiTargetType.SECURITY),
                    1,
                    1,
                    STANDARD_RANGE,
                    QUESTION_MAX_LENGTH),
            new AiSceneDefinition(
                    AiScene.STOCK_RISK,
                    "风险梳理",
                    "围绕波动、流动性与事件线索梳理单只证券面临的风险与反例，不给出买卖建议。",
                    List.of(AiTargetType.SECURITY),
                    1,
                    1,
                    STANDARD_RANGE,
                    QUESTION_MAX_LENGTH),
            new AiSceneDefinition(
                    AiScene.COMPARE,
                    "多标的对比",
                    "在统一区间与数据截止时点下比较 2 至 3 只证券的异同，明确数据缺失与不可比之处。",
                    List.of(AiTargetType.SECURITY),
                    2,
                    3,
                    STANDARD_RANGE,
                    QUESTION_MAX_LENGTH));

    private static final Map<AiScene, AiSceneDefinition> BY_SCENE = index(DEFINITIONS);

    /** 全部场景定义，顺序与 {@link AiScene} 的声明顺序一致（前端标签顺序由此固定）。 */
    public List<AiSceneDefinition> definitions() {
        return DEFINITIONS;
    }

    /**
     * 按场景取定义。
     *
     * <p>未知场景返回空而不是抛异常或返回兜底场景：调用方（请求解析）需要区分
     * "客户端传了一个不存在的场景"与"服务端漏配了某个场景"，
     * 而返回兜底场景会把后者伪装成前者——用户会看到一个他并没有选择的场景。
     */
    public Optional<AiSceneDefinition> find(AiScene scene) {
        if (scene == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_SCENE.get(scene));
    }

    private static Map<AiScene, AiSceneDefinition> index(List<AiSceneDefinition> definitions) {
        Map<AiScene, AiSceneDefinition> index = new LinkedHashMap<>();
        for (AiSceneDefinition definition : definitions) {
            index.put(definition.scene(), definition);
        }
        return Map.copyOf(index);
    }
}
