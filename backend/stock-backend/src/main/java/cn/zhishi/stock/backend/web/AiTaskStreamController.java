package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.ai.application.AiTaskService;
import cn.zhishi.stock.ai.application.AiTaskStreamRelay;
import cn.zhishi.stock.ai.application.AiTaskSummary;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 任务事件流（契约 §13.2 AI-05 / §13.4）。
 *
 * <h2>归属校验发生在建流之前</h2>
 * {@code AiTaskService.get} 先同步跑一遍：任务不存在或不属于当前用户时抛
 * {@code AI_TASK_NOT_FOUND}，由 {@code GlobalExceptionHandler} 转成普通的 404 JSON。
 * 这正是想要的——SSE 一旦开始，响应头就发出去了，之后**无法**再表达 404；
 * 前端只会看到一个空流，而它无从区分"没有权限"和"任务还没产生事件"。
 *
 * <h2>为什么用 {@code SseEmitter} 而不是 WebFlux</h2>
 * 架构约定前端只走 Spring Boot，而本项目是 Servlet 栈。为了一条流引入 WebFlux
 * 会让两套线程模型并存。代价是**每条连接占一个线程**，所以线程池大小就是
 * 并发 SSE 连接数的上限，必须可配（{@code stock.ai.stream.threads}）。
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiTaskStreamController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiTaskStreamController.class);

    private final AiTaskService tasks;
    private final AiTaskStreamRelay relay;
    private final Executor executor;
    private final long timeoutMillis;

    public AiTaskStreamController(
            AiTaskService tasks,
            AiTaskStreamRelay relay,
            Executor aiStreamExecutor,
            @org.springframework.beans.factory.annotation.Value(
                            "${stock.ai.stream.timeout-seconds:120}")
                    long timeoutSeconds) {
        this.tasks = tasks;
        this.relay = relay;
        this.executor = aiStreamExecutor;
        this.timeoutMillis = timeoutSeconds * 1000L;
    }

    /** AI-05：流式接收任务状态与临时文本。 */
    @GetMapping(value = "/tasks/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            Authentication authentication,
            @PathVariable long taskId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        AiTaskSummary task = tasks.get(taskId, principal(authentication).userId());

        SseEmitter emitter = new SseEmitter(timeoutMillis);
        SseTaskEventSink sink = new SseTaskEventSink(emitter);
        long cursor = parseLastEventId(lastEventId);
        executor.execute(() -> relay.relay(task, cursor, sink));
        return emitter;
    }

    /**
     * {@code Last-Event-ID} 解析。
     *
     * <p>它是浏览器按 SSE 规范自动回传的，正常值一定是序号。解析不出来时**从头补发**
     * 而不是报 400：重连请求被拒会让前端陷入"一直重连、一直失败"的循环，
     * 而从头补发的代价只是多收几条它已经有的片段（前端按 {@code sequence} 去重）。
     * 但也不静默——记一条 WARN，因为那通常意味着有人手工构造了这个头。
     */
    private long parseLastEventId(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(lastEventId.trim()));
        } catch (NumberFormatException exception) {
            LOGGER.warn("Last-Event-ID 不是合法的序号，改为从头补发：{}", lastEventId);
            return 0L;
        }
    }

    private AccessTokenPrincipal principal(Authentication authentication) {
        return (AccessTokenPrincipal) authentication.getPrincipal();
    }
}
