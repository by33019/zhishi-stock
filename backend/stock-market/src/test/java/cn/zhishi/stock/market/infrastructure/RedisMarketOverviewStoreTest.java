package cn.zhishi.stock.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisMarketOverviewStoreTest {

  @Test
  void treatsRedisOutageAsCacheMissSoQueryCanFallBackToMysql() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("market:overview:CN"))
        .thenThrow(new RedisConnectionFailureException("redis unavailable"));
    var codec = new MarketOverviewJsonCodec(
        JsonMapper.builder().addModule(new JavaTimeModule()).build());
    var store = new RedisMarketOverviewStore(redis, codec, Duration.ofMinutes(10));

    assertThat(store.find("CN")).isEmpty();
  }
}
