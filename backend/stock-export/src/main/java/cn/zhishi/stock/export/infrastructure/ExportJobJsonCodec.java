package cn.zhishi.stock.export.infrastructure;

import cn.zhishi.stock.export.domain.ExportJob;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 导出作业记录与 JSON 之间的转换（同 {@code MarketOverviewJsonCodec} 的形状）。
 *
 * <p>刻意 {@code copy()} 一份再改，而不是直接用 Spring 的 {@code ObjectMapper}：
 * 两处开关都是**存储格式的一部分**。
 * <ul>
 *   <li>{@code WRITE_DATES_AS_TIMESTAMPS} 关掉 → 时间写成 ISO-8601 字符串，
 *       Redis 里 {@code redis-cli get} 出来能读；</li>
 *   <li>{@code ADJUST_DATES_TO_CONTEXT_TIME_ZONE} 关掉 → 反序列化时不做时区换算，
 *       否则同一个 {@code OffsetDateTime} 读回来会变成另一个瞬时，
 *       而"文件什么时候过期"正是拿它算的。</li>
 * </ul>
 * 挂在全局 {@code ObjectMapper} 上会顺带改变所有 HTTP 响应的日期格式。
 */
public class ExportJobJsonCodec {

    private final ObjectMapper objectMapper;

    public ExportJobJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public String encode(ExportJob job) {
        try {
            return objectMapper.writeValueAsString(job);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("导出作业 JSON 序列化失败", exception);
        }
    }

    public ExportJob decode(String json) {
        try {
            return objectMapper.readValue(json, ExportJob.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("导出作业 JSON 反序列化失败", exception);
        }
    }
}
