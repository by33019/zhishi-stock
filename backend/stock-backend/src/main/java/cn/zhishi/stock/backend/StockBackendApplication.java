package cn.zhishi.stock.backend;

import org.mybatis.spring.annotation.MapperScan;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(
        scanBasePackages = "cn.zhishi.stock",
        exclude = UserDetailsServiceAutoConfiguration.class)
/**
 * 扫描整个 {@code cn.zhishi.stock.system}（用户域），而不是逐个包列举：
 * 加了 {@code annotationClass = Mapper.class} 之后只有带 {@code @Mapper} 的接口会成为 Bean，
 * 因此范围放宽不会顺带把无关接口注册进来，而新增一个 Mapper 也不必再回来改这里。
 */
@MapperScan(basePackages = "cn.zhishi.stock.system", annotationClass = Mapper.class)
public class StockBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockBackendApplication.class, args);
    }
}
