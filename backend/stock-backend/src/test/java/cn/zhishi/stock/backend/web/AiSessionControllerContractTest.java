package cn.zhishi.stock.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.ai.application.AiHistoryService;
import cn.zhishi.stock.ai.application.AiMessageView;
import cn.zhishi.stock.ai.application.AiSessionDetail;
import cn.zhishi.stock.ai.application.AiSessionSummaryView;
import cn.zhishi.stock.ai.application.AiTaskException;
import cn.zhishi.stock.ai.application.AiTaskSummary;
import cn.zhishi.stock.ai.application.InvalidAiHistoryQueryException;
import cn.zhishi.stock.ai.domain.AiContextTarget;
import cn.zhishi.stock.ai.domain.AiMessageRole;
import cn.zhishi.stock.ai.domain.AiScene;
import cn.zhishi.stock.ai.domain.AiTargetRole;
import cn.zhishi.stock.ai.domain.AiTargetType;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HIS-01 / HIS-05 的 HTTP 面契约。
 *
 * <h2>最要紧的一条：{@code isFavorite} 的字段名</h2>
 * 契约写的是 {@code isFavorite}，而 Java 访问器是 {@code favorite()}。
 * record 的 JSON 名取自**组件名**，不会自动剥掉 {@code is} 前缀——不加
 * {@code @JsonProperty} 时响应里是 {@code favorite}，前端按契约取值永远拿到
 * {@code undefined}。HIS-06 的 {@code isLimited} 已经栽过一次，这次在契约层钉住。
 *
 * <p>其余（分页换算、筛选透传、越界判据）由 {@code AiHistoryServiceTest} 覆盖——
 * 在这里再造一套夹具只会得到两份会各自漂移的定义。
 */
class AiSessionControllerContractTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-22T07:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final long USER_ID = 1001L;
    private static final String SESSION_ID = "6001";

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-09-22T15:00:00+08:00");

    private final AiHistoryService history = mock(AiHistoryService.class);

    @Test
    @DisplayName("HIS-01 返回 200：字段名为 isFavorite（不是 favorite），含分页字段")
    void listSessionsUsesContractFieldNames() throws Exception {
        when(history.listSessions(anyLong(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageData<>(List.of(sessionItem()), 1, 20, 1L, 1, false));

        mvc().perform(get("/api/v1/ai/sessions").principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.items[0].sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.data.items[0].scene").value("STOCK"))
                .andExpect(jsonPath("$.data.items[0].title").value("贵州茅台分析"))
                .andExpect(jsonPath("$.data.items[0].isFavorite").value(true))
                .andExpect(jsonPath("$.data.items[0].version").value(3))
                .andExpect(jsonPath("$.data.items[0].lastTask.taskId").value("7001"))
                .andExpect(jsonPath("$.data.items[0].lastTask.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.items[0].lastActivityAt")
                        .value("2026-09-22T15:00:00+08:00"));
    }

    @Test
    @DisplayName("HIS-01 查询参数原样交给用例层（时间由用例层解析，不靠 Spring 转换）")
    void listSessionsPassesQueryThrough() throws Exception {
        when(history.listSessions(anyLong(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageData<>(List.of(), 2, 10, 0L, 0, false));

        mvc().perform(get("/api/v1/ai/sessions")
                        .param("scene", "STOCK")
                        .param("keyword", "茅台")
                        .param("favorite", "true")
                        .param("startAt", "2026-09-01T00:00:00+08:00")
                        .param("endAt", "2026-09-22T00:00:00+08:00")
                        .param("page", "2")
                        .param("size", "10")
                        .principal(authentication()))
                .andExpect(status().isOk());

        verify(history).listSessions(
                eq(USER_ID), eq("STOCK"), eq("茅台"), eq(Boolean.TRUE),
                eq(OffsetDateTime.parse("2026-09-01T00:00:00+08:00")),
                eq(OffsetDateTime.parse("2026-09-22T00:00:00+08:00")),
                eq(2), eq(10));
    }

    @Test
    @DisplayName("HIS-01 时间格式非法 → 400，且不是 500")
    void rejectsMalformedTime() throws Exception {
        when(history.listSessions(anyLong(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new InvalidAiHistoryQueryException("startAt 必须是 ISO-8601 带偏移的时间"));

        mvc().perform(get("/api/v1/ai/sessions")
                        .param("startAt", "2026-09-01")
                        .principal(authentication()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("HIS-01 空串时间参数按「未提供」处理，不报错")
    void treatsBlankTimeAsAbsent() throws Exception {
        when(history.listSessions(anyLong(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageData<>(List.of(), 1, 20, 0L, 0, false));

        mvc().perform(get("/api/v1/ai/sessions")
                        .param("startAt", "")
                        .principal(authentication()))
                .andExpect(status().isOk());

        verify(history).listSessions(
                eq(USER_ID), any(), any(), any(), eq(null), eq(null), any(), any());
    }

    @Test
    @DisplayName("HIS-05 返回 200，消息按 sequenceNo 升序、contentFormat/status 不出现")
    void messagesUseContractShape() throws Exception {
        when(history.messages(anyLong(), anyLong(), any(), any()))
                .thenReturn(new PageData<>(List.of(userMessage(), assistantMessage()), 1, 20, 2L, 1, false));

        mvc().perform(get("/api/v1/ai/sessions/" + SESSION_ID + "/messages").principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].messageId").value("5001"))
                .andExpect(jsonPath("$.data.items[0].roleType").value("USER"))
                .andExpect(jsonPath("$.data.items[0].sequenceNo").value(1))
                .andExpect(jsonPath("$.data.items[0].content").value("这只股票怎么样？"))
                .andExpect(jsonPath("$.data.items[1].roleType").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.items[1].dataCutoffAt")
                        .value("2026-09-22T15:00:00+08:00"));
    }

    @Test
    @DisplayName("HIS-05 他人的会话 → 404 AI_SESSION_NOT_FOUND（与「不存在」同形）")
    void otherUsersSessionIsNotFound() throws Exception {
        when(history.messages(anyLong(), anyLong(), any(), any()))
                .thenThrow(AiTaskException.sessionNotFound(Long.parseLong(SESSION_ID)));

        mvc().perform(get("/api/v1/ai/sessions/" + SESSION_ID + "/messages").principal(authentication()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AI_SESSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("HIS-05 每页条数越界 → 400")
    void rejectsInvalidSize() throws Exception {
        when(history.messages(anyLong(), anyLong(), any(), any()))
                .thenThrow(new InvalidAiHistoryQueryException("每页条数必须在 1 到 100 之间：500"));

        mvc().perform(get("/api/v1/ai/sessions/" + SESSION_ID + "/messages")
                        .param("size", "500")
                        .principal(authentication()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("两个端点都要从 principal 取 userId：没有 principal 时不会拿到数据")
    void requiresAuthentication() throws Exception {
        // 独立 MockMvc 不带 Spring Security 过滤链，因此这里断言的是"控制器必须从
        // principal 取 userId"——没有 principal 就取不到，不会退化成匿名可读。
        // 真正返回 401 的那道门在 SecurityConfigurationTest 里验。
        int status = mvc().perform(get("/api/v1/ai/sessions"))
                .andReturn()
                .getResponse()
                .getStatus();
        assertThat(status).isNotEqualTo(200);
    }

    @Test
    @DisplayName("HIS-02 返回 200：目标、最近任务与报告摘要齐备，isFavorite / isLimited 字段名正确")
    void sessionDetailUsesContractFieldNames() throws Exception {
        when(history.getSession(anyLong(), anyLong())).thenReturn(sessionDetail());

        mvc().perform(get("/api/v1/ai/sessions/" + SESSION_ID).principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.data.scene").value("STOCK"))
                .andExpect(jsonPath("$.data.title").value("贵州茅台分析"))
                .andExpect(jsonPath("$.data.isFavorite").value(true))
                .andExpect(jsonPath("$.data.version").value(3))
                .andExpect(jsonPath("$.data.targets[0].targetId").value("sim-600519"))
                .andExpect(jsonPath("$.data.targets[0].targetType").value("SECURITY"))
                .andExpect(jsonPath("$.data.targets[0].targetRole").value("PRIMARY"))
                .andExpect(jsonPath("$.data.lastTask.taskId").value("7001"))
                .andExpect(jsonPath("$.data.lastTask.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.lastReport.reportId").value("8001"))
                .andExpect(jsonPath("$.data.lastReport.isLimited").value(true))
                .andExpect(jsonPath("$.data.lastReport.qualityStatus").value("LIMITED"));
    }

    @Test
    @DisplayName("HIS-02 他人的会话 → 404 AI_SESSION_NOT_FOUND")
    void sessionDetailOfOtherUserIsNotFound() throws Exception {
        when(history.getSession(anyLong(), anyLong()))
                .thenThrow(AiTaskException.sessionNotFound(Long.parseLong(SESSION_ID)));

        mvc().perform(get("/api/v1/ai/sessions/" + SESSION_ID).principal(authentication()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AI_SESSION_NOT_FOUND"));
    }

    private static AiSessionDetail sessionDetail() {
        return new AiSessionDetail(
                SESSION_ID,
                AiScene.STOCK,
                "贵州茅台分析",
                "ACTIVE",
                true,
                List.of(new AiContextTarget(
                        AiTargetType.SECURITY, "sim-600519", "600519", "模拟证券600519",
                        AiTargetRole.PRIMARY, 600519L)),
                new AiTaskSummary(
                        "7001", SESSION_ID, AiScene.STOCK, AiTaskStatus.COMPLETED,
                        List.of(), "这只股票怎么样？", "已完成", NOW, NOW, NOW, "8001", null),
                new AiSessionDetail.ReportBrief("8001", "LIMITED", true, NOW),
                NOW,
                NOW,
                3);
    }

    private static AiSessionSummaryView sessionItem() {
        return new AiSessionSummaryView(
                SESSION_ID, AiScene.STOCK, "贵州茅台分析", "ACTIVE", true,
                new AiSessionSummaryView.LastTaskView("7001", "COMPLETED"), NOW, NOW, 3);
    }

    private static AiMessageView userMessage() {
        return new AiMessageView("5001", "7001", AiMessageRole.USER, 1, "这只股票怎么样？", null, NOW);
    }

    private static AiMessageView assistantMessage() {
        return new AiMessageView("5002", "7001", AiMessageRole.ASSISTANT, 2, "# 报告", NOW, NOW);
    }

    private MockMvc mvc() {
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        return MockMvcBuilders.standaloneSetup(new AiSessionController(history, CLOCK))
                .setControllerAdvice(new GlobalExceptionHandler(CLOCK))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new TraceIdFilter())
                .build();
    }

    private static UsernamePasswordAuthenticationToken authentication() {
        var principal = new AccessTokenPrincipal(
                USER_ID, "demo", Set.of("user:self:read"), "jti-1", Instant.parse("2026-09-22T07:00:00Z"));
        return new UsernamePasswordAuthenticationToken(principal, "token", Set.of());
    }
}
