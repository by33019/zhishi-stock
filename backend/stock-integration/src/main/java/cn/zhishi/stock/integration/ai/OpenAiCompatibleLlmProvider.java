package cn.zhishi.stock.integration.ai;

import cn.zhishi.stock.ai.domain.AiSectionSplitter;
import cn.zhishi.stock.ai.domain.LlmChunk;
import cn.zhishi.stock.ai.domain.LlmCompletion;
import cn.zhishi.stock.ai.domain.LlmEvidence;
import cn.zhishi.stock.ai.domain.LlmErrorCategory;
import cn.zhishi.stock.ai.domain.LlmProviderException;
import cn.zhishi.stock.ai.domain.LlmProviderPort;
import cn.zhishi.stock.ai.domain.LlmRequest;
import cn.zhishi.stock.ai.domain.LlmUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * OpenAI 兼容协议的 LLM 适配器（架构 §11.3：{@code LlmProviderPort} 由 {@code stock-integration} 实现）。
 *
 * <h2>为什么是"协议"而不是"某一家"</h2>
 * 通义（DashScope）的 compatible-mode、以及多数国内供应商都暴露 OpenAI 兼容的
 * {@code /chat/completions}。因此这里按协议实现而不是按供应商实现：换一家供应商通常只需改
 * base URL 与模型名（{@code application.yml} 的两个配置项），不需要新写一个类。
 *
 * <h2>只取 delta.content —— 这是本类最容易写错的地方</h2>
 * 推理模型（实测 {@code qwen3.8-max-0902}）的 delta 里有**两条通道**：
 * {@code content}（最终答案）与 {@code reasoning_content}（思维链），两者文本完全不同。
 * 必须只取前者。把思维链混进报告的后果是：
 * <ul>
 *   <li>思维链里随机出现的 {@code [数字]} 会被定稿校验器当成引用编号，
 *       判定越界后整份输出被拒——而报告正文本身是合规的；</li>
 *   <li>六章节结构被思维链文本冲散。</li>
 * </ul>
 * 这两者都**不会**在"随手跑一次"时暴露，只在偶发任务上失败，因此有专门的单测守着。
 *
 * <h2>证据候选由本类渲染进请求</h2>
 * {@code AiPromptRenderer} 刻意不把证据正文拼进 userPrompt（那会造成"Prompt 里有 20 条、
 * 候选集合里有 18 条"的错位）。把候选集合呈现给供应商是适配器的职责——它才知道
 * "怎么跟这个供应商说话"。
 *
 * <h2>线程安全</h2>
 * 端口要求实现必须线程安全（Worker 会并发调用）。本类不持有任何可变状态，
 * 分片缓冲是 {@link #complete} 的局部变量。
 */
public class OpenAiCompatibleLlmProvider implements LlmProviderPort {

    /** 流式片段若长时间无产出，视为卡死。与总时限分开，避免"慢但活着"被误杀。 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final URI endpoint;
    private final String apiKey;
    private final String providerCode;
    private final String modelCode;
    private final Duration timeout;

    public OpenAiCompatibleLlmProvider(
            ObjectMapper mapper,
            String baseUrl,
            String apiKey,
            String providerCode,
            String modelCode) {
        this(mapper, baseUrl, apiKey, providerCode, modelCode, DEFAULT_TIMEOUT);
    }

    public OpenAiCompatibleLlmProvider(
            ObjectMapper mapper,
            String baseUrl,
            String apiKey,
            String providerCode,
            String modelCode,
            Duration timeout) {
        if (apiKey == null || apiKey.isBlank()) {
            // 快速失败：缺密钥时不要在第一次任务执行时才报错，
            // 那时错误会落在某个用户的任务上，而根因是部署配置。
            throw new IllegalArgumentException("apiKey 不得为空");
        }
        this.mapper = mapper;
        this.apiKey = apiKey;
        this.providerCode = providerCode;
        this.modelCode = modelCode;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        this.endpoint = URI.create(normalized + "/chat/completions");
        this.http = HttpClient.newBuilder().connectTimeout(DEFAULT_CONNECT_TIMEOUT).build();
    }

    @Override
    public LlmCompletion complete(LlmRequest request, Consumer<LlmChunk> onChunk) {
        long startedAt = System.nanoTime();
        HttpRequest httpRequest = buildHttpRequest(request);

        List<LlmChunk> chunks = new ArrayList<>();
        AiSectionSplitter splitter = new AiSectionSplitter();
        Usage usage = new Usage();
        String[] providerRequestId = new String[1];
        String[] actualModel = new String[1];
        long[] firstChunkAt = new long[1];
        int[] sequence = new int[] {1};
        int[] reasoningChars = new int[1];

        HttpResponse<InputStream> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw LlmProviderException.timeout("调用供应商超时（" + timeout.toSeconds() + "秒）");
        } catch (IOException e) {
            throw LlmProviderException.providerUnavailable("无法连接供应商：" + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmProviderException(
                    LlmErrorCategory.SYSTEM, "AI_CALL_INTERRUPTED", "调用被中断", false);
        }

        try (InputStream body = response.body();
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            if (response.statusCode() != 200) {
                throw errorOfStatus(response.statusCode(), readAll(reader));
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    // SSE 的注释行与空行（心跳）不属于数据帧。
                    continue;
                }
                String payload = line.substring("data:".length()).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    break;
                }
                JsonNode frame;
                try {
                    frame = mapper.readTree(payload);
                } catch (IOException e) {
                    throw new LlmProviderException(
                            LlmErrorCategory.SYSTEM,
                            "AI_PROVIDER_MALFORMED_FRAME",
                            "供应商返回了无法解析的数据帧",
                            e);
                }
                if (frame.hasNonNull("error")) {
                    throw errorOfBody(frame.get("error"));
                }
                if (providerRequestId[0] == null && frame.hasNonNull("id")) {
                    providerRequestId[0] = frame.get("id").asText();
                }
                if (frame.hasNonNull("model")) {
                    actualModel[0] = frame.get("model").asText();
                }
                if (frame.hasNonNull("usage") && !frame.get("usage").isNull()) {
                    usage.read(frame.get("usage"));
                }
                // 收尾包（只带 usage）的 choices 是空数组，不是错误。
                JsonNode choices = frame.get("choices");
                if (choices == null || !choices.isArray() || choices.isEmpty()) {
                    continue;
                }
                JsonNode delta = choices.get(0).get("delta");
                if (delta == null || delta.isNull()) {
                    continue;
                }
                if (delta.hasNonNull("reasoning_content")) {
                    // 思维链只计数、不进报告。见类注释。
                    reasoningChars[0] += delta.get("reasoning_content").asText().length();
                }
                if (!delta.hasNonNull("content")) {
                    continue;
                }
                String text = delta.get("content").asText();
                if (text.isEmpty()) {
                    continue;
                }
                for (AiSectionSplitter.Segment segment : splitter.accept(text)) {
                    emit(segment, sequence[0]++, chunks, onChunk);
                    if (firstChunkAt[0] == 0) {
                        firstChunkAt[0] = System.nanoTime();
                    }
                }
            }
        } catch (HttpTimeoutException e) {
            throw LlmProviderException.timeout("读取供应商流式响应超时");
        } catch (IOException e) {
            throw LlmProviderException.providerUnavailable(
                    "读取供应商流式响应失败：" + e.getClass().getSimpleName());
        }

        for (AiSectionSplitter.Segment segment : splitter.finish()) {
            emit(segment, sequence[0]++, chunks, onChunk);
            if (firstChunkAt[0] == 0) {
                firstChunkAt[0] = System.nanoTime();
            }
        }

        long endedAt = System.nanoTime();
        if (chunks.isEmpty()) {
            // 一个片段都没切出来，分两种成因，报错必须能区分它们：
            //   1) 模型只产出了思维链就停了——推理模型的常见失败，max_tokens 被推理过程吃光，
            //      正文一个字都没轮到。此时调大 max_tokens 才有用。
            //   2) 模型输出了正文但没有章节标记——参数没用，要改的是 Prompt 或换模型。
            // 合并成一句"缺少必填章节"会让上面两种完全不同的处置方式看起来一样。
            if (reasoningChars[0] > 0) {
                throw new LlmProviderException(
                        LlmErrorCategory.DATA,
                        "AI_OUTPUT_TRUNCATED",
                        "供应商只产出了思维链（" + reasoningChars[0] + " 字符）而没有正文，"
                                + "通常是 maxOutputTokens=" + request.maxOutputTokens() + " 被推理过程耗尽",
                        false);
            }
            throw new LlmProviderException(
                    LlmErrorCategory.DATA,
                    "AI_SECTION_MARKERS_MISSING",
                    "供应商输出中没有章节分隔标记，无法切分章节",
                    false);
        }
        Duration firstChunkLatency =
                firstChunkAt[0] == 0
                        ? Duration.ofNanos(endedAt - startedAt)
                        : Duration.ofNanos(firstChunkAt[0] - startedAt);
        return new LlmCompletion(
                providerRequestId[0] == null ? "unknown" : providerRequestId[0],
                actualModel[0] == null ? modelCode : actualModel[0],
                chunks,
                usage.toLlmUsage(),
                firstChunkLatency,
                Duration.ofNanos(endedAt - startedAt));
    }

    private void emit(
            AiSectionSplitter.Segment segment,
            int sequence,
            List<LlmChunk> chunks,
            Consumer<LlmChunk> onChunk) {
        LlmChunk chunk = new LlmChunk(segment.section(), segment.delta(), sequence);
        chunks.add(chunk);
        onChunk.accept(chunk);
    }

    private HttpRequest buildHttpRequest(LlmRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.modelCode());
        body.put("stream", true);
        body.put("max_tokens", request.maxOutputTokens());

        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.systemPrompt());

        StringBuilder user = new StringBuilder(request.userPrompt());
        List<LlmEvidence> candidates = request.evidenceCandidates();
        if (candidates.isEmpty()) {
            user.append("\n证据候选：无。数据不足时只说明缺什么，不要给出结论。");
        } else {
            user.append("\n证据候选（只能引用以下编号）：\n");
            for (LlmEvidence evidence : candidates) {
                user.append('[').append(evidence.evidenceNo()).append("] ")
                        .append(evidence.evidenceType())
                        .append("｜").append(nullToEmpty(evidence.sourceTitle()))
                        .append("｜").append(nullToEmpty(evidence.evidenceSummary()))
                        .append('\n');
            }
        }
        messages.addObject().put("role", "user").put("content", user.toString());

        return HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }

    /**
     * 把 HTTP 状态映射成错误类别。
     *
     * <p>401 / 403 归为 {@link LlmErrorCategory#SYSTEM} 而不是可重试类别：密钥错配重试多少次
     * 都是同样的结果，而归入 PROVIDER 会让重试逻辑白跑三轮再失败。
     */
    private LlmProviderException errorOfStatus(int status, String body) {
        String detail = abbreviate(body);
        if (status == 401 || status == 403) {
            return new LlmProviderException(
                    LlmErrorCategory.SYSTEM,
                    "AI_PROVIDER_AUTH_FAILED",
                    "供应商拒绝了凭证（HTTP " + status + "）",
                    false);
        }
        if (status == 429) {
            return LlmProviderException.rateLimited("供应商限流（HTTP 429）");
        }
        if (looksLikeSafetyRejection(body)) {
            return LlmProviderException.safetyRejected("供应商内容安全策略拒绝：" + detail);
        }
        if (status >= 500) {
            return LlmProviderException.providerUnavailable("供应商故障（HTTP " + status + "）");
        }
        // 其余 4xx 都是请求本身有问题（模型名不存在、参数非法）。
        // 归为 DATA（不可重试）：重试同一个请求不会变成合法请求。
        return new LlmProviderException(
                LlmErrorCategory.DATA,
                "AI_PROVIDER_REJECTED_REQUEST",
                "供应商拒绝了请求（HTTP " + status + "）：" + detail,
                false);
    }

    private LlmProviderException errorOfBody(JsonNode error) {
        String type = error.hasNonNull("type") ? error.get("type").asText() : "";
        String code = error.hasNonNull("code") ? error.get("code").asText() : "";
        String message = error.hasNonNull("message") ? error.get("message").asText() : "";
        String joined = (type + " " + code + " " + message).toLowerCase(Locale.ROOT);
        if (looksLikeSafetyRejection(joined)) {
            return LlmProviderException.safetyRejected("供应商内容安全策略拒绝：" + abbreviate(message));
        }
        if (joined.contains("rate") || joined.contains("throttl") || joined.contains("quota")) {
            return LlmProviderException.rateLimited("供应商流内报错（限流）：" + abbreviate(message));
        }
        return LlmProviderException.providerUnavailable(
                "供应商流内报错（" + code + "）：" + abbreviate(message));
    }

    private static boolean looksLikeSafetyRejection(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("data_inspection")
                || lower.contains("datainspection")
                || lower.contains("content_filter")
                || lower.contains("contentfilter")
                || lower.contains("sensitive");
    }

    /** 错误信息要能进 {@code ai_task.error_message}，因此截断且不包含请求正文。 */
    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() <= 200 ? single : single.substring(0, 200) + "…";
    }

    private static String readAll(BufferedReader reader) {
        StringBuilder builder = new StringBuilder();
        try {
            String line;
            while ((line = reader.readLine()) != null && builder.length() < 4096) {
                builder.append(line);
            }
        } catch (IOException e) {
            return builder.toString();
        }
        return builder.toString();
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    /** 用量累加器。供应商把所有计数放在最后一个数据帧里，也可能拆开放。 */
    private static final class Usage {

        private int promptTokens;
        private int completionTokens;
        private int cachedTokens;
        private int totalTokens;

        void read(JsonNode usage) {
            promptTokens = Math.max(promptTokens, intOf(usage, "prompt_tokens"));
            completionTokens = Math.max(completionTokens, intOf(usage, "completion_tokens"));
            totalTokens = Math.max(totalTokens, intOf(usage, "total_tokens"));
            JsonNode details = usage.get("prompt_tokens_details");
            if (details != null && details.isObject()) {
                cachedTokens = Math.max(cachedTokens, intOf(details, "cached_tokens"));
            }
        }

        LlmUsage toLlmUsage() {
            // LlmUsage 的构造器要求 total >= prompt + completion。推理模型的
            // reasoning token 可能被单独计价、也可能不计入 total，与其让构造器抛
            // IllegalArgumentException（那是"本侧系统故障"的语义，会掩盖真实原因），
            // 不如在这里补足并保持"总量不小于分项之和"这一不变量。
            int total = Math.max(totalTokens, promptTokens + completionTokens);
            if (total == 0) {
                return LlmUsage.EMPTY;
            }
            return new LlmUsage(promptTokens, completionTokens, cachedTokens, total);
        }

        private static int intOf(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value == null || !value.isNumber() ? 0 : value.asInt();
        }
    }
}
