package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.ai.application.AiTaskEventSink;
import cn.zhishi.stock.ai.domain.AiTaskEvent;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@link AiTaskEventSink} 的 SSE 实现。
 *
 * <h2>{@code id:} 用任务内序号，不用 Redis 消息 ID</h2>
 * 浏览器把最后一个 {@code id:} 记下来，重连时通过 {@code Last-Event-ID} 回传；
 * 而中继补发用的游标也是序号。用同一个数，就不需要"消息 ID → 序号"的映射
 * （那是第二个索引，分叉的表现是重连后丢一段或重一段）。契约 §13.4 的示例
 * 也正是 {@code id: 38} 与 {@code sequence: 38} 相等。
 *
 * <h2>{@code data} 用 {@code text/plain} 写原始 JSON 字符串</h2>
 * 载荷在写入方（Worker）就已经序列化好。若声明成 {@code application/json}，
 * Spring 会把**字符串**再序列化一次，前端拿到的是一个被引号包起来的 JSON 串。
 *
 * <h2>发送失败不抛出</h2>
 * 中继跑在自己的线程上，抛出去只会变成一条无人处理的异步异常。
 * 这里改成标记"已关闭"，让中继在下一个检查点收尾。
 */
final class SseTaskEventSink implements AiTaskEventSink {

    static final String SNAPSHOT_EVENT = "snapshot";

    private final SseEmitter emitter;
    private final AtomicBoolean closed = new AtomicBoolean();

    SseTaskEventSink(SseEmitter emitter) {
        this.emitter = emitter;
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(exception -> closed.set(true));
    }

    @Override
    public void sendSnapshot(String dataJson) {
        // snapshot 不带 id：它不是流里的事件，给它一个 id 会让客户端的游标
        // 在收到历史补发之前先跳一次。
        send(SseEmitter.event().name(SNAPSHOT_EVENT).data(dataJson, MediaType.TEXT_PLAIN));
    }

    @Override
    public void send(AiTaskEvent event) {
        send(SseEmitter.event()
                .id(Long.toString(event.sequence()))
                .name(event.type().eventName())
                .data(event.dataJson(), MediaType.TEXT_PLAIN));
    }

    @Override
    public boolean closed() {
        return closed.get();
    }

    private void send(SseEmitter.SseEventBuilder builder) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(builder);
        } catch (IOException | IllegalStateException exception) {
            // 客户端断开、或容器已经完成这个 emitter。两种情况都只需要收尾。
            closed.set(true);
        }
    }
}
