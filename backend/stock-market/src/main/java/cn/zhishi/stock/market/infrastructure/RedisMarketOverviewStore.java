package cn.zhishi.stock.market.infrastructure;

import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketOverviewStore;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisMarketOverviewStore implements MarketOverviewStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisMarketOverviewStore.class);
    private static final String KEY_PREFIX = "market:overview:";

    private final StringRedisTemplate redis;
    private final MarketOverviewJsonCodec codec;
    private final Duration timeToLive;

    public RedisMarketOverviewStore(
            StringRedisTemplate redis,
            MarketOverviewJsonCodec codec,
            Duration timeToLive) {
        this.redis = redis;
        this.codec = codec;
        this.timeToLive = timeToLive;
    }

    @Override
    public Optional<MarketOverview> find(String marketCode) {
        try {
            String json = redis.opsForValue().get(key(marketCode));
            return json == null ? Optional.empty() : Optional.of(codec.decode(json));
        } catch (DataAccessException | IllegalStateException exception) {
            LOGGER.warn("读取市场总览缓存失败，将回退数据库：market={}", marketCode, exception);
            return Optional.empty();
        }
    }

    @Override
    public void save(MarketOverview snapshot) {
        try {
            redis.opsForValue().set(key(snapshot.marketCode()), codec.encode(snapshot), timeToLive);
        } catch (DataAccessException exception) {
            LOGGER.warn("写入市场总览缓存失败：market={}", snapshot.marketCode(), exception);
        }
    }

    private String key(String marketCode) {
        return KEY_PREFIX + marketCode;
    }
}
