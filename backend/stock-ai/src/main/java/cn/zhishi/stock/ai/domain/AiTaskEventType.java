package cn.zhishi.stock.ai.domain;

import java.util.Arrays;
import java.util.List;

/**
 * SSE 事件类型（契约 §13.4 的六类事件）。
 *
 * <p>{@link #eventName()} 就是 SSE 帧里的 {@code event:} 字段值。
 * 把字符串收在枚举里而不是散在 Worker 与中继两处：
 * 两侧各写一份字面量时，拼错的一方不会报错——前端只是永远收不到那类事件。
 */
public enum AiTaskEventType {

    /** 建连或重连后的当前完整状态。 */
    SNAPSHOT("snapshot"),
    /** 任务状态变化。 */
    STATUS("status"),
    /** 临时文本片段。 */
    CHUNK("chunk"),
    /** 最终报告已写入 MySQL。 */
    REPORT("report"),
    /** 失败、超时或安全拒绝。 */
    ERROR("error"),
    /** 流结束，客户端应关闭连接。 */
    DONE("done");

    private final String eventName;

    AiTaskEventType(String eventName) {
        this.eventName = eventName;
    }

    /** SSE 帧的 {@code event:} 字段值。 */
    public String eventName() {
        return eventName;
    }

    /** 是否为"流到此结束"的事件。中继收到它之后关闭连接。 */
    public boolean terminal() {
        return this == DONE;
    }

    public static List<String> names() {
        return Arrays.stream(values()).map(AiTaskEventType::eventName).toList();
    }

    public static AiTaskEventType fromName(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.trim().toLowerCase(java.util.Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.eventName.equals(normalized))
                .findFirst()
                .orElse(null);
    }
}
