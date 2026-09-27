package cn.zhishi.stock.admin.application;

import cn.zhishi.stock.admin.domain.AdminRoleStore;
import cn.zhishi.stock.admin.domain.RoleQuery;
import cn.zhishi.stock.admin.domain.RoleSummary;
import cn.zhishi.stock.common.api.PageData;

/**
 * 角色只读用例（契约 §16.2 ADM-ROL-01）。
 *
 * <p>只交付列表。ADM-USR-03/06 需要一份"可选角色"，而角色增删改（ADM-ROL-02~06）
 * 不在本轮范围内——列表是让那两处真正可用的最小集合。
 */
public class AdminRoleService {

    private final AdminRoleStore roles;

    public AdminRoleService(AdminRoleStore roles) {
        this.roles = roles;
    }

    public PageData<RoleSummary> list(RoleQuery query) {
        var page = roles.page(query);
        return new PageData<>(
                page.items(),
                query.page(),
                query.size(),
                page.total(),
                (int) ((page.total() + query.size() - 1) / query.size()),
                (long) query.page() * query.size() < page.total());
    }
}
