package cn.zhishi.stock.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import cn.zhishi.stock.system.auth.AccessTokenBlacklist;
import cn.zhishi.stock.system.auth.JwtAccessTokenService;
import cn.zhishi.stock.system.auth.UserAccountRepository;
import cn.zhishi.stock.backend.web.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class SecurityConfigurationTest {

  /**
   * 契约标为 PUBLIC 的只读行情接口，游客必须能直接访问——首页与板块页在未登录时就要出数。
   *
   * <p>逐条列出而不是用一个宽前缀：`/api/v1/**` 之类的写法会把将来的写操作一起放开，
   * 而这类"顺手放宽"不会有任何测试变红。
   */
  @Test
  void keepsPublicMarketDataReadEndpointsOpenToGuests() {
    new WebApplicationContextRunner()
        .withUserConfiguration(SecurityConfiguration.class, TestEndpoints.class)
        .withBean(JwtAuthenticationFilter.class, () -> new JwtAuthenticationFilter(
            mock(JwtAccessTokenService.class), mock(AccessTokenBlacklist.class),
            mock(UserAccountRepository.class)))
        .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(TraceIdFilter.class, TraceIdFilter::new)
        .run(context -> {
          assertThat(context).hasNotFailed();
          var mvc = MockMvcBuilders.webAppContextSetup(context)
              .apply(springSecurity())
              .addFilters(context.getBean(TraceIdFilter.class))
              .build();
          for (String path : new String[] {
              "/api/v1/stock-rankings",
              "/api/v1/sector-rankings",
              "/api/v1/sectors",
              "/api/v1/sectors/sim-bk0006",
              "/api/v1/sectors/sim-bk0006/quote",
              "/api/v1/sectors/sim-bk0006/constituents",
              "/api/v1/securities/sim-600000/quote"}) {
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus())
                .describedAs("游客访问 %s 应当放行", path)
                .isEqualTo(200);
          }
        });
  }

  @Test
  void keepsMarketPublicAndProtectsCurrentUserEndpoints() {
    new WebApplicationContextRunner()
        .withUserConfiguration(SecurityConfiguration.class, TestEndpoints.class)
        .withBean(JwtAuthenticationFilter.class, () -> new JwtAuthenticationFilter(
            mock(JwtAccessTokenService.class), mock(AccessTokenBlacklist.class),
            mock(UserAccountRepository.class)))
        .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(TraceIdFilter.class, TraceIdFilter::new)
        .run(context -> {
          assertThat(context).hasNotFailed();
          var mvc = MockMvcBuilders.webAppContextSetup(context)
              .apply(springSecurity())
              .addFilters(context.getBean(TraceIdFilter.class))
              .build();
          mvc.perform(get("/api/v1/markets/overview"))
              .andExpect(status().isOk());
          mvc.perform(get("/api/v1/users/me"))
              .andExpect(status().isUnauthorized())
              .andExpect(jsonPath("$.success").value(false))
              .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
              .andExpect(jsonPath("$.traceId").isNotEmpty());
        });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  static class TestEndpoints {

    @Bean
    EndpointController endpointController() {
      return new EndpointController();
    }
  }

  @RestController
  static class EndpointController {

    @GetMapping("/api/v1/markets/overview")
    String market() {
      return "ok";
    }

    @GetMapping("/api/v1/stock-rankings")
    String stockRankings() {
      return "ok";
    }

    @GetMapping("/api/v1/sector-rankings")
    String sectorRankings() {
      return "ok";
    }

    @GetMapping("/api/v1/sectors")
    String sectors() {
      return "ok";
    }

    @GetMapping("/api/v1/sectors/{sectorId}")
    String sector() {
      return "ok";
    }

    @GetMapping("/api/v1/sectors/{sectorId}/quote")
    String sectorQuote() {
      return "ok";
    }

    @GetMapping("/api/v1/sectors/{sectorId}/constituents")
    String sectorConstituents() {
      return "ok";
    }

    @GetMapping("/api/v1/securities/{securityId}/quote")
    String securityQuote() {
      return "ok";
    }

    @GetMapping("/api/v1/users/me")
    String currentUser() {
      return "ok";
    }
  }
}
