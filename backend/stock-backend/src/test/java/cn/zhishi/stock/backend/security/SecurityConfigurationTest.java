package cn.zhishi.stock.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.system.auth.AccessTokenBlacklist;
import cn.zhishi.stock.system.auth.JwtAccessTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class SecurityConfigurationTest {

  @Test
  void keepsMarketPublicAndProtectsCurrentUserEndpoints() {
    new WebApplicationContextRunner()
        .withUserConfiguration(SecurityConfiguration.class, TestEndpoints.class)
        .withBean(JwtAuthenticationFilter.class, () -> new JwtAuthenticationFilter(
            mock(JwtAccessTokenService.class), mock(AccessTokenBlacklist.class)))
        .run(context -> {
          assertThat(context).hasNotFailed();
          var mvc = MockMvcBuilders.webAppContextSetup(context)
              .apply(springSecurity())
              .build();
          mvc.perform(get("/api/v1/markets/overview"))
              .andExpect(status().isOk());
          mvc.perform(get("/api/v1/users/me"))
              .andExpect(status().isUnauthorized());
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

    @GetMapping("/api/v1/users/me")
    String currentUser() {
      return "ok";
    }
  }
}
