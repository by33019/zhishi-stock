package cn.zhishi.stock.admin.domain;

import java.util.List;

/**
 * 一页用户 + 过滤后的总数。
 *
 * <p>{@code total} 必须是**过滤后**的总数（与 {@code WHERE} 同条件、不含 {@code LIMIT}），
 * 而不是全表条数：用全表条数算总页数，筛完之后页码会指向一片空白，
 * 而调用方无法从响应里看出这件事。
 */
public record AdminUserPage(List<AdminUserSummary> items, long total) {

    public AdminUserPage {
        items = List.copyOf(items);
    }
}
