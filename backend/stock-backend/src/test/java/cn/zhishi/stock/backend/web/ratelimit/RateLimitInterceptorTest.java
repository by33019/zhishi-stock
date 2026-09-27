package cn.zhishi.stock.backend.web.ratelimit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.system.ratelimit.RateLimitDecision;
import cn.zhishi.stock.system.ratelimit.RequestRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 限流拦截器的行为固定（契约 §22.1）。
 *
 * <h2>这里钉的事实</h2>
 * <ul>
 *   <li>命中规则的请求带着"类别:维度"去计数，并把窗口三元组写进响应头；</li>
 *   <li>超限抛出的 429 带业务码 {@code RATE_LIMITED} 与 {@code Retry-After}；</li>
 *   <li>未命中规则的路径（如登录、AI）不被本拦截器计数——它们有自己的闸门。</li>
 * </ul>
 */
class RateLimitInterceptorTest {

  private static final java.time.Clock CLOCK =
      java.time.Clock.fixed(java.time.Instant.parse("2026-09-27T02:00:00Z"), java.time.ZoneId.of("Asia/Shanghai"));

  private final RequestRateLimiter limiter = mock(RequestRateLimiter.class);
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
        .addInterceptors(new RateLimitInterceptor(limiter))
        .build();
  }

  @Test
  void countsAndExposesTheWindowHeaders() throws Exception {
    when(limiter.acquire(anyString(), anyInt(), any()))
        .thenReturn(new RateLimitDecision(true, 120, 119, 1757313060L, 0));

    mvc.perform(get("/api/v1/markets/overview"))
        .andExpect(status().isOk())
        .andExpect(header().string("RateLimit-Limit", "120"))
        .andExpect(header().string("RateLimit-Remaining", "119"))
        .andExpect(header().string("RateLimit-Reset", "1757313060"));

    verify(limiter).acquire(contains("public-market:"), eq(120), any());
  }

  /**
   * 超限：拦截器抛 {@code RateLimitExceededException}。
   *
   * <p>standalone MockMvc 不会用 ControllerAdvice 解析**拦截器**抛出的异常
   * （与 @PreAuthorize 不生效是同一类限制），因此这里断言异常本身；
   * "429 + Retry-After 头"由 {@code GlobalExceptionHandler} 的
   * {@code rateLimited} 处理器完成，下一条测试直接调用它钉住。
   */
  @Test
  void throwsRateLimitExceededWhenTheWindowIsExhausted() {
    when(limiter.acquire(anyString(), anyInt(), any()))
        .thenReturn(new RateLimitDecision(false, 60, 0, 1757313060L, 30));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> mvc.perform(get("/api/v1/markets/overview")))
        .hasCauseInstanceOf(cn.zhishi.stock.system.ratelimit.RateLimitExceededException.class);
  }

  /** 429 响应的形状：业务码 + 建议重试头（直接调用处理器，不经过 MVC）。 */
  @Test
  void theHandlerMapsTheExceptionTo429WithRetryAfter() throws Exception {
    var response = new cn.zhishi.stock.backend.web.GlobalExceptionHandler(CLOCK)
        .rateLimited(
            new cn.zhishi.stock.system.ratelimit.RateLimitExceededException(60, 1757313060L, 30),
            new org.springframework.mock.web.MockHttpServletRequest());

    org.assertj.core.api.Assertions.assertThat(response.getStatusCode().value()).isEqualTo(429);
    org.assertj.core.api.Assertions.assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("30");
    org.assertj.core.api.Assertions.assertThat(response.getHeaders().getFirst("RateLimit-Limit")).isEqualTo("60");
  }

  /** 未命中规则的路径直接放行，计数器一次都不会被调。 */
  @Test
  void skipsPathsOutsideTheRules() throws Exception {
    mvc.perform(get("/probe/unmatched")).andExpect(status().isOk());
    verifyNoInteractions(limiter);
  }

  @RestController
  static class ProbeController {

    @GetMapping("/api/v1/markets/overview")
    String market() {
      return "ok";
    }

    @GetMapping("/probe/unmatched")
    String unmatched() {
      return "ok";
    }
  }
}
