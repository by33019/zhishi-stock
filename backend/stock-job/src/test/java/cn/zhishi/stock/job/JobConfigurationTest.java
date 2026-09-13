package cn.zhishi.stock.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.zhishi.stock.market.application.MarketIngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

class JobConfigurationTest {

    @Test
    void assemblesMarketIngestionUseCase() {
        new ApplicationContextRunner()
                .withUserConfiguration(JobConfiguration.class)
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("stock.market.scenario=NORMAL")
                .run(context -> assertThat(context).hasSingleBean(MarketIngestionService.class));
    }
}
