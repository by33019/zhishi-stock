package cn.zhishi.stock.system.auth;

import java.time.Instant;
import java.util.Optional;

/**
 * 一次性密码重置凭证的存放端口（契约 §16.1 ADM-USR-07 签发 + §5 AUTH-07 兑换）。
 *
 * <h2>端口为什么在认证域，而不是签发它的后台域</h2>
 * 凭证由后台签发（ADM-USR-07），但**消费方是认证侧的兑换流程**（AUTH-07）——
 * 端口属于它的消费者。后台模块本来就依赖认证域，签发侧照常注入本端口；
 * 反过来（认证依赖后台）会让一个公开端点背上整个管理面。
 *
 * <h2>verificationId 与 credential 的分工</h2>
 * 管理员签发时一次生成两个值：{@code verificationId}（本次签发的标识）与
 * {@code credential}（一次性秘密，Redis 只存哈希）。两者经邮件通道一起送达用户
 * （项目暂无邮件通道，签发接口如实提示"未送达"）。兑换时 AUTH-07 同时校验
 * 两者——单靠一枚 30 分钟有效的秘密不够时，identifer 让"撞库一枚旧凭证"不成立。
 *
 * <h2>一次性由 {@link #consume} 保证</h2>
 * 校验通过后必须先原子消费（Redis DEL 的返回值），再改密码——两个并发兑换
 * 只有一个能消费成功，后到的拿不到任何"重置成功"的响应。
 */
public interface PasswordResetCredentialStore {

    /** 契约未定义时长；取 30 分钟——够走完"管理员线下转达"的通常耗时，又不至于长期有效。 */
    long TTL_SECONDS = 1800;

    void save(long userId, String verificationId, String credentialHash, Instant expiresAt);

    /** 读回当前有效凭证；过期或从未签发时为空（Redis TTL 到期即消失）。 */
    Optional<StoredResetCredential> find(long userId);

    /**
     * 原子消费：删除并报告是否真的删掉了。返回 {@code false} 意味着凭证已被
     * 并发的另一次兑换用掉，本次必须失败。
     */
    boolean consume(long userId);

    /** 存储中的凭证（哈希形态）。 */
    record StoredResetCredential(String verificationId, String credentialHash) {
    }
}
