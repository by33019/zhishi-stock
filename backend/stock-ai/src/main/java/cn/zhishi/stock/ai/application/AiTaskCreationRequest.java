package cn.zhishi.stock.ai.application;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * AI-03 创建任务请求体（契约 §13.2 / §13.3）。
 *
 * <p>{@code sessionId} 声明为 {@code String} 而不是 {@code Long}：项目的业务 ID
 * 全程以字符串流转，在用例层显式解析能给出"sessionId 不是合法的会话 ID"这样的消息，
 * 而交给 Jackson 强制转换只会得到一句"请求体格式无效"（同 {@code AiController} 的处理方式）。
 *
 * @param sessionId 为 {@code null} 时新建会话（契约：未传 {@code sessionId} 时同时返回新会话 ID）
 */
public record AiTaskCreationRequest(
        String sessionId,
        String scene,
        List<AiTargetRequest> targets,
        OffsetDateTime analysisStartAt,
        OffsetDateTime analysisEndAt,
        String question) {

    public AiTaskCreationRequest {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
}
