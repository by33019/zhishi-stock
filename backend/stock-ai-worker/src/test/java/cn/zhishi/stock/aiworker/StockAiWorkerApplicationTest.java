package cn.zhishi.stock.aiworker;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

class StockAiWorkerApplicationTest {

    @Test
    void declaresBootApplicationEntryPoint() {
        assertThat(StockAiWorkerApplication.class).hasAnnotation(SpringBootApplication.class);
    }

    /** 消费与恢复扫描都靠定时器；漏了它两个任务都不会跑，而应用"启动成功"。 */
    @Test
    void enablesScheduling() {
        assertThat(StockAiWorkerApplication.class).hasAnnotation(EnableScheduling.class);
    }

    /**
     * 执行器要落库（任务的六张表）并读资讯证据，就必须能拿到
     * {@code stock-ai} 与 {@code stock-news} 两个包下的 Mapper。
     *
     * <p>这是**唯一**能发现"扫描范围漏了新模块"的地方：
     * {@code ApplicationContextRunner} 直接注册配置类，根本不走 {@code @MapperScan}，
     * 所以 {@link AiWorkerConfigurationTest} 永远看不到这个问题。线上症状是
     * {@code No qualifying bean of type ...Mapper available}——整个应用起不来，
     * 而报错指向的是配置类，不是"扫描范围"（M3-01 已踩过一次）。
     *
     * <p>{@code annotationClass} 不能省：只给 {@code basePackages} 会让 MyBatis
     * 把两个包下**所有**接口都注册成 Mapper，而 AI 域与资讯域各有二十多个领域端口接口
     * （{@code AiTaskStore} / {@code NewsProvider} / …），它们会被当成 Mapper 生成代理，
     * 把正常装配搅坏。
     */
    @Test
    void scansTheAiAndNewsMappers() {
        MapperScan scan = StockAiWorkerApplication.class.getAnnotation(MapperScan.class);

        assertThat(scan).isNotNull();
        assertThat(scan.basePackages())
                .contains("cn.zhishi.stock.ai", "cn.zhishi.stock.news");
        assertThat(scan.annotationClass()).isEqualTo(Mapper.class);
    }
}
