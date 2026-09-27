package cn.zhishi.stock.admin.application;

/**
 * ADM-USR-09 的结果。
 *
 * @param deleted             是否完成逻辑删除
 * @param revokedSessionCount 被置为 REVOKED 的刷新令牌条数
 */
public record UserDeletion(boolean deleted, int revokedSessionCount) {
}
