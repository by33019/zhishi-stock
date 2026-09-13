package cn.zhishi.stock.market.infrastructure;

import cn.zhishi.stock.market.domain.MarketOverview;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class MarketOverviewJsonCodec {

    private final ObjectMapper objectMapper;

    public MarketOverviewJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public String encode(MarketOverview snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("市场快照 JSON 序列化失败", exception);
        }
    }

    public MarketOverview decode(String json) {
        try {
            return objectMapper.readValue(json, MarketOverview.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("市场快照 JSON 反序列化失败", exception);
        }
    }
}
