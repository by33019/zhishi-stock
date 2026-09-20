package cn.zhishi.stock.job;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务入口。
 *
 * <p>{@code @MapperScan} 是 M3-04 加的：资讯采集要落库，就必须拿到
 * {@code stock-news} 的三个 Mapper，而它们不在 {@code cn.zhishi.stock.job} 下，
 * {@code @SpringBootApplication} 的默认扫描范围覆盖不到。
 *
 * <p>加了 {@code annotationClass = Mapper.class} 之后只有带 {@code @Mapper} 的接口
 * 会成为 Bean，因此范围放宽到模块根不会顺带把资讯域的二十多个领域端口接口
 * （{@code NewsProvider} / {@code NewsSourceStore} / …）也注册成 Mapper——
 * 那些接口一旦被 MyBatis 代理，正常装配就会被搅坏，而且报错指向的是别处。
 * 漏掉这一项的线上症状是整个应用起不来（{@code No qualifying bean of type ...Mapper available}），
 * 看起来像"配置类写错了"而不是"扫描范围没覆盖"（M3-01 已踩过一次）。
 */
@EnableScheduling
@SpringBootApplication
@MapperScan(basePackages = "cn.zhishi.stock.news", annotationClass = Mapper.class)
public class StockJobApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockJobApplication.class, args);
    }
}
