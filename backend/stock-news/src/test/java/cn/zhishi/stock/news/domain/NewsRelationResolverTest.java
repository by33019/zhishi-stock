package cn.zhishi.stock.news.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link NewsRelationResolver} 的规则表测试。
 *
 * <p>这里逐条钉住 spec §3.5 的 R1~R6，以及两条不变量：
 * 同一目标只产出一条（取最高置信度）、最长匹配优先。
 * 后者在模拟数据上不是理论问题——证券名 {@code 模拟证券600519} 里就含着一个板块名 {@code 证券}。
 */
class NewsRelationResolverTest {

    private static final RelationCatalog CATALOG = new RelationCatalog(
            List.of(
                    NewsFixtures.security("600519", "模拟证券600519"),
                    NewsFixtures.security("000001", "模拟证券000001")),
            List.of(
                    NewsFixtures.sector(2, "证券"),
                    NewsFixtures.sector(11, "半导体")));

    private static final NewsRelationResolver RESOLVER = new NewsRelationResolver(CATALOG);

    // ---------- R1 / R2：结构化提示 ----------

    @Test
    @DisplayName("R1 结构化证券代码命中目录 → EXPLICIT / 1.0 / CONFIRMED")
    void resolvesExplicitSecurityCode() {
        List<NewsRelationResolver.ResolvedRelation> relations = RESOLVER.resolve(
                NewsFixtures.item("一条没有正文线索的标题", null, List.of("600519"), List.of(), null));

        assertThat(relations).hasSize(1);
        NewsRelationResolver.ResolvedRelation relation = relations.get(0);
        assertThat(relation.targetType()).isEqualTo(NewsTargetType.SECURITY);
        assertThat(relation.targetId()).isEqualTo(600519L);
        assertThat(relation.relationMethod()).isEqualTo(NewsRelationMethod.EXPLICIT);
        assertThat(relation.confidenceScore()).isEqualByComparingTo(new BigDecimal("1.00000"));
        assertThat(relation.relationStatus()).isEqualTo(NewsRelationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("R1 结构化代码不在目录中 → 不产出关联（不编造目标 id）")
    void ignoresUnknownExplicitSecurityCode() {
        List<NewsRelationResolver.ResolvedRelation> relations = RESOLVER.resolve(
                NewsFixtures.item("标题", null, List.of("999999"), List.of(), null));

        assertThat(relations).isEmpty();
    }

    @Test
    @DisplayName("R2 结构化 marketCode → MARKET / EXPLICIT / 1.0 / CONFIRMED")
    void resolvesExplicitMarket() {
        List<NewsRelationResolver.ResolvedRelation> relations = RESOLVER.resolve(
                NewsFixtures.item("大盘综述", null, List.of(), List.of(), "CN"));

        assertThat(relations).hasSize(1);
        NewsRelationResolver.ResolvedRelation relation = relations.get(0);
        assertThat(relation.targetType()).isEqualTo(NewsTargetType.MARKET);
        assertThat(relation.targetId()).isEqualTo(1L);
        assertThat(relation.relationMethod()).isEqualTo(NewsRelationMethod.EXPLICIT);
        assertThat(relation.relationStatus()).isEqualTo(NewsRelationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("R2 不支持的 marketCode → 不产出关联")
    void ignoresUnknownMarket() {
        assertThat(RESOLVER.resolve(
                NewsFixtures.item("海外市场综述", null, List.of(), List.of(), "US")))
                .isEmpty();
    }

    // ---------- R3 / R4：证券简称 ----------

    @Test
    @DisplayName("R3 标题含证券简称 → RULE / 0.8 / CONFIRMED")
    void resolvesSecurityNameInTitle() {
        List<NewsRelationResolver.ResolvedRelation> relations = RESOLVER.resolve(
                NewsFixtures.item("模拟证券600519 发布年度业绩预告", null));

        assertThat(relations).hasSize(1);
        NewsRelationResolver.ResolvedRelation relation = relations.get(0);
        assertThat(relation.targetType()).isEqualTo(NewsTargetType.SECURITY);
        assertThat(relation.targetId()).isEqualTo(600519L);
        assertThat(relation.relationMethod()).isEqualTo(NewsRelationMethod.RULE);
        assertThat(relation.confidenceScore()).isEqualByComparingTo(new BigDecimal("0.80000"));
        assertThat(relation.relationStatus()).isEqualTo(NewsRelationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("R4 仅摘要含证券简称 → RULE / 0.6 / CANDIDATE（低置信，不进前台与 AI 证据）")
    void resolvesSecurityNameOnlyInSummaryAsCandidate() {
        List<NewsRelationResolver.ResolvedRelation> relations = RESOLVER.resolve(
                NewsFixtures.item("消费板块午后走强", "机构称模拟证券600519 估值处于低位"));

        assertThat(relations).hasSize(1);
        NewsRelationResolver.ResolvedRelation relation = relations.get(0);
        assertThat(relation.targetId()).isEqualTo(600519L);
        assertThat(relation.confidenceScore()).isEqualByComparingTo(new BigDecimal("0.60000"));
        assertThat(relation.relationStatus()).isEqualTo(NewsRelationStatus.CANDIDATE);
    }

    // ---------- R5 / R6：板块名 ----------

    @Test
    @DisplayName("R5 标题含板块名 → RULE / 0.75 / CONFIRMED")
    void resolvesSectorNameInTitle() {
        List<NewsRelationResolver.ResolvedRelation> relations = RESOLVER.resolve(
                NewsFixtures.item("半导体行业景气度回升", null));

        assertThat(relations).hasSize(1);
        NewsRelationResolver.ResolvedRelation relation = relations.get(0);
        assertThat(relation.targetType()).isEqualTo(NewsTargetType.SECTOR);
        assertThat(relation.targetId()).isEqualTo(11L);
        assertThat(relation.confidenceScore()).isEqualByComparingTo(new BigDecimal("0.75000"));
        assertThat(relation.relationStatus()).isEqualTo(NewsRelationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("R6 仅摘要含板块名 → RULE / 0.5 / CANDIDATE")
    void resolvesSectorNameOnlyInSummaryAsCandidate() {
        List<NewsRelationResolver.ResolvedRelation> relations = RESOLVER.resolve(
                NewsFixtures.item("今日盘面回顾", "半导体板块成交额居前"));

        assertThat(relations).hasSize(1);
        assertThat(relations.get(0).targetId()).isEqualTo(11L);
        assertThat(relations.get(0).confidenceScore())
                .isEqualByComparingTo(new BigDecimal("0.50000"));
        assertThat(relations.get(0).relationStatus()).isEqualTo(NewsRelationStatus.CANDIDATE);
    }

    // ---------- 两条不变量 ----------

    @Test
    @DisplayName("同一目标被标题与摘要同时命中 → 只产出一条，取置信度最高者")
    void keepsHighestConfidencePerTarget() {
        List<NewsRelationResolver.ResolvedRelation> relations = RESOLVER.resolve(
                NewsFixtures.item("模拟证券600519 发布公告", "公告显示模拟证券600519 营收增长"));

        assertThat(relations).hasSize(1);
        assertThat(relations.get(0).confidenceScore())
                .isEqualByComparingTo(new BigDecimal("0.80000"));
    }

    @Test
    @DisplayName("结构化提示与文本匹配命中同一证券 → 只产出一条，取 EXPLICIT 的 1.0")
    void explicitWinsOverTextMatch() {
        List<NewsRelationResolver.ResolvedRelation> relations = RESOLVER.resolve(
                NewsFixtures.item("模拟证券600519 发布公告", null, List.of("600519"), List.of(), null));

        assertThat(relations).hasSize(1);
        assertThat(relations.get(0).relationMethod()).isEqualTo(NewsRelationMethod.EXPLICIT);
        assertThat(relations.get(0).confidenceScore())
                .isEqualByComparingTo(new BigDecimal("1.00000"));
    }

    @Test
    @DisplayName("最长匹配优先：标题含'模拟证券600519'不得顺带关联上'证券'板块")
    void longestMatchWins() {
        List<NewsRelationResolver.ResolvedRelation> relations = RESOLVER.resolve(
                NewsFixtures.item("模拟证券600519 发布公告", null));

        assertThat(relations)
                .extracting(NewsRelationResolver.ResolvedRelation::targetType)
                .containsExactly(NewsTargetType.SECURITY);
    }

    @Test
    @DisplayName("最长匹配优先不误伤：'证券' 单独出现时仍能关联到证券板块")
    void shorterNameStillMatchesWhenStandalone() {
        List<NewsRelationResolver.ResolvedRelation> relations = RESOLVER.resolve(
                NewsFixtures.item("证券行业迎来政策利好", null));

        assertThat(relations).hasSize(1);
        assertThat(relations.get(0).targetType()).isEqualTo(NewsTargetType.SECTOR);
        assertThat(relations.get(0).targetId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("标题命中多只证券 → 每只各一条")
    void resolvesMultipleSecurities() {
        List<NewsRelationResolver.ResolvedRelation> relations = RESOLVER.resolve(
                NewsFixtures.item("模拟证券600519 与 模拟证券000001 同日发布公告", null));

        assertThat(relations).hasSize(2);
        assertThat(relations)
                .extracting(NewsRelationResolver.ResolvedRelation::targetId)
                .containsExactlyInAnyOrder(600519L, 1L);
    }

    @Test
    @DisplayName("摘要为 null 时不抛异常：只有标题参与匹配")
    void toleratesNullSummary() {
        List<NewsRelationResolver.ResolvedRelation> relations =
                RESOLVER.resolve(NewsFixtures.item("模拟证券600519 发布公告", null));

        assertThat(relations).hasSize(1);
    }

    @Test
    @DisplayName("没有任何线索 → 不产出关联（不把空结果伪装成市场关联）")
    void producesNothingWithoutHints() {
        assertThat(RESOLVER.resolve(NewsFixtures.item("今日天气晴朗", null))).isEmpty();
    }

    @Test
    @DisplayName("每条关联都带依据摘要：后台复核要能回答'为什么关联到它'")
    void everyRelationCarriesReason() {
        List<NewsRelationResolver.ResolvedRelation> relations = RESOLVER.resolve(
                NewsFixtures.item("半导体行业景气度回升", null));

        assertThat(relations).allSatisfy(relation -> assertThat(relation.reasonSummary()).isNotBlank());
    }

    @Test
    @DisplayName("目录为空时不产出任何关联，也不抛异常")
    void toleratesEmptyCatalog() {
        NewsRelationResolver empty = new NewsRelationResolver(RelationCatalog.empty());

        assertThat(empty.resolve(NewsFixtures.item("模拟证券600519 发布公告", null))).isEmpty();
    }
}
