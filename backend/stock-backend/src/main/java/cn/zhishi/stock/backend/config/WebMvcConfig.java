package cn.zhishi.stock.backend.config;

import cn.zhishi.stock.backend.web.ratelimit.RateLimitInterceptor;
import cn.zhishi.stock.system.ratelimit.RequestRateLimiter;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 定制：注册全站限流拦截器（契约 §22.1）。
 *
 * <p>规则表在 {@link RateLimitInterceptor} 里；这里只负责把它挂上 MVC 生命周期——
 * 拦截器在 Controller 之前执行，限流判定因此先于任何业务逻辑。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RequestRateLimiter rateLimiter;

    public WebMvcConfig(RequestRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(rateLimiter))
                .addPathPatterns("/api/v1/**");
    }
}
