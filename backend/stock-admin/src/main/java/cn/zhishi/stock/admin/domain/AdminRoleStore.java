package cn.zhishi.stock.admin.domain;

import java.util.OptionalLong;

/**
 * 角色相关的读（契约 §16.2 ADM-ROL-01 只读 + §16.1 的超级管理员判定）。
 *
 * <h2>为什么"超级管理员"是角色名而不是新字段</h2>
 * 契约没有定义超级管理员。实现把它定义为"持有 {@code stock.admin.super-role-name}
 * （默认 {@code ADMIN}）角色的**启用**用户"。判定所需的事实只有三件：
 * 角色 id、这个用户是否持有它、当前系统里有多少启用的超管。
 * 三件都能由现有表回答，因此不需要给 {@code sys_role} 加一列——
 * 加列会让"哪个角色是超管"在数据里有两个可能不一致的答案。
 */
public interface AdminRoleStore {

    RolePage page(RoleQuery query);

    /** 超级管理员角色的 id；该角色不存在时返回空（此时任何超管保护判定都不成立）。 */
    OptionalLong superAdminRoleId();

    /** 该用户是否是**启用中的**超级管理员（角色启用、用户状态为正常）。 */
    boolean isActiveSuperAdmin(long userId);

    /** 当前系统里启用中的超级管理员总数，用于"最后一个"判定。 */
    long countActiveSuperAdmins();
}
