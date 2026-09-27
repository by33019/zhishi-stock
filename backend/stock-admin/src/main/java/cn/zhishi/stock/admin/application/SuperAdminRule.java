package cn.zhishi.stock.admin.application;

/**
 * "最后一个超级管理员"保护（契约 §16.1 ADM-USR-05/06/09 的三处"不得"）。
 *
 * <h2>为什么是一个纯函数</h2>
 * 三处保护（锁定、移除关键角色、删除）的判据完全相同，只是"这次操作是否收回超管权限"
 * 的答案不同。把它写成布尔入参的纯函数，三个调用点就不可能各自漂移出一条"差不多"的规则；
 * 也让"超管总数 = 2 时允许、= 1 时拒绝"这种边界不用起容器就能穷举。
 *
 * <h2>规则本身是假设，不是契约</h2>
 * 契约只写了"不得锁定 / 删除最后一个超级管理员"，没有定义"超级管理员"是谁。
 * 本实现把它定义为"持有 {@code stock.admin.super-role-name}（默认 {@code ADMIN}）角色的
 * 启用用户"，判定与计数见 {@code AdminRoleStore}。这条假设必须写进接口说明。
 *
 * <h2>为什么用"启用超管数 ≤ 1"而不是"= 1"</h2>
 * 计数与判定分两次查询，中间可能有人刚被删。{@code ≤ 1} 让"已经一个都不剩"的异常状态
 * 也落在保护范围内，而不是因为不满足 {@code = 1} 而放开。
 */
public final class SuperAdminRule {

    private SuperAdminRule() {
    }

    /**
     * 这次操作是否必须被拒绝。
     *
     * @param activeSuperAdminCount    当前启用中的超级管理员总数
     * @param targetIsActiveSuperAdmin 目标用户此刻是否是启用中的超级管理员
     * @param revokesSuperAdminRights  这次操作是否收回该用户的超管身份
     *                                 （锁定 / 移除关键角色 / 逻辑删除 / 停用角色）
     */
    public static boolean blocks(
            long activeSuperAdminCount,
            boolean targetIsActiveSuperAdmin,
            boolean revokesSuperAdminRights) {
        return revokesSuperAdminRights && targetIsActiveSuperAdmin && activeSuperAdminCount <= 1;
    }
}
