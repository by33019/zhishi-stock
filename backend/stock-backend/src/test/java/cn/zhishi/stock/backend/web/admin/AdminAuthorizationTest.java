package cn.zhishi.stock.backend.web.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 后台端点与权限码的逐行对照（契约 §16 开头："除登录身份外，每个接口还必须校验权限标识"）。
 *
 * <h2>为什么用反射核对，而不是逐条发请求</h2>
 * 要钉住的事实只有一件：**每个后台端点都声明了它该有的权限码**。
 * 用 MockMvc 逐条请求只能证明"某几条能挡住"，而漏掉的那一条不会被发现——
 * 它恰好是没人写过用例的那一条。反射直接在编译产物上核对全部端点，
 * 漏一个就红，且不需要起容器。
 *
 * <h2>"每个方法都必须有"与"码必须对"是两条断言</h2>
 * 只对比已知方法，新增的端点不会被发现；只断言"都有 @PreAuthorize"，
 * 写错码（比如把 {@code delete} 的码写成 {@code list}）不会被发现。
 * 两条一起才闭合。
 *
 * <h2>方法级鉴权本身在哪验证</h2>
 * {@code SecurityConfigurationTest} 用真实容器断言"有码放行 / 缺码 403"，
 * 证明 {@code @EnableMethodSecurity} 生效、且令牌里的权限码能驱动它。
 * 本类只管码表是否正确。
 */
class AdminAuthorizationTest {

    /** 契约 §16.1 / §16.2 的码表：控制器方法名 → 权限标识。 */
    private static final Map<String, String> USER_ENDPOINTS = endpointTable(
            "list", "sys:user:list",
            "detail", "sys:user:detail",
            "create", "sys:user:create",
            "updateProfile", "sys:user:update",
            "changeStatus", "sys:user:status",
            "replaceRoles", "sys:user:role",
            "requestPasswordReset", "sys:user:password-reset",
            "revokeSessions", "sys:user:session-revoke",
            "delete", "sys:user:delete");

    private static final Map<String, String> ROLE_ENDPOINTS = endpointTable(
            "list", "sys:role:list");

    private static final Map<String, String> OPERATION_LOG_ENDPOINTS = endpointTable(
            "list", "sys:log:list",
            "detail", "sys:log:detail");

    /**
     * 契约 §16.3 的码表。
     *
     * <p>定义列表与执行分页共用 {@code ops:job:list}——两者都是"看"，而看得到任务清单
     * 却看不到执行历史对排查毫无用处。触发与重试分开两个码，是因为前者会让平台**动一次
     * 外部数据**，后者只是把已经失败的再做一遍。
     */
    private static final Map<String, String> JOB_ENDPOINTS = endpointTable(
            "definitions", "ops:job:list",
            "trigger", "ops:job:trigger",
            "executions", "ops:job:list",
            "execution", "ops:job:detail",
            "retry", "ops:job:retry");

    /** 契约 §17.1 / §17.2 的码表（来源与关联两组资源）。 */
    private static final Map<String, String> NEWS_ENDPOINTS = endpointTable(
            "listSources", "news:source:list",
            "source", "news:source:detail",
            "createSource", "news:source:create",
            "updateSource", "news:source:update",
            "listRelations", "news:relation:list",
            "reviewRelation", "news:relation:review",
            "createRelation", "news:relation:create",
            "deleteRelation", "news:relation:delete");

    /** 契约 §19 的码表。 */
    private static final Map<String, String> AI_ENDPOINTS = endpointTable(
            "overview", "ai:ops:overview",
            "tasks", "ai:ops:task-list",
            "task", "ai:ops:task-detail",
            "cancel", "ai:ops:task-cancel",
            "usage", "ai:ops:usage",
            "feedbackStatistics", "ai:ops:feedback");

    /**
     * 后台的全部控制器。
     *
     * <p>集中成一处，是为了让后两条断言（"没有漏掉权限码"、"都挂在 /admin 下"）
     * 自动覆盖新增的控制器——它们如果各自列一遍，新控制器就不会被任何一条看到，
     * 而"新增的类没被检查"是这两条断言唯一真正的失效方式。
     */
    private static final List<Class<?>> ADMIN_CONTROLLERS = List.of(
            AdminUserController.class,
            AdminRoleController.class,
            AdminOperationLogController.class,
            AdminJobController.class,
            AdminNewsController.class,
            AdminAiController.class);

    @Test
    void everyAdminUserEndpointCarriesTheContractPermissionCode() {
        assertThat(permissionCodes(AdminUserController.class))
            .containsExactlyInAnyOrderEntriesOf(USER_ENDPOINTS);
    }

    @Test
    void everyAdminRoleEndpointCarriesTheContractPermissionCode() {
        assertThat(permissionCodes(AdminRoleController.class))
            .containsExactlyInAnyOrderEntriesOf(ROLE_ENDPOINTS);
    }

    @Test
    void everyOperationLogEndpointCarriesTheContractPermissionCode() {
        assertThat(permissionCodes(AdminOperationLogController.class))
            .containsExactlyInAnyOrderEntriesOf(OPERATION_LOG_ENDPOINTS);
    }

    @Test
    void everyJobEndpointCarriesTheContractPermissionCode() {
        assertThat(permissionCodes(AdminJobController.class))
            .containsExactlyInAnyOrderEntriesOf(JOB_ENDPOINTS);
    }

    @Test
    void everyNewsGovernanceEndpointCarriesTheContractPermissionCode() {
        assertThat(permissionCodes(AdminNewsController.class))
            .containsExactlyInAnyOrderEntriesOf(NEWS_ENDPOINTS);
    }

    @Test
    void everyAiOperationsEndpointCarriesTheContractPermissionCode() {
        assertThat(permissionCodes(AdminAiController.class))
            .containsExactlyInAnyOrderEntriesOf(AI_ENDPOINTS);
    }

    /**
     * 每个 HTTP 处理方法都必须有 {@code @PreAuthorize}。
     *
     * <p>这条断言让"新加一个后台端点忘了加权限码"变成测试失败，
     * 而不是一个任何人都能调用的接口。
     */
    @Test
    void noAdminHandlerIsLeftWithoutAPermissionCode() {
        for (Class<?> controller : ADMIN_CONTROLLERS) {
            assertThat(handlerMethodsWithoutPermission(controller))
                .describedAs("%s 里有端点没有声明权限码", controller.getSimpleName())
                .isEmpty();
        }
    }

    /** 后台类必须挂在 {@code /api/v1/admin} 下，否则路径级规则与审计前缀都对不上。 */
    @Test
    void everyAdminControllerIsMountedUnderTheAdminPrefix() {
        for (Class<?> controller : ADMIN_CONTROLLERS) {
            RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
            assertThat(mapping).describedAs("%s 缺少 @RequestMapping", controller.getSimpleName())
                .isNotNull();
            assertThat(mapping.value()[0]).startsWith("/api/v1/admin");
        }
    }

    /**
     * 页面上能看到的能力与接口能调通的能力必须来自同一批码。
     *
     * <p>{@code admin:access} 只是"显示后台入口"的判据，它不该出现在任何
     * {@code @PreAuthorize} 里——一个能进后台、却所有接口都 403 的用户，
     * 比一个看不到入口的用户更难排查。
     */
    @Test
    void noEndpointGuardsItselfWithTheMenuOnlyCode() {
        for (Class<?> controller : ADMIN_CONTROLLERS) {
            assertThat(permissionCodes(controller).values())
                .describedAs("%s 不应把菜单码当作端点权限", controller.getSimpleName())
                .doesNotContain("admin:access");
        }
    }

    private static Map<String, String> permissionCodes(Class<?> controller) {
        Map<String, String> codes = new LinkedHashMap<>();
        for (Method method : handlerMethods(controller)) {
            PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
            if (annotation != null) {
                codes.put(method.getName(), codeOf(annotation.value()));
            }
        }
        return codes;
    }

    private static List<String> handlerMethodsWithoutPermission(Class<?> controller) {
        List<String> missing = new ArrayList<>();
        for (Method method : handlerMethods(controller)) {
            if (method.getAnnotation(PreAuthorize.class) == null) {
                missing.add(method.getName());
            }
        }
        return missing;
    }

    /** 处理方法 = 带任一 {@code @*Mapping} 注解的公开方法。 */
    private static List<Method> handlerMethods(Class<?> controller) {
        List<Method> methods = new ArrayList<>();
        for (Method method : controller.getDeclaredMethods()) {
            boolean mapped = java.util.Arrays.stream(method.getAnnotations())
                .anyMatch(annotation -> annotation.annotationType().getSimpleName()
                    .endsWith("Mapping"));
            if (mapped) {
                methods.add(method);
            }
        }
        return methods;
    }

    /**
     * 从 {@code @PreAuthorize("hasAuthority('sys:user:list')")} 里取出码。
     *
     * <p>只认这一种写法：别的写法（SpEL 组合条件、{@code hasRole}）会被解析成"看不出码"，
     * 于是这条断言会红——这正是想要的：后台所有端点都应当是"一个端点一个码"，
     * 出现别的写法说明有人绕开了约定。
     */
    private static String codeOf(String expression) {
        String prefix = "hasAuthority('";
        int start = expression.indexOf(prefix);
        int end = expression.indexOf('\'', start + prefix.length());
        assertThat(start)
            .describedAs("无法从 %s 里解析权限标识", expression)
            .isEqualTo(0);
        return expression.substring(start + prefix.length(), end);
    }

    private static Map<String, String> endpointTable(String... pairs) {
        Map<String, String> table = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            table.put(pairs[i], pairs[i + 1]);
        }
        return table;
    }
}
