package cn.zhishi.stock.admin.domain;

import java.util.List;

/** 一页角色 + 过滤后的总数（{@code total} 的口径与 {@link AdminUserPage} 一致）。 */
public record RolePage(List<RoleSummary> items, long total) {

    public RolePage {
        items = List.copyOf(items);
    }
}
