package cn.zhishi.stock.ai.application;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * HIS-03 的响应体：更新后的会话与**新版本**。
 *
 * <h2>为什么回传新 version</h2>
 * 客户端刚用 `If-Match: 3` 改了一次，下一次还要用新版本提交。不回传的话它只能
 * 重新拉一次详情——而那次拉取与这次写入之间又可能被别人改动，等于把刚落地的
 * 乐观锁收益丢掉一半。
 *
 * <p>刻意**不**返回完整的会话详情：那要多查一次最近任务与报告，而调用方
 * （列表页）本来就有那一行的其余字段，它只需要把 title / isFavorite / version 换掉。
 *
 * <p>字段名 `isFavorite` 与契约一致，显式 `@JsonProperty` 对齐（record 的 JSON 名
 * 取自组件名，不会自动剥掉 `is` 前缀）。
 */
public record AiSessionUpdated(
        String sessionId,
        String title,
        @JsonProperty("isFavorite") boolean favorite,
        int version) {
}
