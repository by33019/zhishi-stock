package cn.zhishi.stock.admin.application;

/**
 * ADM-USR-08 的结果。
 *
 * @param revokedSessionCount 被置为 REVOKED 的刷新令牌条数
 * @param tokenVersion        递增后的令牌版本（旧 access token 立刻失效的依据）
 */
public record SessionRevocation(int revokedSessionCount, int tokenVersion) {
}
