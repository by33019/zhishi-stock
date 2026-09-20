package cn.zhishi.stock.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.ai.application.AiContextPreview;
import cn.zhishi.stock.ai.application.AiContextPreviewRequest;
import cn.zhishi.stock.ai.application.AiContextPreviewService;
import cn.zhishi.stock.ai.application.InvalidAiContextQueryException;
import cn.zhishi.stock.ai.application.InvalidAiTargetException;
import cn.zhishi.stock.ai.domain.AiContextTarget;
import cn.zhishi.stock.ai.domain.AiContextType;
import cn.zhishi.stock.ai.domain.AiDataCutoff;
import cn.zhishi.stock.ai.domain.AiSceneCatalog;
import cn.zhishi.stock.ai.domain.AiTargetRole;
import cn.zhishi.stock.ai.domain.AiTargetType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * AI 接口契约（{@code RESTful-API.md} §13.1 AI-01 / AI-02）。
 *
 * <h2>这里钉的是 HTTP 面</h2>
 * 路由、请求体绑定、统一壳与字段名、异常到状态码与业务码的映射。
 * 业务规则（场景目标矩阵、区间校验、数据类别取舍）由
 * {@code AiContextPreviewServiceTest} 的 38 项覆盖，在这里重造一套只会得到
 * 两份会各自漂移的夹具。
 *
 * <p>{@link AiSceneCatalog} 用**真实实例**而不是桩：AI-01 的响应内容就是这份静态规则表，
 * 桩掉它等于把被测对象换成自己写的假数据——那样"契约返回 5 个场景"就成了一句空话。
 *
 * <p>两条"响应体不含内部 Prompt"的断言刻意用**整个响应文本**做子串检查，
 * 而不是 {@code jsonPath("$.data.systemPrompt").doesNotExist()}：后者只证明"这个路径下没有"，
 * 换个嵌套层级就失效了。
 *
 * <p>序列化器与其它契约测试同形（{@code featuresToDisable(WRITE_DATES_AS_TIMESTAMPS)}）：
 * 独立 {@code MockMvc} 不继承应用的 Jackson 配置，少了这一句时间字段会变成数组。
 */
class AiControllerContractTest {

  private static final Clock CLOCK = Clock.fixed(
      Instant.parse("2026-09-20T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  private static final OffsetDateTime CUTOFF =
      OffsetDateTime.of(2026, 9, 18, 15, 0, 0, 0, ZoneOffset.ofHours(8));

  // ---------- AI-01 ----------

  @Test
  @DisplayName("AI-01 返回 5 个场景，字段与契约 §13.1 逐一对齐")
  void returnsSceneCatalogContract() throws Exception {
    mvc(mock(AiContextPreviewService.class)).perform(get("/api/v1/ai/scenes"))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Trace-Id"))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.traceId").isNotEmpty())
        .andExpect(jsonPath("$.timestamp").isNotEmpty())
        .andExpect(jsonPath("$.data.length()").value(5))
        .andExpect(jsonPath("$.data[0].scene").value("MARKET"))
        .andExpect(jsonPath("$.data[0].name").isNotEmpty())
        .andExpect(jsonPath("$.data[0].description").isNotEmpty())
        .andExpect(jsonPath("$.data[0].allowedTargetTypes[0]").value("MARKET"))
        .andExpect(jsonPath("$.data[0].minTargets").value(1))
        .andExpect(jsonPath("$.data[0].maxTargets").value(1))
        .andExpect(jsonPath("$.data[0].questionMaxLength").value(500))
        // defaultRange 是档位而不是日期区间：AI-01 是静态目录，返回日期会依赖时钟
        .andExpect(jsonPath("$.data[0].defaultRange.presets.length()").value(3))
        .andExpect(jsonPath("$.data[0].defaultRange.presets[0]").value("LAST_1_TRADING_DAY"))
        .andExpect(jsonPath("$.data[0].defaultRange.defaultPreset").value("LAST_5_TRADING_DAYS"))
        .andExpect(jsonPath("$.data[0].defaultRange.maxCustomDays").value(365))
        // COMPARE 是唯一多标的场景
        .andExpect(jsonPath("$.data[4].scene").value("COMPARE"))
        .andExpect(jsonPath("$.data[4].minTargets").value(2))
        .andExpect(jsonPath("$.data[4].maxTargets").value(3));
  }

  /** 契约 §13.1：AI-01 是公开目录，但**不**返回任何内部 Prompt 或模板正文。 */
  @Test
  @DisplayName("AI-01 响应不含内部 Prompt 字段")
  void sceneCatalogCarriesNoInternalPrompt() throws Exception {
    mvc(mock(AiContextPreviewService.class)).perform(get("/api/v1/ai/scenes"))
        .andExpect(status().isOk())
        // 先确认响应里确实有内容：只断言"不含某字段"时，一个返回空目录的
        // 实现会让这条永远为真，而它恰恰是最需要被抓住的那种实现
        .andExpect(jsonPath("$.data.length()").value(5))
        .andExpect(content().string(not(containsString("systemPrompt"))))
        .andExpect(content().string(not(containsString("userPrompt"))))
        .andExpect(content().string(not(containsString("promptTemplate"))));
  }

  // ---------- AI-02 ----------

  @Test
  @DisplayName("AI-02 返回数据摘要：目标、数据类别与截止时间、资讯条数、降级说明、能否生成")
  void returnsContextPreviewContract() throws Exception {
    AiContextPreviewService service = mock(AiContextPreviewService.class);
    when(service.preview(any())).thenReturn(preview(true));

    mvc(service).perform(post("/api/v1/ai/context-previews")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scene\":\"STOCK\",\"targets\":["
                + "{\"targetType\":\"SECURITY\",\"targetId\":\"stub-600519\","
                + "\"targetRole\":\"PRIMARY\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.canGenerate").value(true))
        .andExpect(jsonPath("$.data.newsCount").value(2))
        .andExpect(jsonPath("$.data.targets.length()").value(1))
        .andExpect(jsonPath("$.data.targets[0].targetType").value("SECURITY"))
        .andExpect(jsonPath("$.data.targets[0].targetId").value("stub-600519"))
        .andExpect(jsonPath("$.data.targets[0].targetCode").value("600519"))
        .andExpect(jsonPath("$.data.targets[0].targetName").value("模拟证券600519"))
        .andExpect(jsonPath("$.data.targets[0].targetRole").value("PRIMARY"))
        .andExpect(jsonPath("$.data.dataCategories.length()").value(2))
        .andExpect(jsonPath("$.data.dataCategories[0].category").value("QUOTE"))
        .andExpect(jsonPath("$.data.dataCategories[0].dataCutoffAt")
            .value("2026-09-18T15:00:00+08:00"))
        .andExpect(jsonPath("$.data.dataCategories[1].category").value("NEWS"))
        .andExpect(jsonPath("$.data.limitations.length()").value(1));
  }

  /**
   * 内部代理键**不得**出现在响应里。
   *
   * <p>{@code storageId} 是 bigint 代理键，前端拿它拼跳转链接会 404
   * （M2-06 / M2-11 已各踩过一次），而且不会有任何后端测试变红。
   */
  @Test
  @DisplayName("AI-02 不返回内部代理键 storageId")
  void previewCarriesNoStorageId() throws Exception {
    AiContextPreviewService service = mock(AiContextPreviewService.class);
    when(service.preview(any())).thenReturn(preview(true));

    mvc(service).perform(post("/api/v1/ai/context-previews")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scene\":\"STOCK\",\"targets\":[]}"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("storageId"))))
        .andExpect(content().string(not(containsString("987654321"))));
  }

  @Test
  @DisplayName("AI-02 响应不含内部 Prompt 或未授权正文")
  void previewCarriesNoInternalPrompt() throws Exception {
    AiContextPreviewService service = mock(AiContextPreviewService.class);
    when(service.preview(any())).thenReturn(preview(false));

    mvc(service).perform(post("/api/v1/ai/context-previews")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scene\":\"STOCK\",\"targets\":[]}"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("systemPrompt"))))
        .andExpect(content().string(not(containsString("userPrompt"))))
        .andExpect(content().string(not(containsString("contentHash"))));
  }

  /** 请求体必须原样交给用例层：字段名写错时接口不会报错，只会静默用默认值。 */
  @Test
  @DisplayName("AI-02 请求体原样透传给用例层")
  void passesPreviewRequestBodyThrough() throws Exception {
    AiContextPreviewService service = mock(AiContextPreviewService.class);
    when(service.preview(any())).thenReturn(preview(true));

    mvc(service).perform(post("/api/v1/ai/context-previews")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scene\":\"COMPARE\",\"targets\":["
                + "{\"targetType\":\"SECURITY\",\"targetId\":\"stub-600519\","
                + "\"targetRole\":\"PRIMARY\"},"
                + "{\"targetType\":\"SECURITY\",\"targetId\":\"stub-000001\","
                + "\"targetRole\":\"COMPARISON\"}],"
                + "\"analysisStartAt\":\"2026-09-01T00:00:00+08:00\","
                + "\"analysisEndAt\":\"2026-09-18T15:00:00+08:00\"}"))
        .andExpect(status().isOk());

    ArgumentCaptor<AiContextPreviewRequest> captor =
        ArgumentCaptor.forClass(AiContextPreviewRequest.class);
    verify(service).preview(captor.capture());
    AiContextPreviewRequest captured = captor.getValue();
    assertThat(captured.scene()).isEqualTo("COMPARE");
    assertThat(captured.targets()).hasSize(2);
    assertThat(captured.targets().get(0).targetType()).isEqualTo("SECURITY");
    assertThat(captured.targets().get(0).targetId()).isEqualTo("stub-600519");
    assertThat(captured.targets().get(0).targetRole()).isEqualTo("PRIMARY");
    assertThat(captured.targets().get(1).targetRole()).isEqualTo("COMPARISON");
    assertThat(captured.analysisStartAt())
        .isEqualTo(OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8)));
    assertThat(captured.analysisEndAt()).isEqualTo(CUTOFF);
  }

  @Test
  @DisplayName("非法目标 → 400，业务码 AI_TARGET_INVALID")
  void mapsInvalidTargetTo400() throws Exception {
    AiContextPreviewService service = mock(AiContextPreviewService.class);
    when(service.preview(any())).thenThrow(
        InvalidAiTargetException.invalid("场景 COMPARE 需要恰好 1 个 PRIMARY 目标，实际 0 个"));

    mvc(service).perform(post("/api/v1/ai/context-previews")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scene\":\"COMPARE\",\"targets\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("AI_TARGET_INVALID"))
        .andExpect(jsonPath("$.message").value(containsString("PRIMARY")))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  /** 场景码或区间不合法属于"请求参数错了"，与"目标选错了"分开报码。 */
  @Test
  @DisplayName("场景或区间不合法 → 400，业务码 INVALID_REQUEST")
  void mapsInvalidQueryTo400() throws Exception {
    AiContextPreviewService service = mock(AiContextPreviewService.class);
    when(service.preview(any())).thenThrow(
        InvalidAiContextQueryException.unknownScene("NOT_A_SCENE"));

    mvc(service).perform(post("/api/v1/ai/context-previews")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scene\":\"NOT_A_SCENE\",\"targets\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").value(containsString("NOT_A_SCENE")))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  // ---------- 夹具 ----------

  private static AiContextPreview preview(boolean canGenerate) {
    List<AiContextTarget> targets = List.of(new AiContextTarget(
        AiTargetType.SECURITY, "stub-600519", "600519", "模拟证券600519",
        AiTargetRole.PRIMARY, 987654321L));
    List<AiDataCutoff> cutoffs = List.of(
        new AiDataCutoff(AiContextType.QUOTE, CUTOFF),
        new AiDataCutoff(AiContextType.NEWS, CUTOFF.minusHours(5)));
    List<String> limitations = canGenerate
        ? List.of("该证券在分析区间内没有可用资讯，报告将为受限分析")
        : List.of("证券 stub-600519 在当前行情批次中缺少快照，核心行情不完整");
    return new AiContextPreview(targets, cutoffs, 2, limitations, canGenerate);
  }

  private static MockMvc mvc(AiContextPreviewService service) {
    ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
    return MockMvcBuilders.standaloneSetup(new AiController(new AiSceneCatalog(), service, CLOCK))
        .setControllerAdvice(new GlobalExceptionHandler(CLOCK))
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
        .addFilters(new TraceIdFilter())
        .build();
  }
}
