package cn.zhishi.stock.integration.ai;

import cn.zhishi.stock.ai.domain.AiContentHasher;
import cn.zhishi.stock.ai.domain.AiEvidenceType;
import cn.zhishi.stock.ai.domain.AiReportSection;
import cn.zhishi.stock.ai.domain.LlmChunk;
import cn.zhishi.stock.ai.domain.LlmCompletion;
import cn.zhishi.stock.ai.domain.LlmEvidence;
import cn.zhishi.stock.ai.domain.LlmProviderException;
import cn.zhishi.stock.ai.domain.LlmProviderPort;
import cn.zhishi.stock.ai.domain.LlmRequest;
import cn.zhishi.stock.ai.domain.LlmUsage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 确定性模拟 LLM Provider（架构 §11.3：{@code LlmProviderPort} 由 {@code stock-integration} 实现）。
 *
 * <h2>它模拟什么，不模拟什么</h2>
 * 模拟的是**契约行为**：流式片段、固定章节、引用只能落在候选集合内、错误分类、
 * token 用量、确定性。**不模拟分析质量**——正文是结构占位文本，
 * 事实数字全部由证据摘要承载，模型不生成任何数字。真实 LLM 接入后替换实现类即可，
 * 调用方（M3-07 的编排器）不需要改一行。
 *
 * <p>之所以不假装能分析：一份"看起来像分析结论"的模拟文本会被当真，
 * 而它背后的推理并不存在。宁可让占位文本一眼可辨。
 *
 * <h2>确定性</h2>
 * 随机源只用 {@link AiContentHasher}（其实现用 SplitMix64），
 * 不用 {@code Math.random} / {@code UUID} / 当前时间。同一请求产出逐位相同的结果——
 * 否则测试与 CI 会随运行漂移，而"偶发失败"是最难排查的一类问题。
 *
 * <h2>引用安全</h2>
 * 正文里的引用编号**只从 {@code request.evidenceCandidates()} 里取**。
 * 模型侧看不到原文地址（{@link LlmEvidence} 没有 URL 字段），
 * 因此"模型生成可信链接"在结构上不可能发生。
 */
public class SimulatedLlmProvider implements LlmProviderPort {

    /**
     * 故障注入模式，默认 {@link #NONE}。
     *
     * <p>默认路径产出**合法**结果；故障路径供 M3-07 的引用校验器与重试逻辑测试使用。
     * 把"故意产出非法引用"塞进默认路径，会让正常链路永远跑不通——
     * 那不是在测试校验器，是在破坏主流程。
     */
    public enum FaultMode {

        /** 正常：引用全部落在候选集合内。 */
        NONE,

        /** 正文里出现候选集合外的编号，用于验证校验器真的会拒绝。 */
        INVALID_CITATION,

        /** 直接抛超时。 */
        TIMEOUT,

        /** 直接抛限流。 */
        RATE_LIMIT
    }

    /** 中文的粗略折算：1 token ≈ 4 个字符。真实 Provider 会返回真实计数。 */
    private static final int CHARS_PER_TOKEN = 4;

    /** 单个流式片段的字符数。 */
    private static final int DELTA_LENGTH = 24;

    /** 故障注入时使用的越界编号。 */
    private static final int OUT_OF_RANGE_EVIDENCE_NO = 99;

    private final AiContentHasher hasher;
    private final String providerCode;
    private final String modelCode;
    private final FaultMode faultMode;

    public SimulatedLlmProvider(AiContentHasher hasher, String providerCode, String modelCode) {
        this(hasher, providerCode, modelCode, FaultMode.NONE);
    }

    public SimulatedLlmProvider(
            AiContentHasher hasher, String providerCode, String modelCode, FaultMode faultMode) {
        this.hasher = hasher;
        this.providerCode = providerCode;
        this.modelCode = modelCode;
        this.faultMode = faultMode == null ? FaultMode.NONE : faultMode;
    }

    @Override
    public LlmCompletion complete(LlmRequest request, Consumer<LlmChunk> onChunk) {
        if (faultMode == FaultMode.TIMEOUT) {
            throw LlmProviderException.timeout("模拟供应商超时：未在时限内产出完整结果");
        }
        if (faultMode == FaultMode.RATE_LIMIT) {
            throw LlmProviderException.rateLimited("模拟供应商限流：请退避后重试");
        }

        String fingerprint = fingerprintOf(request);
        List<LlmChunk> chunks = new ArrayList<>();
        StringBuilder produced = new StringBuilder();
        int sequence = 1;
        for (AiReportSection section : sectionsFor(request)) {
            String body = bodyOf(section, request);
            if (faultMode == FaultMode.INVALID_CITATION && section == AiReportSection.CORE_CONCLUSION) {
                body = body + " 补充引用 [" + OUT_OF_RANGE_EVIDENCE_NO + "]。";
            }
            for (String delta : split(body)) {
                LlmChunk chunk = new LlmChunk(section, delta, sequence++);
                chunks.add(chunk);
                produced.append(delta);
                onChunk.accept(chunk);
            }
        }

        int promptTokens = estimateTokens(request.systemPrompt()) + estimateTokens(request.userPrompt());
        for (LlmEvidence evidence : request.evidenceCandidates()) {
            promptTokens += estimateTokens(evidence.sourceTitle())
                    + estimateTokens(evidence.evidenceSummary());
        }
        int completionTokens = estimateTokens(produced.toString());
        LlmUsage usage = new LlmUsage(
                promptTokens, completionTokens, 0, promptTokens + completionTokens);

        // 延迟由请求指纹派生：确定性，且不同请求有不同的值（比固定常量更接近真实）
        long seed = Math.abs(fingerprint.hashCode());
        Duration firstChunkLatency = Duration.ofMillis(80 + seed % 200);
        Duration totalLatency = firstChunkLatency.plusMillis(500 + seed % 1500);

        return new LlmCompletion(
                "sim-" + fingerprint.substring(0, 16),
                modelCode,
                chunks,
                usage,
                firstChunkLatency,
                totalLatency);
    }

    /** 请求指纹：请求的全部内容参与，因此内容一变指纹就变。 */
    private String fingerprintOf(LlmRequest request) {
        StringBuilder source = new StringBuilder()
                .append(request.systemPrompt()).append('\u0000')
                .append(request.userPrompt()).append('\u0000')
                .append(request.promptVersion()).append('\u0000')
                .append(request.contentSchemaVersion());
        for (LlmEvidence evidence : request.evidenceCandidates()) {
            source.append('\u0000')
                    .append(evidence.evidenceNo()).append(':')
                    .append(evidence.evidenceType()).append(':')
                    .append(evidence.sourceTitle()).append(':')
                    .append(evidence.evidenceSummary());
        }
        return hasher.hashOfText(source.toString());
    }

    // ---------- 章节 ----------

    /**
     * 产出哪些章节。
     *
     * <p>必填章节（契约 §13.5）恒产出；{@code COMPARISON_ANALYSIS} 需要至少两个可比对象
     * （以行情证据条数近似——对比场景每只证券恰好一条行情证据）；
     * {@code EVENT_CLUES} 需要至少一条资讯类证据。没有内容支撑的章节不产出：
     * "本报告无资讯线索"这种空话只会稀释真正有信息的部分。
     */
    private static List<AiReportSection> sectionsFor(LlmRequest request) {
        boolean hasComparison = evidenceOfType(request, AiEvidenceType.QUOTE).size() >= 2;
        boolean hasEventClues = !newsEvidenceOf(request).isEmpty();
        List<AiReportSection> sections = new ArrayList<>();
        for (AiReportSection section : AiReportSection.inOrder()) {
            switch (section) {
                case COMPARISON_ANALYSIS -> {
                    if (hasComparison) {
                        sections.add(section);
                    }
                }
                case EVENT_CLUES -> {
                    if (hasEventClues) {
                        sections.add(section);
                    }
                }
                default -> sections.add(section);
            }
        }
        return sections;
    }

    private static List<LlmEvidence> evidenceOfType(LlmRequest request, AiEvidenceType type) {
        return request.evidenceCandidates().stream()
                .filter(evidence -> evidence.evidenceType() == type)
                .toList();
    }

    private static List<LlmEvidence> newsEvidenceOf(LlmRequest request) {
        return request.evidenceCandidates().stream()
                .filter(evidence -> evidence.evidenceType() == AiEvidenceType.NEWS
                        || evidence.evidenceType() == AiEvidenceType.ANNOUNCEMENT)
                .toList();
    }

    // ---------- 正文 ----------

    private String bodyOf(AiReportSection section, LlmRequest request) {
        List<LlmEvidence> quotes = evidenceOfType(request, AiEvidenceType.QUOTE);
        List<LlmEvidence> news = newsEvidenceOf(request);
        return switch (section) {
            case CORE_CONCLUSION -> "综合本任务固化的 " + request.evidenceCandidates().size()
                    + " 条证据（行情 " + quotes.size() + " 条、资讯 " + news.size() + " 条），"
                    + "以下结论均以证据编号标注来源，未标注编号的表述不作为事实依据。"
                    + "证据覆盖范围见 " + cite(request.evidenceCandidates()) + "。";
            case QUOTE_EVIDENCE -> quotes.isEmpty()
                    ? "本任务没有固化的行情证据，行情侧依据缺失。"
                    : "行情侧依据：" + quotes.stream()
                            .map(evidence -> evidence.sourceTitle() + "[" + evidence.evidenceNo()
                                    + "]（" + evidence.evidenceSummary() + "）")
                            .collect(Collectors.joining("；"))
                            + "。以上数值均来自固化的行情快照，未在本环节重算。";
            case COMPARISON_ANALYSIS -> "本次对比涉及 " + quotes.size() + " 个行情对象："
                    + cite(quotes) + "。所有对象使用同一数据截止时点与同一分析区间；"
                    + "未对齐的口径不作为可比依据。";
            case EVENT_CLUES -> "分析区间内的资讯线索包括：" + news.stream()
                            .map(evidence -> evidence.sourceTitle() + "[" + evidence.evidenceNo() + "]")
                            .collect(Collectors.joining("；"))
                            + "。以上线索均来自任务固化的证据候选，不引入外部链接。";
            case RISK_AND_UNCERTAINTY -> "本结论的不确定性来自三处："
                    + "一、数据截止时间之后发生的变化未被纳入；"
                    + "二、证据 " + cite(request.evidenceCandidates()) + " 只覆盖已固化的来源，"
                    + "可能存在未被采集的信息；"
                    + "三、模型输出不构成对未来走势的判断。";
            case DISCLAIMER -> "本内容由 AI 生成，仅供研究参考，不构成任何投资建议，"
                    + "亦不构成任何买卖要约。投资决策应基于独立判断并自行承担风险。";
        };
    }

    /**
     * 渲染引用。
     *
     * <p>没有候选时给出**明确的文字说明**而不是留空：留空会让正文出现
     * "证据覆盖范围见 。"这种看起来像渲染故障的句子。
     */
    private static String cite(List<LlmEvidence> evidence) {
        if (evidence.isEmpty()) {
            return "（本任务没有可引用的固化证据）";
        }
        return evidence.stream()
                .map(item -> "[" + item.evidenceNo() + "]")
                .collect(Collectors.joining("、"));
    }

    /**
     * 把正文切成流式片段。
     *
     * <p>按固定长度切而不是按标点：真实流式的切分由 token 边界决定，
     * 与语义边界无关。按标点切会让"片段"看起来比实际更有意义。
     */
    private static List<String> split(String text) {
        List<String> deltas = new ArrayList<>();
        for (int start = 0; start < text.length(); start += DELTA_LENGTH) {
            deltas.add(text.substring(start, Math.min(text.length(), start + DELTA_LENGTH)));
        }
        return deltas;
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, (text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN);
    }

    /** 供应商编码，用于装配侧核对（与 {@code ai_task.provider_code} 同值）。 */
    public String providerCode() {
        return providerCode;
    }

    /** 模型编码。 */
    public String modelCode() {
        return modelCode;
    }

    @Override
    public String toString() {
        return "SimulatedLlmProvider[" + providerCode + "/" + modelCode
                + ", fault=" + faultMode.name().toLowerCase(Locale.ROOT) + "]";
    }
}
