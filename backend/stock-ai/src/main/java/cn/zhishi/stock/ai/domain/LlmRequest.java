package cn.zhishi.stock.ai.domain;

import java.util.List;

/**
 * 一次 LLM 调用请求。
 *
 * <p>请求里**只有** {@link LlmEvidence} 形态的证据（无 URL），以及由用例层组装好的
 * {@code systemPrompt} / {@code userPrompt}。用例层负责"哪些数据可以发出去"的过滤
 * （架构 §11.4：不发送用户名、邮箱、手机号、IP、角色、密码、Token、数据源密钥）。
 *
 * <p>{@code promptVersion} / {@code contentSchemaVersion} 随请求固化并最终写入报告：
 * 历史报告必须可解释——模型或模板升级后，旧报告仍能说明它当时用的是哪一版规则。
 *
 * @param providerCode         供应商编码，与 {@code ai_task.provider_code} 对齐
 * @param modelCode            模型编码
 * @param promptVersion        提示模板版本
 * @param contentSchemaVersion 输出结构版本
 * @param systemPrompt         系统规则（含"只能引用给定编号"的约束）
 * @param userPrompt           上下文与用户问题的渲染结果
 * @param evidenceCandidates   固化后的证据候选视图；模型只能引用其中的编号
 * @param maxOutputTokens      输出 token 上限
 */
public record LlmRequest(
        String providerCode,
        String modelCode,
        String promptVersion,
        String contentSchemaVersion,
        String systemPrompt,
        String userPrompt,
        List<LlmEvidence> evidenceCandidates,
        int maxOutputTokens) {

    public LlmRequest {
        evidenceCandidates = List.copyOf(evidenceCandidates);
        if (maxOutputTokens < 1) {
            throw new IllegalArgumentException("maxOutputTokens 必须为正数：" + maxOutputTokens);
        }
    }

    /** 候选集合里是否存在该编号——引用合法性判定的**唯一**依据。 */
    public boolean hasEvidence(int evidenceNo) {
        return evidenceCandidates.stream().anyMatch(evidence -> evidence.evidenceNo() == evidenceNo);
    }
}
