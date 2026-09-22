package cn.zhishi.stock.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.ai.application.AiReportDetail;
import cn.zhishi.stock.ai.application.AiReportQueryService;
import cn.zhishi.stock.ai.application.AiTaskErrorCode;
import cn.zhishi.stock.ai.application.AiTaskException;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HIS-06 报告接口契约（{@code RESTful-API.md} §13.3）。
 *
 * <h2>这里钉的是 HTTP 面</h2>
 * 路由与路径变量绑定、状态码、业务码到 HTTP 状态的映射、以及"ID 在响应里是字符串"。
 * 归属判断与三种"拿不到"的等价性由 {@code AiReportQueryServiceTest} 覆盖——
 * 在这里再造一套夹具只会得到两份会各自漂移的定义。
 *
 * <h2>为什么日期断言必须用显式 mapper</h2>
 * 独立 {@code MockMvc} 的默认 {@code ObjectMapper} 会把 {@code OffsetDateTime}
 * 序列化成 epoch 数字（已知问题 #5），断言因此会静默失真。这里显式关闭
 * {@code WRITE_DATES_AS_TIMESTAMPS}，与线上格式对齐。
 */
class AiReportControllerContractTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-22T07:02:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final long USER_ID = 1001L;
    private static final String REPORT_ID = "7332086761476098";

    private static final OffsetDateTime CUTOFF =
            OffsetDateTime.parse("2026-09-22T15:00:00+08:00");
    private static final OffsetDateTime GENERATED =
            OffsetDateTime.parse("2026-09-22T15:02:00+08:00");

    private final AiReportQueryService reports = mock(AiReportQueryService.class);

    @Test
    @DisplayName("HIS-06 返回 200，六章节、版本标识、数据截止时间齐备，ID 为字符串")
    void returnsReport() throws Exception {
        when(reports.get(Long.parseLong(REPORT_ID), USER_ID)).thenReturn(limitedDetail());

        mvc().perform(get("/api/v1/ai/reports/" + REPORT_ID).principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reportId").value(REPORT_ID))
                .andExpect(jsonPath("$.data.taskId").value("7332086769086466"))
                .andExpect(jsonPath("$.data.sessionId").value("7332086769086465"))
                .andExpect(jsonPath("$.data.coreConclusion").value("核心结论正文"))
                .andExpect(jsonPath("$.data.quoteEvidence").value("量价依据正文"))
                .andExpect(jsonPath("$.data.riskAndUncertainty").value("风险正文"))
                .andExpect(jsonPath("$.data.disclaimer").value("本内容不构成投资建议。"))
                .andExpect(jsonPath("$.data.renderedMarkdown").exists())
                .andExpect(jsonPath("$.data.qualityStatus").value("LIMITED"))
                .andExpect(jsonPath("$.data.isLimited").value(true))
                .andExpect(jsonPath("$.data.limitedReason").value("分析区间内没有可用资讯"))
                .andExpect(jsonPath("$.data.contentSchemaVersion").value("v1"))
                .andExpect(jsonPath("$.data.promptVersion").value("p2"))
                .andExpect(jsonPath("$.data.providerCode").value("DASHSCOPE"))
                .andExpect(jsonPath("$.data.modelCode").value("qwen3.8-max-0902"))
                // 日期是 ISO 文本而不是 epoch 数字：前端直接展示，不做二次换算。
                .andExpect(jsonPath("$.data.marketDataCutoffAt").value("2026-09-22T15:00:00+08:00"))
                .andExpect(jsonPath("$.data.generatedAt").value("2026-09-22T15:02:00+08:00"))
                // 反馈尚无写入路径，如实为 null，而不是编一个「中性」默认值。
                // 用 nullValue 而不是 doesNotExist：字段确实存在，只是值为 null，
                // 而 doesNotExist 期望的是"路径都不存在"，两者不是一回事。
                .andExpect(jsonPath("$.data.feedback").value(nullValue()));
    }

    @Test
    @DisplayName("HIS-06 是纯读接口，不要求 Idempotency-Key")
    void doesNotRequireIdempotencyKey() throws Exception {
        when(reports.get(anyLong(), anyLong())).thenReturn(limitedDetail());

        mvc().perform(get("/api/v1/ai/reports/" + REPORT_ID).principal(authentication()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("报告不存在 → 404 且业务码为 AI_REPORT_NOT_FOUND")
    void missingReportIsNotFound() throws Exception {
        when(reports.get(anyLong(), anyLong()))
                .thenThrow(AiTaskException.reportNotFound(Long.parseLong(REPORT_ID)));

        mvc().perform(get("/api/v1/ai/reports/" + REPORT_ID).principal(authentication()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AI_REPORT_NOT_FOUND"));
    }

    @Test
    @DisplayName("同一个 ID 下「他人的报告」与「不存在的报告」在 HTTP 面上完全同形")
    void otherUsersReportIsIndistinguishableFromMissing() throws Exception {
        long reportId = Long.parseLong(REPORT_ID);

        // 必须用**同一个** reportId 比较两种情形。文案里会回显调用方传入的 ID，
        // 用不同 ID 去比对会得到"文案不同"的假失败——而那不是泄漏：
        // ID 本来就是调用方自己给的，他早就知道。
        // 真正的泄漏只会是"存在但无权"与"不存在"在**同一 ID** 下可区分。
        doThrow(AiTaskException.reportNotFound(reportId))
                .when(reports)
                .get(anyLong(), anyLong());
        var notMine = mvc().perform(get("/api/v1/ai/reports/" + REPORT_ID).principal(authentication()))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        doThrow(AiTaskException.reportNotFound(reportId))
                .when(reports)
                .get(anyLong(), anyLong());
        var missing = mvc().perform(get("/api/v1/ai/reports/" + REPORT_ID).principal(authentication()))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // traceId 与 timestamp 描述的是"这一次响应"，本就应当不同；
        // 可比较的是业务码与文案——它们逐字相同，才说明两种"拿不到"不可区分。
        assertThat(codeOf(notMine)).isEqualTo(codeOf(missing)).isEqualTo("AI_REPORT_NOT_FOUND");
        assertThat(messageOf(notMine)).isEqualTo(messageOf(missing));
    }

    @Test
    @DisplayName("归属校验走本人的 userId：控制器把 token 里的 userId 原样传给用例层")
    void passesCallersUserIdToUseCase() throws Exception {
        when(reports.get(anyLong(), anyLong())).thenReturn(limitedDetail());

        mvc().perform(get("/api/v1/ai/reports/" + REPORT_ID).principal(authentication()))
                .andExpect(status().isOk());

        verify(reports).get(Long.parseLong(REPORT_ID), USER_ID);
    }

    @Test
    @DisplayName("路径变量不是数字 → 400，而不是 500")
    void nonNumericReportIdIsRejected() throws Exception {
        mvc().perform(get("/api/v1/ai/reports/not-a-number").principal(authentication()))
                .andExpect(status().isBadRequest());
    }

    private static String codeOf(String body) {
        return fieldOf(body, "code");
    }

    private static String messageOf(String body) {
        return fieldOf(body, "message");
    }

    private static String fieldOf(String body, String field) {
        try {
            return new ObjectMapper().readTree(body).get(field).asText();
        } catch (Exception e) {
            throw new AssertionError("响应体里没有 " + field + "：" + body, e);
        }
    }

    private static AiReportDetail limitedDetail() {
        return new AiReportDetail(
                REPORT_ID,
                "7332086769086466",
                "7332086769086465",
                "核心结论正文",
                "量价依据正文",
                null,
                null,
                "风险正文",
                "本内容不构成投资建议。",
                "# 报告\n核心结论正文\n",
                "LIMITED",
                true,
                "分析区间内没有可用资讯",
                CUTOFF,
                null,
                "v1",
                "p2",
                "DASHSCOPE",
                "qwen3.8-max-0902",
                GENERATED,
                null);
    }

    private MockMvc mvc() {
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        return MockMvcBuilders.standaloneSetup(new AiReportController(reports, CLOCK))
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
