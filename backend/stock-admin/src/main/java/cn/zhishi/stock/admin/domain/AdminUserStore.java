package cn.zhishi.stock.admin.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 后台用户写的读与写（契约 §16.1 ADM-USR-01~09）。
 *
 * <h2>每个写方法都要求 {@code expectedVersion}</h2>
 * 契约给 ADM-USR-04/05/06/09 都定义了 {@code If-Match}。把版本号作为**必填参数**而不是
 * 可选参数，是为了让"忘记校验乐观锁"变成编译错误，而不是一个静默的成功写入。
 *
 * <h2>返回值是受影响行数</h2>
 * 返回 {@code 0} 有两种可能：目标不存在、或版本不匹配。用例层已经把记录读出来了，
 * 因此它能区分这两者并给出 404 / 409——仓储不做这个判断，它只知道"这一行没被改"。
 */
public interface AdminUserStore {

    AdminUserPage page(AdminUserQuery query);

    /**
     * 按 id 查未删除用户。
     *
     * <p>查询一律带 {@code deleted = 1}：契约要求不把反向逻辑删除语义暴露给前端，
     * "已删除"在接口上的表现就是查不到。
     */
    Optional<AdminUserDetail> find(long userId);

    boolean usernameExists(String username);

    /** 邮箱唯一性检查。{@code excludingUserId} 用于改资料时排除自己。 */
    boolean emailExists(String email, long excludingUserId);

    /**
     * 建号：写 {@code sys_user} 与 {@code sys_user_role}。
     *
     * <p>两条写必须同事务——只有账号没有角色的用户，在后台里看起来像"权限配错了"，
     * 而不是"创建失败"。
     *
     * @return 新用户 id
     */
    long insert(NewAdminUser user);

    int updateProfile(long userId, AdminUserPatch patch, int expectedVersion, long operatorId);

    int updateStatus(long userId, AdminUserStatus status, int expectedVersion, long operatorId);

    /**
     * 递增令牌版本（契约 §16.1 ADM-USR-05/06/08/09）。
     *
     * <p>权限码烧在 access token 里，不递增就撤不掉：被撤权 / 被锁 / 被删的用户
     * 最长还能用旧 access token 越权 15 分钟。
     */
    int bumpTokenVersion(long userId, int expectedVersion, long operatorId);

    /**
     * 原子替换角色关系（契约 §16.1 ADM-USR-06）。
     *
     * <p>先删后插，同事务；并在**同一个事务里**递增 {@code token_version}。
     * 两件事必须同生共死：权限码烧在 access token 里，若角色换成功而版本没动，
     * 被撤权者最长还能用旧令牌越权 15 分钟；若分成两次调用，调用方漏掉一次不会有任何报错。
     *
     * <p>{@code roleIds} 为空表示"清空所有角色"。
     */
    int replaceRoles(long userId, List<Long> roleIds, int expectedVersion, long operatorId);

    /**
     * 逻辑删除：按遗留反向语义把 {@code deleted} 置为 0（契约 §16.1 ADM-USR-09）。
     *
     * <p>不是物理删除——{@code sys_log} 里还留着这个用户的操作记录，
     * 物理删除会让审计里的 {@code user_id} 指向一个不存在的用户。
     */
    int softDelete(long userId, int expectedVersion, long operatorId);

    /** 校验一组角色 id 是否都有效（未删除且启用），返回其中无效的那些。 */
    List<Long> invalidRoleIds(Collection<Long> roleIds);
}
