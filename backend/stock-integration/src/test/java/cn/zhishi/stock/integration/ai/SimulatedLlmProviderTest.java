package cn.zhishi.stock.integration.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhishi.stock.ai.domain.AiReportSection;
import cn.zhishi.stock.ai.domain.LlmChunk;
import cn.zhishi.stock.ai.domain.LlmCompletion;
import cn.zhishi.stock.ai.domain.LlmErrorCategory;
import cn.zhishi.stock.ai.domain.LlmEvidence;
import cn.zhishi.stock.ai.domain.LlmProviderException;
import cn.zhishi.stock.ai.domain.LlmRequest;
import cn.zhishi.stock.ai.domain.AiEvidenceType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SimulatedLlmProvider} 的行为测试。
 *
 * <p>它是"接口与真实 LLM 同构，替换实现类即可切换"这条验收口径的落点，因此测的是
 * **契约行为**而不是某段文案：
 *
 * <ul>
 *   <li>确定性——同一请求逐位相同。不确定的模拟实现会让 CI 与测试随运行漂移；
 *   <li>引用合法性——正文里出现的编号必须都在候选集合内。这是 M3-07 校验器的前提，
 *       也是"模型不能凭空造引用"这条规则的**可观测**证据；
 *   <li>章节完整性与顺序——契约 §13.5 的固定章节，前端按 section 追加片段；
 *   <li>错误分类——决定重试策略，靠 message 文本判断会在供应商改措辞后静默失效。
 * </ul>
 */
class SimulatedLlmProviderTest {

    /** 正文里的引用写法：{@code [1]}、{@code [12]}。 */
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");

    private static final String PROVIDER = "SIMULATED";
    private static final String MODEL = "sim-analyst-v1";

    private final SimulatedLlmProvider provider = new SimulatedLlmProvider(
            new SimulatedContentHasher(), PROVIDER, MODEL);

    private static LlmEvidence quote(int no, String title) {
        return new LlmEvidence(no, AiEvidenceType.QUOTE, title, title + " 的行情摘要");
    }

    private static LlmEvidence news(int no, String title) {
        return new LlmEvidence(no, AiEvidenceType.NEWS, title, title + " 的资讯摘要");
    }

    private static LlmRequest request(List<LlmEvidence> evidence) {
        return new LlmRequest(
                PROVIDER,
                MODEL,
                "prompt-v1",
                "report-schema-v1",
                "你是分析助手，只能引用给定编号的证据，不得生成新的链接。",
                "请分析 模拟证券600519 在最近 5 个交易日的表现。",
                evidence,
                2048);
    }

    /** 标准请求：两只证券的行情 + 两条资讯，足以产出全部六个章节。 */
    private static LlmRequest requestWithQuoteAndNews() {
        return request(List.of(
                quote(1, "模拟证券600519 行情快照"),
                quote(2, "模拟证券000001 行情快照"),
                news(3, "某公司发布业绩预告"),
                news(4, "行业景气度回升")));
    }

    private static Set<Integer> citationsOf(LlmCompletion completion) {
        StringBuilder text = new StringBuilder();
        for (LlmChunk chunk : completion.chunks()) {
            text.append(chunk.delta());
        }
        Matcher matcher = CITATION.matcher(text);
        Set<Integer> found = new LinkedHashSet<>();
        while (matcher.find()) {
            found.add(Integer.parseInt(matcher.group(1)));
        }
        return found;
    }

    // ---------- 确定性 ----------

    @Test
    @DisplayName("同一请求两次调用产出逐位相同的结果——模拟实现必须确定性")
    void isDeterministic() {
        LlmCompletion first = provider.complete(requestWithQuoteAndNews(), chunk -> { });
        LlmCompletion second = provider.complete(requestWithQuoteAndNews(), chunk -> { });

        assertThat(second.chunks()).isEqualTo(first.chunks());
        assertThat(second.providerRequestId()).isEqualTo(first.providerRequestId());
        assertThat(second.usage()).isEqualTo(first.usage());
        assertThat(second.firstChunkLatency()).isEqualTo(first.firstChunkLatency());
        assertThat(second.totalLatency()).isEqualTo(first.totalLatency());
        assertThat(second.modelCode()).isEqualTo(MODEL);
    }

    @Test
    @DisplayName("请求内容变化时输出随之变化——否则「确定性」会退化成「恒定不变」")
    void outputChangesWithRequest() {
        LlmCompletion baseline = provider.complete(requestWithQuoteAndNews(), chunk -> { });
        LlmCompletion changed = provider.complete(
                request(List.of(quote(1, "另一只证券的行情快照"))), chunk -> { });

        assertThat(changed.textOf(AiReportSection.CORE_CONCLUSION))
                .isNotEqualTo(baseline.textOf(AiReportSection.CORE_CONCLUSION));
        assertThat(changed.providerRequestId()).isNotEqualTo(baseline.providerRequestId());
    }

    // ---------- 章节 ----------

    @Test
    @DisplayName("有行情与资讯证据时六个章节齐全，顺序与枚举声明一致")
    void producesAllSectionsInFixedOrder() {
        LlmCompletion completion = provider.complete(requestWithQuoteAndNews(), chunk -> { });

        assertThat(completion.sections())
                .containsExactly(
                        AiReportSection.CORE_CONCLUSION,
                        AiReportSection.QUOTE_EVIDENCE,
                        AiReportSection.COMPARISON_ANALYSIS,
                        AiReportSection.EVENT_CLUES,
                        AiReportSection.RISK_AND_UNCERTAINTY,
                        AiReportSection.DISCLAIMER);
    }

    @Test
    @DisplayName("没有资讯证据时不产出事件线索章节——空话不如不说")
    void omitsEventCluesWithoutNewsEvidence() {
        LlmCompletion completion = provider.complete(
                request(List.of(quote(1, "模拟证券600519 行情快照"), quote(2, "模拟证券000001 行情快照"))),
                chunk -> { });

        assertThat(completion.sections()).doesNotContain(AiReportSection.EVENT_CLUES);
        assertThat(completion.sections()).contains(AiReportSection.COMPARISON_ANALYSIS);
    }

    @Test
    @DisplayName("只有一条行情证据时不产出对比分析章节——没有第二个对象可比")
    void omitsComparisonWithoutSecondQuote() {
        LlmCompletion completion = provider.complete(
                request(List.of(quote(1, "模拟证券600519 行情快照"), news(2, "一条资讯"))),
                chunk -> { });

        assertThat(completion.sections()).doesNotContain(AiReportSection.COMPARISON_ANALYSIS);
        assertThat(completion.sections()).contains(AiReportSection.EVENT_CLUES);
    }

    @Test
    @DisplayName("必填章节在任何情况下都产出，且免责声明永远在最后")
    void requiredSectionsAlwaysPresent() {
        LlmCompletion completion = provider.complete(request(List.of()), chunk -> { });

        assertThat(completion.sections())
                .containsExactly(
                        AiReportSection.CORE_CONCLUSION,
                        AiReportSection.QUOTE_EVIDENCE,
                        AiReportSection.RISK_AND_UNCERTAINTY,
                        AiReportSection.DISCLAIMER);
        assertThat(completion.sections().get(completion.sections().size() - 1))
                .isEqualTo(AiReportSection.DISCLAIMER);
        assertThat(completion.textOf(AiReportSection.DISCLAIMER))
                .contains("不构成任何投资建议");
    }

    // ---------- 片段与回调 ----------

    @Test
    @DisplayName("片段序号从 1 连续递增，回调顺序与返回列表一致")
    void chunkSequenceIsSequentialAndCallbackMatches() {
        List<LlmChunk> streamed = new ArrayList<>();
        LlmCompletion completion = provider.complete(requestWithQuoteAndNews(), streamed::add);

        assertThat(completion.chunks())
                .extracting(LlmChunk::sequence)
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, completion.chunks().size())
                                .boxed()
                                .toList());
        assertThat(streamed).isEqualTo(completion.chunks());
    }

    @Test
    @DisplayName("同一章节的片段拼起来就是该章节的完整正文")
    void sectionTextIsConcatenationOfItsChunks() {
        LlmCompletion completion = provider.complete(requestWithQuoteAndNews(), chunk -> { });

        for (AiReportSection section : completion.sections()) {
            String joined = completion.chunks().stream()
                    .filter(chunk -> chunk.section() == section)
                    .map(LlmChunk::delta)
                    .reduce("", String::concat);
            assertThat(completion.textOf(section)).as("%s 的正文", section).isEqualTo(joined);
            assertThat(joined).as("%s 的正文非空", section).isNotBlank();
        }
    }

    // ---------- 引用合法性 ----------

    @Test
    @DisplayName("正文里出现的引用编号全部落在候选集合内")
    void everyCitationIsWithinCandidateSet() {
        LlmCompletion completion = provider.complete(requestWithQuoteAndNews(), chunk -> { });

        assertThat(citationsOf(completion))
                .isNotEmpty()
                .isSubsetOf(Set.of(1, 2, 3, 4));
    }

    @Test
    @DisplayName("没有候选证据时不产生任何引用——不能凭空造编号")
    void noCitationsWithoutCandidates() {
        LlmCompletion completion = provider.complete(request(List.of()), chunk -> { });

        assertThat(citationsOf(completion)).isEmpty();
    }

    @Test
    @DisplayName("引用被引证据的摘要片段，正文与证据对得上")
    void citesEvidenceSummary() {
        LlmCompletion completion = provider.complete(requestWithQuoteAndNews(), chunk -> { });

        assertThat(completion.textOf(AiReportSection.EVENT_CLUES))
                .contains("某公司发布业绩预告")
                .contains("行业景气度回升");
    }

    // ---------- 用量 ----------

    @Test
    @DisplayName("用量计数为正且满足 total >= prompt + completion 的库约束")
    void usageSatisfiesDatabaseConstraint() {
        LlmCompletion completion = provider.complete(requestWithQuoteAndNews(), chunk -> { });

        assertThat(completion.usage().promptTokens()).isPositive();
        assertThat(completion.usage().completionTokens()).isPositive();
        assertThat(completion.usage().totalTokens())
                .isGreaterThanOrEqualTo(
                        completion.usage().promptTokens() + completion.usage().completionTokens());
    }

    // ---------- 故障注入 ----------

    @Test
    @DisplayName("故障注入：超时按 TIMEOUT 分类且标记可重试")
    void timeoutFaultRaisesRetryableTimeout() {
        SimulatedLlmProvider failing = new SimulatedLlmProvider(
                new SimulatedContentHasher(), PROVIDER, MODEL,
                SimulatedLlmProvider.FaultMode.TIMEOUT);

        assertThatThrownBy(() -> failing.complete(requestWithQuoteAndNews(), chunk -> { }))
                .isInstanceOf(LlmProviderException.class)
                .satisfies(exception -> {
                    LlmProviderException failure = (LlmProviderException) exception;
                    assertThat(failure.category()).isEqualTo(LlmErrorCategory.TIMEOUT);
                    assertThat(failure.retryable()).isTrue();
                    assertThat(failure.errorCode()).isEqualTo("AI_TASK_TIMED_OUT");
                });
    }

    @Test
    @DisplayName("故障注入：限流按 RATE_LIMIT 分类且标记可重试")
    void rateLimitFaultRaisesRetryableRateLimit() {
        SimulatedLlmProvider failing = new SimulatedLlmProvider(
                new SimulatedContentHasher(), PROVIDER, MODEL,
                SimulatedLlmProvider.FaultMode.RATE_LIMIT);

        assertThatThrownBy(() -> failing.complete(requestWithQuoteAndNews(), chunk -> { }))
                .isInstanceOf(LlmProviderException.class)
                .satisfies(exception -> assertThat(((LlmProviderException) exception).category())
                        .isEqualTo(LlmErrorCategory.RATE_LIMIT));
    }

    @Test
    @DisplayName("故障注入：非法引用产出候选集合外的编号，供后续校验器测试使用")
    void invalidCitationFaultProducesOutOfRangeNumber() {
        SimulatedLlmProvider faulty = new SimulatedLlmProvider(
                new SimulatedContentHasher(), PROVIDER, MODEL,
                SimulatedLlmProvider.FaultMode.INVALID_CITATION);

        LlmCompletion completion = faulty.complete(requestWithQuoteAndNews(), chunk -> { });

        // AssertJ 没有 isNotSubsetOf；直接断言"出现了候选集合外的编号"
        assertThat(citationsOf(completion)).isNotEmpty();
        assertThat(citationsOf(completion)).anyMatch(no -> no > 4);
    }

    @Test
    @DisplayName("默认故障模式为 NONE：正常路径产出合法结果")
    void defaultFaultModeIsNone() {
        LlmCompletion completion = provider.complete(requestWithQuoteAndNews(), chunk -> { });

        assertThat(citationsOf(completion)).isSubsetOf(Set.of(1, 2, 3, 4));
    }
}
