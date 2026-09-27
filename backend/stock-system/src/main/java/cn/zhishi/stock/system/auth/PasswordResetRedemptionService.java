package cn.zhishi.stock.system.auth;

import java.time.Clock;
import java.util.Objects;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 密码重置兑换（契约 §5 AUTH-07 {@code POST /auth/password/reset}，PUBLIC）。
 *
 * <h2>所有失败都是同一句话</h2>
 * 这是公开端点：邮箱不存在、凭证过期、verificationId 不匹配、code 错、
 * 凭证已被并发消费、账号被锁定或停用——对调用方一律是
 * {@code CREDENTIALS_INVALID}。区分开任何一种，都等于在帮人探测
 * "这个邮箱注册过没有"与"这张凭证还活着没有"。
 *
 * <h2>顺序：先消费，后改密</h2>
 * 一次性由 {@code consume}（Redis DEL）的返回值保证：校验通过后先原子消费，
 * 两个并发兑换只有一个能继续。若先改密后消费，两个并发都会改密成功，
 * "一次性"就成了君子协定。
 *
 * <h2>成功即全员下线</h2>
 * 契约明写"成功后递增 tokenVersion 并撤销全部刷新会话"：密码被重置意味着
 * 旧密码可能已泄露，所有现存会话（含 access token 与 refresh 家族）必须作废。
 */
public class PasswordResetRedemptionService {

    /** 所有失败共用的文案：不给探测者任何额外信息。 */
    private static final String GENERIC_FAILURE = "重置凭证无效或已过期";

    private final UserAccountRepository accounts;
    private final PasswordResetCredentialStore credentials;
    private final RefreshSessionStore sessions;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public PasswordResetRedemptionService(
            UserAccountRepository accounts,
            PasswordResetCredentialStore credentials,
            RefreshSessionStore sessions,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.accounts = accounts;
        this.credentials = credentials;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** AUTH-07：兑换一次性凭证，重置密码并全员下线。 */
    @Transactional
    public PasswordResetResult reset(
            String email, String verificationId, String verificationCode, String newPassword) {
        if (isBlank(email) || isBlank(verificationId)
                || isBlank(verificationCode) || isBlank(newPassword)) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, GENERIC_FAILURE);
        }

        UserAccount account = accounts.findByEmail(email.trim())
                .orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_CREDENTIALS, GENERIC_FAILURE));
        if (account.status() != UserAccount.Status.ACTIVE) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, GENERIC_FAILURE);
        }

        PasswordResetCredentialStore.StoredResetCredential stored = credentials.find(account.id())
                .orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_CREDENTIALS, GENERIC_FAILURE));
        boolean identifierMatches = Objects.equals(stored.verificationId(), verificationId.trim());
        boolean secretMatches = RefreshTokenHashing.sha256(verificationCode.trim())
                .equals(stored.credentialHash());
        if (!identifierMatches || !secretMatches) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, GENERIC_FAILURE);
        }
        if (!credentials.consume(account.id())) {
            // 校验通过到消费之间被并发的另一次兑换抢先：本次没有凭证可用。
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, GENERIC_FAILURE);
        }

        accounts.updatePasswordHashBumpingTokenVersion(account.id(), passwordEncoder.encode(newPassword));
        int revoked = sessions.revokeAllForUser(account.id());
        return new PasswordResetResult(true, revoked);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** AUTH-07 的响应（契约：{@code reset}、{@code revokedSessionCount}）。 */
    public record PasswordResetResult(boolean reset, int revokedSessionCount) {
    }
}
