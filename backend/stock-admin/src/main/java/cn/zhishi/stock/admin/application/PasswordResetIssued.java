package cn.zhishi.stock.admin.application;

/**
 * ADM-USR-07 的结果。
 *
 * <h2>{@code delivered} 必须是 {@code false}</h2>
 * 项目里没有邮件通道。凭证生成了、进了 Redis、30 分钟后过期，但**没有任何东西
 * 会把它送到用户手上**。契约只定义了 {@code accepted} / {@code maskedDestination} /
 * {@code expiresInSeconds} 三个字段，都写成"成功"的样子；因此额外给出
 * {@code delivered} 与 {@code deliveryNote}，让调用方在响应里就能看出真实状态，
 * 而不是靠读文档才知道"accepted 不代表已送达"。
 *
 * <p>{@code accepted=true} 的含义精确地是"凭证已生成并入库"，不是"已发送"。
 *
 * @param accepted           凭证是否已生成
 * @param maskedDestination  脱敏后的投递目标（账号邮箱）
 * @param expiresInSeconds   凭证有效期（秒）
 * @param delivered          是否真的送达了用户——本轮恒为 {@code false}
 * @param deliveryNote       未送达的原因（面向调用方的原话，前端直接显示，不要自己拼）
 */
public record PasswordResetIssued(
        boolean accepted,
        String maskedDestination,
        long expiresInSeconds,
        boolean delivered,
        String deliveryNote) {
}
