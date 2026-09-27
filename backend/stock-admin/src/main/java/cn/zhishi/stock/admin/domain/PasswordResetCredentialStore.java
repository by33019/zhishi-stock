package cn.zhishi.stock.admin.domain;

import java.time.Instant;

/**
 * 一次性密码重置凭证的存放端口（契约 §16.1 ADM-USR-07）。
 *
 * <h2>为什么管理员拿不到这张凭证</h2>
 * 契约明写"不允许管理员读取或指定用户最终密码"。因此这里只**存哈希**
 * （{@code credentialHash}），且没有任何读回接口：凭证的有效性将来由
 * 认证侧的兑换流程校验（AUTH 尚未实现），而不是由管理员读出来转告。
 *
 * <h2>本轮的真实边界</h2>
 * 项目里没有邮件通道，AUTH 的兑换端点也还没实现，所以这张凭证**生成了但送不出去、
 * 也还没人能兑换**。这一点必须在 API 说明与前端文案里照实写出来，
 * 不能返回一个 {@code accepted=true} 就让调用方以为"用户马上会收到邮件"。
 */
public interface PasswordResetCredentialStore {

    /** 契约未定义时长；取 30 分钟——够走完"管理员线下转达"的通常耗时，又不至于长期有效。 */
    long TTL_SECONDS = 1800;

    void save(long userId, String credentialHash, Instant expiresAt);
}
