package cn.zhishi.stock.system.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * 幂等守卫：把一次写操作包成"同一个键 + 同一个请求体只真正执行一次"。
 *
 * <p>契约里有 8 个接口要求 {@code Idempotency-Key}（AUTH-02/03/07、WAT-02/07、EXP-01、AI-03/06/07/08），
 * 所以它是一份**可复用**实现，而不是塞在 WAT-02 里的特例。
 *
 * <p><b>为什么显式调用而不是 Filter / AOP</b>：Filter 要缓冲整个响应体才能回放，
 * 还得自己绕开 SSE 与二进制下载；而契约里要求幂等的接口全是"小请求体 + 小 JSON 响应"的写操作。
 * 显式调用让"这个接口有幂等保护"在控制器里一眼可见，且只需 mock 一个 store 就能单测。
 *
 * <p><b>顺序是先执行再落键</b>：若 {@code save} 失败（Redis 抖动），接口返回 500 而业务已成功——
 * 这是"幂等键丢失"，退化为"客户端重试可能重复创建"；
 * 反过来（先占键、业务失败后键还在）会让重试被回放成一个**并不存在的成功结果**，危害大得多。
 */
public class IdempotencyGuard {

    /** 契约 §3.7：幂等键有效窗口默认 24 小时。 */
    public static final Duration WINDOW = Duration.ofHours(24);

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    public IdempotencyGuard(IdempotencyStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    /**
     * 校验契约要求的 {@code Idempotency-Key} 请求头。
     *
     * <p>做成静态方法而不是让每个控制器自己判空：8 个接口都要这一段，
     * 复制 8 遍之后"缺失该返回 400 还是当成没有幂等保护"就会各处不同。
     */
    public static String requireKey(String header) {
        if (header == null || header.isBlank()) {
            throw new IdempotencyKeyMissingException();
        }
        return header.trim();
    }

    public <T> T execute(
            String scope,
            long userId,
            String key,
            Object requestBody,
            Class<T> responseType,
            Supplier<T> action) {
        String body = serialize(requestBody);
        IdempotencyRecord existing = store.find(scope, userId, key).orElse(null);
        if (existing != null) {
            if (!existing.requestBody().equals(body)) {
                throw new IdempotencyKeyConflictException();
            }
            return deserialize(existing.responseJson(), responseType);
        }
        T result = action.get();
        store.save(scope, userId, key, new IdempotencyRecord(body, serialize(result)));
        return result;
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("幂等记录反序列化失败", exception);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("幂等记录序列化失败", exception);
        }
    }
}
