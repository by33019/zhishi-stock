package cn.zhishi.stock.ai.application;

import java.time.OffsetDateTime;

/**
 * HIS-04 的响应体。
 *
 * <h2>为什么必须回传 {@code purgeAfter}</h2>
 * `ai_session` 用 `deleted_at` / `purge_after` 两个列表达"软删"，并由
 * `ck_ai_session_delete_state` 约束它们必须同时有值。前端要能告诉用户
 * "这条记录会在哪天彻底消失"——只回一个 `deleted: true` 的话，用户看到的
 * 是"删了，但数据还在"，而它到底会留多久无从得知。
 *
 * <h2>{@code deleted} 恒为 {@code true}</h2>
 * 该字段存在是因为契约 §HIS-04 的响应里有它，而不是因为存在"删除失败但返回 200"
 * 的路径：版本冲突与已删除都抛 409 / 404，不会走到这里。保留它是为了让响应形状
 * 与契约一致，前端不必为"成功但 deleted=false"写分支。
 */
public record AiSessionDeletion(boolean deleted, OffsetDateTime purgeAfter) {
}
