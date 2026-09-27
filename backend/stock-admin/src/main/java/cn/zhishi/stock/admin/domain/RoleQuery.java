package cn.zhishi.stock.admin.domain;

import java.util.List;

/**
 * 角色列表的查询条件（契约 §16.2 ADM-ROL-01）。
 *
 * @param keyword 模糊匹配角色名 / 描述
 * @param status  状态；{@code null} 表示全部
 * @param page    页码，从 1 开始
 * @param size    每页条数
 */
public record RoleQuery(String keyword, Integer status, int page, int size) {

    public static final int MAX_SIZE = 100;

    public RoleQuery {
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
