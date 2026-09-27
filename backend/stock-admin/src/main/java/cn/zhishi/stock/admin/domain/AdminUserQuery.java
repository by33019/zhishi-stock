package cn.zhishi.stock.admin.domain;

import java.time.OffsetDateTime;

/**
 * 后台用户列表的查询条件（契约 §16.1 ADM-USR-01）。
 *
 * <p>每个可筛字段都可以为 {@code null}，表示"不按它筛"。字段名与契约的 Query 参数一一对应，
 * 便于把"契约里有的筛选项"与"实现里真的支持的筛选项"逐项对照。
 *
 * @param keyword        模糊匹配账号 / 昵称 / 邮箱 / 手机
 * @param status         状态；{@code null} 表示全部
 * @param roleId         拥有该角色的用户
 * @param createdStartAt 创建时间下界（含）
 * @param createdEndAt   创建时间上界（含）
 * @param page           页码，从 1 开始
 * @param size           每页条数
 */
public record AdminUserQuery(
        String keyword,
        AdminUserStatus status,
        Long roleId,
        OffsetDateTime createdStartAt,
        OffsetDateTime createdEndAt,
        int page,
        int size) {

    public static final int MAX_SIZE = 100;

    /**
     * 把页码与页长夹回合法区间。
     *
     * <p>不在这里抛异常：控制器已经拒绝过非法取值，这里是兜底。
     * 让 {@code LIMIT -1} 走到 SQL 会得到 500，而契约期望的是 400——
     * 兜底成合法值至少不会把参数问题伪装成服务故障。
     */
    public AdminUserQuery {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), MAX_SIZE);
    }

    public int offset() {
        return (page - 1) * size;
    }

    public boolean hasKeyword() {
        return keyword != null && !keyword.isBlank();
    }
}
