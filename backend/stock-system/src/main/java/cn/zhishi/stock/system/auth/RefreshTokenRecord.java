package cn.zhishi.stock.system.auth;

import java.time.Instant;

/**
 * 一条刷新令牌记录。
 *
 * <h2>{@code tokenVersion} 为什么必须存进会话</h2>
 * 权限码烧在 access token 里，access token 的有效性靠"令牌里的 tokenVersion == 库里的
 * tokenVersion"判定（{@code JwtAuthenticationFilter}）。但**刷新令牌是长期凭证**：
 * 管理员强制下线后如果只递增库里的版本号，被下线者仍能用刷新令牌换到一张
 * "版本已经是新的"的 access token——撤销只挡住了旧令牌，挡不住那次刷新。
 * 因此刷新令牌签发时把当时的版本号一并存下，轮换时与库里比对，不一致即视为已被撤销。
 *
 * @param tokenHash    刷新令牌的 SHA-256（不存明文）
 * @param familyId     所属会话族（一次登录一个族，轮换在同一族内进行）
 * @param userId       所属用户
 * @param tokenVersion 签发时的用户令牌版本
 * @param expiresAt    过期时刻
 * @param status       状态
 */
public record RefreshTokenRecord(
        String tokenHash,
        String familyId,
        long userId,
        int tokenVersion,
        Instant expiresAt,
        Status status) {

    public RefreshTokenRecord withStatus(Status newStatus) {
        return new RefreshTokenRecord(
                tokenHash, familyId, userId, tokenVersion, expiresAt, newStatus);
    }

    public enum Status {
        ACTIVE,
        ROTATED,
        REVOKED
    }
}
