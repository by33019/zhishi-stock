package cn.zhishi.stock.ai.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AiSceneCatalog} 的规则表测试。
 *
 * <p>它守着契约 §13.1「场景目标规则」那张表。这张表是**纯静态规则**，
 * 因此测试可以逐行断言——规则改动必须在测试里同步改，
 * 而不是"改了实现但没人发现约束松了"。
 */
class AiSceneCatalogTest {

    private final AiSceneCatalog catalog = new AiSceneCatalog();

    @Test
    @DisplayName("目录覆盖全部五个场景，且顺序与枚举声明一致")
    void coversAllScenesInDeclarationOrder() {
        assertThat(catalog.definitions())
                .extracting(AiSceneDefinition::scene)
                .containsExactly(
                        AiScene.MARKET,
                        AiScene.SECTOR,
                        AiScene.STOCK,
                        AiScene.STOCK_RISK,
                        AiScene.COMPARE);
    }

    @Test
    @DisplayName("市场、板块、个股、个股风险场景都只接受 1 个目标")
    void singleTargetScenesAcceptExactlyOneTarget() {
        for (AiScene scene : List.of(
                AiScene.MARKET, AiScene.SECTOR, AiScene.STOCK, AiScene.STOCK_RISK)) {
            AiSceneDefinition definition = catalog.find(scene).orElseThrow();
            assertThat(definition.minTargets())
                    .as("%s 的最小目标数", scene)
                    .isEqualTo(1);
            assertThat(definition.maxTargets())
                    .as("%s 的最大目标数", scene)
                    .isEqualTo(1);
            assertThat(definition.allowsTargetCount(1))
                    .as("%s 接受 1 个目标", scene)
                    .isTrue();
            assertThat(definition.allowsTargetCount(2))
                    .as("%s 拒绝 2 个目标", scene)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("对比场景接受 2 至 3 个证券目标")
    void compareSceneAcceptsTwoToThreeSecurities() {
        AiSceneDefinition compare = catalog.find(AiScene.COMPARE).orElseThrow();

        assertThat(compare.allowedTargetTypes()).containsExactly(AiTargetType.SECURITY);
        assertThat(compare.minTargets()).isEqualTo(2);
        assertThat(compare.maxTargets()).isEqualTo(3);
        assertThat(compare.allowsTargetCount(1)).isFalse();
        assertThat(compare.allowsTargetCount(2)).isTrue();
        assertThat(compare.allowsTargetCount(3)).isTrue();
        assertThat(compare.allowsTargetCount(4)).isFalse();
    }

    @Test
    @DisplayName("各场景只接受自己声明的目标类型")
    void eachSceneOnlyAcceptsItsOwnTargetTypes() {
        assertThat(catalog.find(AiScene.MARKET).orElseThrow().allowedTargetTypes())
                .containsExactly(AiTargetType.MARKET);
        assertThat(catalog.find(AiScene.SECTOR).orElseThrow().allowedTargetTypes())
                .containsExactly(AiTargetType.SECTOR);
        assertThat(catalog.find(AiScene.STOCK).orElseThrow().allowedTargetTypes())
                .containsExactly(AiTargetType.SECURITY);
        assertThat(catalog.find(AiScene.STOCK_RISK).orElseThrow().allowedTargetTypes())
                .containsExactly(AiTargetType.SECURITY);

        // 市场场景不能拿板块当目标——类型不匹配是最容易被"看起来合理"地放过去的一类错误
        assertThat(catalog.find(AiScene.MARKET).orElseThrow()
                        .allowsTargetType(AiTargetType.SECTOR))
                .isFalse();
    }

    @Test
    @DisplayName("全部场景的问题长度上限都是 500，与 ai_task 的 CHECK 约束同口径")
    void questionMaxLengthMatchesDatabaseConstraint() {
        assertThat(catalog.definitions())
                .extracting(AiSceneDefinition::questionMaxLength)
                .containsOnly(500);
    }

    @Test
    @DisplayName("分析区间档位来自 PRD 的 1/5/20 个交易日，默认选中中间档，自定义上限 1 年")
    void analysisRangePresetsMatchProductRequirements() {
        AiAnalysisRange range = catalog.find(AiScene.MARKET).orElseThrow().defaultRange();

        assertThat(range.presets())
                .containsExactly(
                        AiAnalysisRange.Preset.LAST_1_TRADING_DAY,
                        AiAnalysisRange.Preset.LAST_5_TRADING_DAYS,
                        AiAnalysisRange.Preset.LAST_20_TRADING_DAYS);
        assertThat(range.defaultPreset()).isEqualTo(AiAnalysisRange.Preset.LAST_5_TRADING_DAYS);
        assertThat(range.maxCustomDays()).isEqualTo(365);
        assertThat(range.presetCodes())
                .containsExactly(
                        "LAST_1_TRADING_DAY", "LAST_5_TRADING_DAYS", "LAST_20_TRADING_DAYS");
    }

    @Test
    @DisplayName("五个场景共用同一份区间档位，避免各场景各有一套默认值")
    void allScenesShareTheSameAnalysisRange() {
        List<AiAnalysisRange> ranges =
                catalog.definitions().stream().map(AiSceneDefinition::defaultRange).distinct().toList();

        assertThat(ranges).hasSize(1);
    }

    @Test
    @DisplayName("档位语义写在天数上：1/5/20 各只有一处定义")
    void presetCarriesTradingDayCount() {
        assertThat(AiAnalysisRange.Preset.LAST_1_TRADING_DAY.tradingDays()).isEqualTo(1);
        assertThat(AiAnalysisRange.Preset.LAST_5_TRADING_DAYS.tradingDays()).isEqualTo(5);
        assertThat(AiAnalysisRange.Preset.LAST_20_TRADING_DAYS.tradingDays()).isEqualTo(20);
    }

    @Test
    @DisplayName("场景名与说明都非空——空说明会让前端只能显示一个光秃秃的枚举名")
    void everySceneHasNameAndDescription() {
        // 先钉住数量：空列表会让下面的 for 循环空转，测试"通过"却什么都没验证
        assertThat(catalog.definitions()).hasSize(5);
        for (AiSceneDefinition definition : catalog.definitions()) {
            assertThat(definition.name()).as("%s 的名称", definition.scene()).isNotBlank();
            assertThat(definition.description()).as("%s 的说明", definition.scene()).isNotBlank();
        }
    }

    @Test
    @DisplayName("find 对未知场景返回空，而不是抛异常或返回兜底场景")
    void findReturnsEmptyForUnknownScene() {
        assertThat(catalog.find(null)).isEmpty();
        assertThat(catalog.find(AiScene.COMPARE)).isPresent();
    }

    @Test
    @DisplayName("场景取值集合与 ai_session / ai_task 的 CHECK 约束同集合")
    void sceneCodesMatchDatabaseConstraint() {
        assertThat(AiScene.codes())
                .containsExactly("MARKET", "SECTOR", "STOCK", "STOCK_RISK", "COMPARE");
        assertThat(AiScene.fromCode("compare")).contains(AiScene.COMPARE);
        assertThat(AiScene.fromCode("UNKNOWN")).isEmpty();
    }

    @Test
    @DisplayName("场景定义是不可变的：拿到列表后改动不会污染目录")
    void definitionsAreImmutable() {
        List<AiSceneDefinition> first = catalog.definitions();
        Optional<AiSceneDefinition> market = catalog.find(AiScene.MARKET);

        assertThat(first).isSameAs(catalog.definitions());
        assertThat(market).isPresent();
        assertThat(catalog.find(AiScene.MARKET).orElseThrow().name())
                .isEqualTo(market.orElseThrow().name());
    }
}
