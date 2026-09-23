package cn.zhishi.stock.backend.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.common.api.ApiResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 请求了不存在的接口路径时的对外行为（契约 §23.1：404 = 资源不存在）。
 *
 * <h2>这条契约为什么值得单独钉住</h2>
 * 真实运行时，未匹配的路径会被 Spring Boot 的静态资源处理器接走，它找不到文件就抛
 * {@link NoResourceFoundException}。若没人接住这个异常，它就会落进兜底的
 * {@code @ExceptionHandler(Exception.class)}，对外报 **500 {@code INTERNAL_ERROR}「服务暂时不可用」**。
 *
 * <p>那个谎报的代价很具体：本地联调时容器里的 jar 比工作区代码旧（改了接口没重新构建），
 * 新接口就会以 500 出现。"服务暂时不可用"会把人引向"服务是不是崩了、要不要重启"，
 * 而真正要看的只有一句"这个路径在运行的版本里不存在"。
 *
 * <h2>为什么用探针控制器而不是直接请求一个不存在的路径</h2>
 * {@code standaloneSetup} 不注册静态资源处理器，因此在那里请求不存在的路径只会得到
 * 一个不带响应体的 404，**根本不会抛出**上面那个异常——用那种写法测不到真实路径。
 * 这里让探针控制器抛出与生产**同一个异常类型**，验证的是异常类型的映射与响应体形状。
 * 真实的"未匹配路径 → 404"由端到端实测覆盖（见交付说明）。
 */
class UnknownEndpointHandlingTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-23T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  /** 模拟静态资源处理器：找不到资源时抛的正是这个异常。 */
  @RestController
  static class MissingResourceProbe {

    @GetMapping("/api/v1/__no-such-endpoint")
    ApiResponse<Void> missing() throws NoResourceFoundException {
      throw new NoResourceFoundException(HttpMethod.GET, "/api/v1/__no-such-endpoint");
    }
  }

  @Test
  void reportsMissingEndpointAs404WithNotFoundCodeRatherThan500() throws Exception {
    mvc()
        .perform(get("/api/v1/__no-such-endpoint"))
        .andExpect(status().isNotFound())
        .andExpect(header().exists("X-Trace-Id"))
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        // 文案要能把人推向"版本不一致"，而不是"服务挂了"。
        .andExpect(jsonPath("$.message").value(
            org.hamcrest.Matchers.containsString("确认请求路径")))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  private static MockMvc mvc() {
    return MockMvcBuilders.standaloneSetup(new MissingResourceProbe())
        .setControllerAdvice(new GlobalExceptionHandler(CLOCK))
        .setMessageConverters(new MappingJackson2HttpMessageConverter(
            Jackson2ObjectMapperBuilder.json().build()))
        .addFilters(new TraceIdFilter())
        .build();
  }
}
