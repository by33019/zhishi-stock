package cn.zhishi.stock.aiworker;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 任务执行器入口（第 9 个模块，架构 §3.2 的部署单元）。
 *
 * <h2>它为什么是独立进程</h2>
 * <ol>
 *   <li><b>生命周期不同</b>：这里是 Consumer Group 的至少一次消费 + 心跳 + 超时恢复，
 *       与 Web 的请求-响应节奏无关。放进 {@code stock-backend} 会让一次模型调用
 *       占住一个 Web 线程池线程，而它的耗时由外部供应商决定。
 *   <li><b>不暴露 HTTP</b>：架构要求它不开放公开业务接口，只负责执行与写 Redis 事件流，
 *       由 {@code stock-backend} 把事件中继成 SSE。没有 Web 依赖，也就没有
 *       Spring Security 与鉴权链路需要同步。
 * </ol>
 *
 * <h2>{@code @MapperScan} 必须在每个可启动模块各声明一次</h2>
 * 这里要拿到的 Mapper 分布在 {@code cn.zhishi.stock.ai}（任务的六张表）与
 * {@code cn.zhishi.stock.news}（资讯证据的查询链路）两个包下，
 * {@code @SpringBootApplication} 的默认扫描范围（本模块的包）覆盖不到。
 *
 * <p>加了 {@code annotationClass = Mapper.class} 之后只有带 {@code @Mapper} 的接口
 * 会成为 Bean，因此范围放宽到两个模块根不会顺带把它们的二十多个领域端口接口
 * （{@code AiTaskStore} / {@code NewsProvider} / …）也注册成 Mapper——
 * 那些接口一旦被 MyBatis 代理，正常装配就会被搅坏，而且报错指向的是别处。
 *
 * <p><b>漏掉这一项的线上症状是整个应用起不来</b>
 * （{@code No qualifying bean of type '...Mapper' available}），看起来像"配置类写错了"
 * 而不是"扫描范围没覆盖"。而 {@code ApplicationContextRunner} 写的配置测试
 * **永远发现不了**它——那种测试不走扫描。唯一有效的验证是直接读注解：
 * 见 {@code StockAiWorkerApplicationTest}。M3-04 已经踩过一次。
 */
@EnableScheduling
@SpringBootApplication
@MapperScan(
        basePackages = {"cn.zhishi.stock.ai", "cn.zhishi.stock.news"},
        annotationClass = Mapper.class)
public class StockAiWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockAiWorkerApplication.class, args);
    }
}
