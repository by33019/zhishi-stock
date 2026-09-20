package cn.zhishi.stock.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class StockJobApplicationTest {

    @Test
    void declaresBootApplicationEntryPoint() {
        assertThat(StockJobApplication.class).hasAnnotation(SpringBootApplication.class);
    }

    /**
     * 定时采集要落库，就必须能拿到 {@code stock-news} 的 Mapper。
     *
     * <p>这是**唯一**能发现"扫描范围漏了新模块"的地方：
     * {@code ApplicationContextRunner} 直接注册配置类，根本不走 {@code @MapperScan}，
     * 所以 {@link JobConfigurationTest} 永远看不到这个问题。线上症状是
     * {@code No qualifying bean of type ...Mapper available}——整个应用起不来，
     * 而报错指向的是配置类，不是"扫描范围"（M3-01 已踩过一次）。
     *
     * <p>{@code annotationClass} 不能省：只给 {@code basePackages} 会让 MyBatis
     * 把包下**所有**接口都注册成 Mapper，而资讯域里有二十多个领域端口接口，
     * 它们会被当成 Mapper 生成代理，把正常装配搅坏。
     */
    @Test
    void scansTheNewsMappers() {
        MapperScan scan = StockJobApplication.class.getAnnotation(MapperScan.class);

        assertThat(scan).isNotNull();
        assertThat(scan.basePackages()).contains("cn.zhishi.stock.news");
        assertThat(scan.annotationClass()).isEqualTo(Mapper.class);
    }
}
