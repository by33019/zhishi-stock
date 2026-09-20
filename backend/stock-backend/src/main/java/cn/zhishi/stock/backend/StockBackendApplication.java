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
 * 扫描用户域（{@code cn.zhishi.stock.system}）与资讯域（{@code cn.zhishi.stock.news}）的
 * Mapper，而不是逐个包列举：加了 {@code annotationClass = Mapper.class} 之后只有带
 * {@code @Mapper} 的接口会成为 Bean，因此范围放宽不会顺带把无关接口注册进来，
 * 而新增一个 Mapper 也不必再回来改这里。
 *
 * <p>资讯域是 M3-04 新增的第 7 个模块，它落在 {@code cn.zhishi.stock.news} 下；
 * 漏掉这一项的后果是整个 {@code ApplicationContext} 起不来
 * （{@code No qualifying bean of type ...Mapper available}），
 * 看起来像"配置类写错了"而不是"扫描范围没覆盖"（M3-01 已踩过一次）。
 */
@MapperScan(
        basePackages = {"cn.zhishi.stock.system", "cn.zhishi.stock.news"},
        annotationClass = Mapper.class)
public class StockBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockBackendApplication.class, args);
    }
}
