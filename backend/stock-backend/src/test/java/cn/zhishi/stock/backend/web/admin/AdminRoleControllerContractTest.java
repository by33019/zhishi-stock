package cn.zhishi.stock.backend.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.admin.application.AdminRoleService;
import cn.zhishi.stock.admin.domain.RoleQuery;
import cn.zhishi.stock.admin.domain.RoleSummary;
import cn.zhishi.stock.backend.web.GlobalExceptionHandler;
import cn.zhishi.stock.backend.web.TraceIdFilter;
import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 后台角色只读接口契约（{@code RESTful-API.md} §16.2 ADM-ROL-01）。
 *
 * <h2>只测这一条端点，是因为只交付了这一条</h2>
 * 角色增删改（ADM-ROL-02~06）不在本轮范围内。这里不断言"没有其它路由"——
 * 那种断言会在真正加功能时变成噪声；路由范围的核对由 {@code AdminAuthorizationTest}
 * 用 {@code @PreAuthorize} 表来做。
 *
 * <h2>这里钉的事实</h2>
 * <ul>
 *   <li>{@code PageData} 统一分页壳，字段名与用户列表一致（前端可以复用同一个表格组件）；</li>
 *   <li>{@code size} 在这里被夹到上限——越界的 {@code size} 不应该变成一次全表扫描；</li>
 *   <li>{@code userCount} / {@code permissionCount} 由后端聚合给出，不需要前端再发两次请求。</li>
 * </ul>
 *
 * <p>权限码不在这里测：{@code standaloneSetup} 没有 AOP，{@code @PreAuthorize} 不生效。
 */
class AdminRoleControllerContractTest {

    private static final long OPERATOR_ID = 9001L;
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-23T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final AdminRoleService roles = mock(AdminRoleService.class);
    private final ObjectMapper objectMapper = mapper();

    @Test
    void returnsTheStandardPaginationEnvelope() throws Exception {
        when(roles.list(any())).thenReturn(new PageData<>(
                List.of(role(100L, "ADMIN", 3L, 23L), role(101L, "USER", 42L, 1L)),
                1, 20, 43, 3, true));

        mvc().perform(get("/api/v1/admin/roles").param("page", "1").param("size", "20")
                        .principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].roleId").value(100))
                .andExpect(jsonPath("$.data.items[0].name").value("ADMIN"))
                .andExpect(jsonPath("$.data.items[0].userCount").value(3))
                .andExpect(jsonPath("$.data.items[0].permissionCount").value(23))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").value(43))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(true));
    }

    /**
     * {@code size} 的夹取必须发生在**发往 SQL 之前**。
     *
     * <p>断言的是传给仓储的查询对象，而不是响应里的 {@code size}——
     * 响应回显一个被改小的值、SQL 却按原值取，是最容易漏的一种写法。
     */
    @Test
    void clampsThePageSizeBeforeQueryingTheStore() throws Exception {
        when(roles.list(any())).thenReturn(new PageData<>(List.of(), 1, 100, 0, 0, false));

        mvc().perform(get("/api/v1/admin/roles").param("size", "9999")
                        .principal(authentication()))
                .andExpect(status().isOk());

        ArgumentCaptor<RoleQuery> captor = ArgumentCaptor.forClass(RoleQuery.class);
        org.mockito.Mockito.verify(roles).list(captor.capture());
        assertThat(captor.getValue().size()).isEqualTo(RoleQuery.MAX_SIZE);
    }

    /**
     * 空结果是 200 + 空数组，不是 404。
     *
     * <p>"这个筛选条件下没有角色"是正常答案，把它变成错误码会让前端多一条无用分支。
     */
    @Test
    void returnsAnEmptyPageRatherThanAnError() throws Exception {
        when(roles.list(any())).thenReturn(new PageData<>(List.of(), 1, 20, 0, 0, false));

        mvc().perform(get("/api/v1/admin/roles").param("keyword", "不存在的角色")
                        .principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    /** 缺省分页参数要落到与用户列表相同的默认值上，否则两个列表的翻页手感会不一致。 */
    @Test
    void defaultsToTheFirstPageOfTwenty() throws Exception {
        when(roles.list(any())).thenReturn(new PageData<>(List.of(), 1, 20, 0, 0, false));

        mvc().perform(get("/api/v1/admin/roles").principal(authentication()))
                .andExpect(status().isOk());

        ArgumentCaptor<RoleQuery> captor = ArgumentCaptor.forClass(RoleQuery.class);
        org.mockito.Mockito.verify(roles).list(captor.capture());
        assertThat(captor.getValue().page()).isEqualTo(1);
        assertThat(captor.getValue().size()).isEqualTo(20);
        assertThat(captor.getValue().hasKeyword()).isFalse();
    }

    // ---------- 装配 ----------

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new AdminRoleController(roles, CLOCK))
                .setControllerAdvice(new GlobalExceptionHandler(CLOCK))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .addFilters(new TraceIdFilter())
                .build();
    }

    private static ObjectMapper mapper() {
        return Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    private static UsernamePasswordAuthenticationToken authentication() {
        AccessTokenPrincipal principal = new AccessTokenPrincipal(
                OPERATOR_ID, "admin", Set.of("sys:role:list"), "jti-1",
                Instant.parse("2030-01-01T00:00:00Z"), 0);
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    private static RoleSummary role(long roleId, String name, long userCount, long permissionCount) {
        return new RoleSummary(roleId, name, name + " 角色", 1, userCount, permissionCount, 0);
    }
}
