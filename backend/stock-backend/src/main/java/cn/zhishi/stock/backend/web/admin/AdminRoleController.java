package cn.zhishi.stock.backend.web.admin;

import cn.zhishi.stock.admin.application.AdminRoleService;
import cn.zhishi.stock.admin.domain.RoleQuery;
import cn.zhishi.stock.admin.domain.RoleSummary;
import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.backend.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台角色只读（契约 §16.2 ADM-ROL-01）。
 *
 * <h2>为什么只交付 list</h2>
 * ADM-USR-03（建号时选角色）与 ADM-USR-06（替换角色）都需要一份"可选角色"，
 * 而角色增删改（ADM-ROL-02~06）不在本轮范围内。只给列表，是让那两个端点真正可用的
 * 最小集合；顺带交付 create/update 而不交付权限树与删除，会得到一套"能建不能授权"的
 * 半截功能，比不做更容易让人误用。
 *
 * <h2>没有写操作，因此没有审计</h2>
 * 契约 §22.3 的审计范围是写操作。这条接口不写 {@code sys_log} 是有意的，
 * 不是漏了——把只读查询也记进日志，会让 {@code sys_log} 从"审计"变成"访问记录"，
 * 而后者另有其表。
 */
@RestController
@RequestMapping("/api/v1/admin/roles")
public class AdminRoleController {

    private final AdminRoleService roles;
    private final Clock clock;

    public AdminRoleController(AdminRoleService roles, Clock clock) {
        this.roles = roles;
        this.clock = clock;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('sys:role:list')")
    public ApiResponse<PageData<RoleSummary>> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        PageData<RoleSummary> data = roles.list(new RoleQuery(keyword, status, page, size));
        return ApiResponse.success(
                data, TraceIdFilter.current(request), OffsetDateTime.now(clock));
    }
}
