package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiReport;
import cn.zhishi.stock.ai.domain.AiReportSection;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE 事件的 {@code data} 载荷（契约 §13.4）。
 *
 * <h2>为什么载荷在这里拼，而不是在 worker 与中继各自拼</h2>
 * 六类事件里有五类由 worker 写、{@code snapshot} 由中继写，但它们的字段名是**同一份契约**。
 * 分两处写会出现"worker 发 {@code finalStatus}、中继发 {@code status}"这种不一致，
 * 而前端只会把不认识的那个字段当成不存在——不报错，只是少显示一个东西。
 *
 * <h2>{@code taskId} 是字符串</h2>
 * 业务 ID 全程用 Snowflake 字符串（架构约定），契约 §13.4 的示例也是
 * {@code "taskId":"19876543219999"}。这里发数字会让前端在大 ID 上丢精度，
 * 而丢精度只会在某一天突然出现，不会在开发时暴露。
 *
 * <h2>{@code sequence} 与 SSE 的 {@code id:} 是同一个值</h2>
 * 它就是 Redis Stream 消息 ID 的序号部分（见 {@code RedisAiTaskEventStream}）。
 * 两个计数器必然分叉，而分叉的表现是"重连后前端重复拼接已经收到的片段"。
 */
public final class AiTaskEventPayloads {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AiTaskEventPayloads() {
    }

    public static String status(long taskId, AiTaskStatus status, long sequence) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", Long.toString(taskId));
        payload.put("status", status.name());
        payload.put("progressStage", status.progressStage());
        payload.put("sequence", sequence);
        return write(payload);
    }

    public static String chunk(long taskId, AiReportSection section, String delta, long sequence) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", Long.toString(taskId));
        payload.put("section", section.name());
        payload.put("delta", delta);
        payload.put("sequence", sequence);
        return write(payload);
    }

    public static String report(long taskId, AiReport report, long sequence) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", Long.toString(taskId));
        payload.put("reportId", Long.toString(report.reportId()));
        payload.put("qualityStatus", report.qualityStatus());
        payload.put("isLimited", report.limited());
        payload.put("sequence", sequence);
        return write(payload);
    }

    public static String error(
            long taskId, String errorCode, String message, boolean retryable, long sequence) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", Long.toString(taskId));
        payload.put("errorCode", errorCode);
        payload.put("message", message);
        payload.put("retryable", retryable);
        payload.put("sequence", sequence);
        return write(payload);
    }

    public static String done(long taskId, AiTaskStatus finalStatus, long sequence) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", Long.toString(taskId));
        payload.put("finalStatus", finalStatus.name());
        payload.put("sequence", sequence);
        return write(payload);
    }

    /**
     * 建连或重连后的当前完整状态。
     *
     * <p>{@code partialContent} 只在**确实有临时片段**时给出。空字符串与"没有片段"
     * 对前端的含义不同：前者会让前端把已经渲染的文本清空成空白。
     */
    public static String snapshot(
            AiTaskSummary task, long lastSequence, String partialContent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task", task);
        payload.put("lastSequence", lastSequence);
        if (partialContent != null && !partialContent.isEmpty()) {
            payload.put("partialContent", partialContent);
        }
        return write(payload);
    }

    private static String write(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            // 载荷全部是字符串与数字，序列化失败意味着代码写错了（比如塞进了不可序列化的对象）。
            throw new IllegalStateException("AI 事件载荷无法序列化", exception);
        }
    }
}
