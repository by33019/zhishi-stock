package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.ai.application.AiContextPreview;
import cn.zhishi.stock.ai.application.AiContextPreviewRequest;
import cn.zhishi.stock.ai.application.AiContextPreviewService;
import cn.zhishi.stock.ai.domain.AiSceneCatalog;
import cn.zhishi.stock.ai.domain.AiSceneDefinition;
import cn.zhishi.stock.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 分析接口（{@code RESTful-API.md} §13.1 AI-01 / AI-02）。
 *
 * <h2>两个端点都要求登录</h2>
 * 它们不在 {@code SecurityConfiguration} 的任何 {@code permitAll} 白名单里，
 * 因此落到 {@code anyRequest().authenticated()}。本轮控制器**不读**当前用户——
 * 配额与历史属于 M3-07 之后；但把"需要登录"写成测试
 * （{@code SecurityConfigurationTest}），免得将来有人为放开别的公共前缀而顺手放开
 * {@code /ai/**}。
 *
 * <h2>请求参数一律在用例层解析</h2>
 * AI-02 的 {@code targetType} / {@code targetRole} 在请求体里是字符串，
 * 由 {@link AiContextPreviewService} 解析成枚举。声明成枚举会让 Spring 把
 * "取值不在白名单"转成 {@code MethodArgumentTypeMismatchException}，
 * 语义上混同为"参数格式错误"（同 STK-01 / NEWS-01 的处理方式）。
 *
 * <h2>响应里没有内部 Prompt</h2>
 * 契约 §13.1 明确 AI-02「不返回完整内部 Prompt 或未授权正文」。
 * 这条由**类型**保证：{@link AiContextPreview} 上没有承载 prompt 的字段，
 * 因此不存在"忘了过滤"的窗口。
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final AiSceneCatalog sceneCatalog;
    private final AiContextPreviewService previews;
    private final Clock clock;

    public AiController(
            AiSceneCatalog sceneCatalog, AiContextPreviewService previews, Clock clock) {
        this.sceneCatalog = sceneCatalog;
        this.previews = previews;
        this.clock = clock;
    }

    /** AI-01：可用场景目录。静态规则表，不含日期，因此不依赖时钟。 */
    @GetMapping("/scenes")
    public ApiResponse<List<AiSceneDefinition>> scenes(HttpServletRequest request) {
        return success(sceneCatalog.definitions(), request);
    }

    /** AI-02：上下文预览——在正式消耗配额前告知将使用哪些数据。 */
    @PostMapping("/context-previews")
    public ApiResponse<AiContextPreview> preview(
            @RequestBody AiContextPreviewRequest body, HttpServletRequest request) {
        return success(previews.preview(body), request);
    }

    private <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return ApiResponse.success(data, TraceIdFilter.current(request), OffsetDateTime.now(clock));
    }
}
