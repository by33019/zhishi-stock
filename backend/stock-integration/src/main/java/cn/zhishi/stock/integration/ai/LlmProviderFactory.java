package cn.zhishi.stock.integration.ai;

import cn.zhishi.stock.ai.domain.AiContentHasher;
import cn.zhishi.stock.ai.domain.LlmProviderPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;

/**
 * LLM 实现的唯一装配点。
 *
 * <h2>为什么需要工厂，而不是在两个配置类里各写一次 if</h2>
 * {@code LlmProviderPort} 有两个装配处：{@code stock-backend}（在线侧，负责 AI-01/AI-02）
 * 与 {@code stock-ai-worker}（真正调模型的一侧）。选择逻辑写两份的后果是
 * **两个进程可能选了不同的实现**——在线侧按真实模型报"将要使用的数据"，
 * 执行侧却用模拟实现产出占位正文，而两边各自看都正常，报告看起来也"有内容"。
 * 收敛成一个工厂，两处只在启动时汇报自己选了哪个模式。
 *
 * <h2>为什么缺省是模拟实现</h2>
 * CI 与本地无密钥环境必须能跑完整测试。把真实实现设为缺省，会让所有集成测试
 * 尝试访问外网——那类失败与代码缺陷长得一样，排查成本极高。
 *
 * <p>反过来，**显式**要求 {@code OPENAI_COMPATIBLE} 而缺密钥时必须快速失败，
 * 不能静默回落到模拟实现：那会产出一份"看起来是分析结论、实则是占位文本"的报告，
 * 而它带着真实的证据编号，读起来与真报告没有区别。
 */
public final class LlmProviderFactory {

    /** 实现模式。取值同时用于 {@code stock.ai.llm-mode} 配置项。 */
    public enum Mode {

        /** 确定性模拟实现：占位正文，不产生模型费用，CI 与本地开发用。 */
        SIMULATED,

        /** OpenAI 兼容协议的真实供应商（通义 compatible-mode、以及其他兼容实现）。 */
        OPENAI_COMPATIBLE
    }

    private LlmProviderFactory() {
    }

    /** 解析配置值。未知取值快速失败而不是回落——拼错一个字母不该安静地降级。 */
    public static Mode modeOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return Mode.SIMULATED;
        }
        for (Mode mode : Mode.values()) {
            if (mode.name().equalsIgnoreCase(raw.trim())) {
                return mode;
            }
        }
        throw new IllegalStateException(
                "无法识别的 stock.ai.llm-mode：" + raw + "（可选：" + SIMULATED_HINT + "）");
    }

    private static final String SIMULATED_HINT = "SIMULATED、OPENAI_COMPATIBLE";

    /**
     * 按模式装配实现。
     *
     * @param hasher     模拟实现的确定性哈希源
     * @param mapper     真实实现的 JSON 编解码器（由 Spring 提供，时区已配置）
     * @param baseUrl    真实实现的 API 基地址，需已包含版本段（如 {@code .../v1}）
     * @param apiKey     真实实现的密钥；{@code OPENAI_COMPATIBLE} 下不得为空
     * @param timeout    单次调用的总时限
     */
    public static LlmProviderPort create(
            Mode mode,
            AiContentHasher hasher,
            ObjectMapper mapper,
            String providerCode,
            String modelCode,
            String baseUrl,
            String apiKey,
            Duration timeout) {
        if (mode == Mode.SIMULATED) {
            return new SimulatedLlmProvider(hasher, providerCode, modelCode);
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "stock.ai.llm-mode=OPENAI_COMPATIBLE 时必须配置 stock.ai.base-url");
        }
        if (mapper == null) {
            throw new IllegalStateException(
                    "stock.ai.llm-mode=OPENAI_COMPATIBLE 时需要 ObjectMapper，但上下文中没有");
        }
        return new OpenAiCompatibleLlmProvider(
                mapper, baseUrl, apiKey, providerCode, modelCode, timeout);
    }
}
