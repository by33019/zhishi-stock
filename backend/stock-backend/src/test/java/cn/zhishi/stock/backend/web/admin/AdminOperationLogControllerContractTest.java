package cn.zhishi.stock.backend.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.admin.application.AdminException;
import cn.zhishi.stock.admin.application.OperationLogService;
import cn.zhishi.stock.admin.domain.OperationLogDetail;
import cn.zhishi.stock.admin.domain.OperationLogEntry;
import cn.zhishi.stock.admin.domain.OperationLogQuery;
import cn.zhishi.stock.backend.web.GlobalExceptionHandler;
import cn.zhishi.stock.backend.web.TraceIdFilter;
import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
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
 * 后台操作日志接口契约（{@code RESTful-API.md} §16.2 LOG-01/02）。
 *
 * <h2>这里钉的是"参数有没有被正确读懂"</h2>
 * 时间范围的缺省与上限、脱敏、分页口径都在 {@code OperationLogServiceTest} 里，
 * 本类只回答另一类问题：HTTP 上的字符串有没有变成正确的 {@code OffsetDateTime}、
 * 非法的 {@code resultStatus} 会不会被静默忽略、业务码到状态码的映射对不对。
 *
 * <h2>非法枚举必须报错</h2>
 * {@code resultStatus} 若接收 {@code String}，拼错的取值会一路传到 SQL，
 * 然后以"零条结果"的形式返回——一个不会报错、只会骗人的答案。
 * 绑定成枚举让它在参数解析阶段就变成 400。
 */
class AdminOperationLogControllerContractTest {

    private static final long OPERATOR_ID = 9001L;
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-23T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final OperationLogService logs = mock(OperationLogService.class);
    private final ObjectMapper objectMapper = mapper();

    @Test
    void returnsTheStandardPaginationEnvelope() throws Exception {
        when(logs.list(any())).thenReturn(new PageData<>(
                List.of(entry(101L), entry(102L)), 2, 20, 42, 3, true));

        mvc().perform(get("/api/v1/admin/operation-logs").param("page", "2")
                        .principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].logId").value(101))
                .andExpect(jsonPath("$.data.items[0].operation").value("ADMIN_USER_CREATE"))
                .andExpect(jsonPath("$.data.items[0].resultStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].paramsSummary").doesNotExist())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").value(42))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(true));
    }

    /**
     * 列表不返回参数摘要。
     *
     * <p>与"详情才需要摘要"的契约一致。这一条特别重要：值一旦被序列化进列表响应，
     * 脱敏就是唯一的防线；而只要它不出现在列表里，就少一个可能泄露的面。
     */
    @Test
    void neverLeaksTheParamSummaryThroughTheList() throws Exception {
        when(logs.list(any())).thenReturn(new PageData<>(
                List.of(entry(101L)), 1, 20, 1, 1, false));

        mvc().perform(get("/api/v1/admin/operation-logs").principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].paramsSummary").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].method").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].legacyUserRef").doesNotExist());
    }

    @Test
    void parsesEveryFilterIntoTheQuery() throws Exception {
        when(logs.list(any())).thenReturn(new PageData<>(List.of(), 1, 20, 0, 0, false));

        mvc().perform(get("/api/v1/admin/operation-logs")
                        .param("userId", "7001")
                        .param("username", "admin")
                        .param("operation", "ADMIN_USER_DELETE")
                        .param("resultStatus", "DENIED")
                        .param("httpMethod", "DELETE")
                        .param("requestUri", "/api/v1/admin/users/7001")
                        .param("traceId", "trace-1")
                        .param("ip", "127.0.0.1")
                        .param("startedAt", "2026-09-01T00:00:00+08:00")
                        .param("endedAt", "2026-09-02T00:00:00+08:00")
                        .principal(authentication()))
                .andExpect(status().isOk());

        ArgumentCaptor<OperationLogQuery> captor = ArgumentCaptor.forClass(OperationLogQuery.class);
        org.mockito.Mockito.verify(logs).list(captor.capture());
        OperationLogQuery query = captor.getValue();
        assertThat(query.userId()).isEqualTo(7001L);
        assertThat(query.username()).isEqualTo("admin");
        assertThat(query.operation()).isEqualTo("ADMIN_USER_DELETE");
        assertThat(query.resultStatus())
                .describedAs("枚举在传给用例层之前转成写入侧用的那个字符串")
                .isEqualTo("DENIED");
        assertThat(query.httpMethod()).isEqualTo("DELETE");
        assertThat(query.requestUri()).isEqualTo("/api/v1/admin/users/7001");
        assertThat(query.traceId()).isEqualTo("trace-1");
        assertThat(query.ip()).isEqualTo("127.0.0.1");
        // 带偏移量的 ISO-8601 必须被原样保留，而不是被解释成服务器本地时间。
        assertThat(query.startedAt())
                .isEqualTo(OffsetDateTime.parse("2026-09-01T00:00:00+08:00"));
        assertThat(query.endedAt())
                .isEqualTo(OffsetDateTime.parse("2026-09-02T00:00:00+08:00"));
    }

    @Test
    void defaultsToTheFirstPageOfTwentyWithNoFilters() throws Exception {
        when(logs.list(any())).thenReturn(new PageData<>(List.of(), 1, 20, 0, 0, false));

        mvc().perform(get("/api/v1/admin/operation-logs").principal(authentication()))
                .andExpect(status().isOk());

        ArgumentCaptor<OperationLogQuery> captor = ArgumentCaptor.forClass(OperationLogQuery.class);
        org.mockito.Mockito.verify(logs).list(captor.capture());
        OperationLogQuery query = captor.getValue();
        assertThat(query.page()).isEqualTo(1);
        assertThat(query.size()).isEqualTo(20);
        assertThat(query.userId()).isNull();
        assertThat(query.startedAt())
                .describedAs("时间缺省由用例层补，控制器不替它猜一个值")
                .isNull();
    }

    @Test
    void rejectsAnUnknownResultStatusInsteadOfIgnoringIt() throws Exception {
        mvc().perform(get("/api/v1/admin/operation-logs").param("resultStatus", "SUCCEEDED")
                        .principal(authentication()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void rejectsAnUnparseableTimestamp() throws Exception {
        mvc().perform(get("/api/v1/admin/operation-logs").param("startedAt", "2026-09-01")
                        .principal(authentication()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void mapsATooWideRangeTo400WithItsOwnCode() throws Exception {
        when(logs.list(any()))
                .thenThrow(AdminException.logRangeTooWide(
                        OperationLogQuery.MAX_RANGE_DAYS, OperationLogQuery.DEFAULT_RANGE_DAYS));

        mvc().perform(get("/api/v1/admin/operation-logs").principal(authentication()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ADMIN_LOG_RANGE_TOO_WIDE"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("90")));
    }

    @Test
    void returnsTheRedactedDetail() throws Exception {
        when(logs.detail(101L)).thenReturn(detail(
                "userId=7001;nickName=小新;password=***"));

        mvc().perform(get("/api/v1/admin/operation-logs/{logId}", 101L)
                        .principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logId").value(101))
                .andExpect(jsonPath("$.data.paramsSummary").value("userId=7001;nickName=小新;password=***"))
                .andExpect(jsonPath("$.data.method").value("AdminUserController.create"))
                .andExpect(jsonPath("$.data.legacyUserRef").value("legacy-9"));
    }

    @Test
    void mapsAMissingLogTo404() throws Exception {
        when(logs.detail(anyLong())).thenThrow(AdminException.logNotFound(404L));

        mvc().perform(get("/api/v1/admin/operation-logs/{logId}", 404L)
                        .principal(authentication()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADMIN_LOG_NOT_FOUND"));
    }

    // ---------- 装配 ----------

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new AdminOperationLogController(logs, CLOCK))
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
                OPERATOR_ID, "admin", Set.of("sys:log:list"), "jti-1",
                Instant.parse("2030-01-01T00:00:00Z"), 0);
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    private static OperationLogEntry entry(long logId) {
        return new OperationLogEntry(
                logId, 7001L, "admin", "ADMIN_USER_CREATE", null,
                "/api/v1/admin/users", "POST", "SUCCESS", "127.0.0.1", "trace-1",
                OffsetDateTime.parse("2026-09-23T01:00:00+08:00"));
    }

    private static OperationLogDetail detail(String paramsSummary) {
        return new OperationLogDetail(
                101L, 7001L, "legacy-9", "admin", "ADMIN_USER_CREATE", 12,
                "AdminUserController.create", "/api/v1/admin/users", "POST", "SUCCESS",
                paramsSummary, "127.0.0.1", "trace-1",
                OffsetDateTime.parse("2026-09-23T01:00:00+08:00"));
    }
}
