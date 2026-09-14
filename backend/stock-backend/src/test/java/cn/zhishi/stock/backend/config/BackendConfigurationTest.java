package cn.zhishi.stock.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.zhishi.stock.market.application.MarketOverviewQueryService;
import cn.zhishi.stock.backend.security.JwtAuthenticationFilter;
import cn.zhishi.stock.backend.web.TraceIdFilter;
import cn.zhishi.stock.system.auth.AuthenticationService;
import cn.zhishi.stock.system.auth.RefreshSessionService;
import cn.zhishi.stock.system.auth.SysUserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

class BackendConfigurationTest {

  @Test
  void assemblesAuthenticationAndMarketUseCasesFromInfrastructurePorts() {
    new ApplicationContextRunner()
        .withUserConfiguration(BackendConfiguration.class)
        .withBean(SysUserMapper.class, () -> mock(SysUserMapper.class))
        .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
        .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withPropertyValues(
            "stock.auth.jwt-secret=0123456789abcdef0123456789abcdef",
            "stock.market.scenario=NORMAL")
        .run(context -> {
          assertThat(context).hasSingleBean(AuthenticationService.class);
          assertThat(context).hasSingleBean(RefreshSessionService.class);
          assertThat(context).hasSingleBean(MarketOverviewQueryService.class);
          assertThat(context).hasSingleBean(JwtAuthenticationFilter.class);
          assertThat(context).hasSingleBean(TraceIdFilter.class);
          FilterRegistrationBean<?> registration =
              context.getBean("traceIdFilterRegistration", FilterRegistrationBean.class);
          assertThat(registration.getFilter()).isSameAs(context.getBean(TraceIdFilter.class));
          assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        });
  }
}
