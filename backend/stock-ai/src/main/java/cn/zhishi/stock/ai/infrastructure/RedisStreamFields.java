package cn.zhishi.stock.ai.infrastructure;

import java.util.Arrays;
import java.util.Map;

/**
 * Redis Stream 条目的字段读取。
 *
 * <h2>为什么需要这么一个东西</h2>
 * Spring Data Redis 把条目的字段表给成 {@code Map<byte[], byte[]>}，而
 * <b>数组的 {@code equals} 是引用相等</b>：从协议回复反序列化出来的那个 key
 * 与代码里的常量 {@code byte[]} 是**两个不同的实例**，于是
 * {@code value.get(FIELD_X)} 永远返回 {@code null}。
 *
 * <p>这个坑在本域踩了两次，两次的症状都极其隐蔽——**Redis 那一侧看起来完全健康**：
 * <ul>
 *   <li>{@code RedisAiTaskQueue.receive}：每条消息都被当成"缺少 taskId"丢弃并 ACK，
 *       任务永远停在 {@code QUEUED}；而 {@code XADD} / {@code XLEN} /
 *       {@code last-delivered-id} / {@code XPENDING} 全都正常。
 *   <li>{@code RedisAiTaskEventStream}：所有事件都被跳过，
 *       {@code readAfter} 永远返回空、{@code latestSequence} 永远为空，
 *       SSE 中继一条事件都发不出去；而 {@code XLEN} 在涨。
 * </ul>
 * 两次都是单测看不见的（那边用的是桩），只有真 Redis 集成测试能暴露。
 * 所以这里收成**唯一**实现，并把这个坑写在它自己的注释里。
 */
final class RedisStreamFields {

    private RedisStreamFields() {
    }

    /**
     * 按字段名取值。
     *
     * <p><b>不能用 {@code value.get(field)}</b>——见类注释。
     */
    static byte[] fieldOf(Map<byte[], byte[]> value, byte[] field) {
        if (value == null) {
            return null;
        }
        for (Map.Entry<byte[], byte[]> entry : value.entrySet()) {
            if (Arrays.equals(field, entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
