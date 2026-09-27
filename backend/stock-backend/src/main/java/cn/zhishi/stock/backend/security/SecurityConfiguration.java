package cn.zhishi.stock.backend.security;

import cn.zhishi.stock.backend.web.TraceIdFilter;
import cn.zhishi.stock.common.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
/**
 * {@code @EnableMethodSecurity} 让后台端点可以逐条声明 {@code @PreAuthorize}（契约 §16 开头：
 * "除登录身份外，每个接口还必须校验 Spring Security 权限标识"）。
 *
 * <p>只加路径级规则做不到这件事：路径能表达的粒度是"/admin 下的都要某个权限"，
 * 而契约给的是 <em>每个端点一个</em>权限码（`sys:user:list` 与 `sys:user:delete` 不是同一件事）。
 * 权限码本来就在 access token 里（{@code JwtAccessTokenService} 写 `permissions` claim、
 * {@code JwtAuthenticationFilter} 裸转 `GrantedAuthority`），因此 {@code hasAuthority('...')}
 * 与现有令牌完全对齐，不需要新的授权基础设施。
 */
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ObjectMapper objectMapper,
            Clock clock) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(
                                        response,
                                        objectMapper,
                                        clock,
                                        TraceIdFilter.current(request),
                                        HttpServletResponse.SC_UNAUTHORIZED,
                                        "UNAUTHORIZED",
                                        "请先登录或刷新会话"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(
                                        response,
                                        objectMapper,
                                        clock,
                                        TraceIdFilter.current(request),
                                        HttpServletResponse.SC_FORBIDDEN,
                                        "FORBIDDEN",
                                        "当前账户无权访问该资源")))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/token/refresh",
                                // AUTH-07 找回密码：持一次性凭证的匿名请求（契约 §5 标 PUBLIC）
                                "/api/v1/auth/password/reset",
                                "/actuator/health")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/markets/**")
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.GET, "/api/v1/securities", "/api/v1/securities/**")
                        .permitAll()
                        // 榜单与板块是契约 §9.1 QTE-01 / §10 SEC-01~SEC-06 标为 PUBLIC 的行情数据，
                        // 游客（未登录）的首页与板块页要直接可读；写操作不在此前缀下，因此不会顺带放开。
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/stock-rankings",
                                "/api/v1/stock-rankings/**",
                                "/api/v1/sector-rankings",
                                "/api/v1/sectors",
                                "/api/v1/sectors/**")
                        .permitAll()
                        // 资讯中心是契约 §11.1 标为 PUBLIC 的公开页面；STK-10 / SEC-07 的资讯区
                        // 也在这两个前缀下，已由上面的 securities/sectors 规则覆盖。
                        .requestMatchers(HttpMethod.GET, "/api/v1/news", "/api/v1/news/**")
                        .permitAll()
                        // 后台管理面（契约 §16）：**不放行任何 admin 路径**。
                        // 这条显式规则写下来是为了让"admin 前缀必须已认证"成为一个被测试钉住的事实，
                        // 而不是靠末尾的 anyRequest() 兜住——将来若有人放宽上面某个公共前缀，
                        // 这一条能挡住"顺手把 /admin 也放开"。
                        // 具体权限码由各控制器的 @PreAuthorize 逐个校验（见 @EnableMethodSecurity 的说明）。
                        .requestMatchers("/api/v1/admin/**")
                        .authenticated()
                        .anyRequest()
                        .authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private static void writeError(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            Clock clock,
            String traceId,
            int status,
            String code,
            String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(
                code,
                message,
                null,
                traceId,
                OffsetDateTime.now(clock)));
    }
}
