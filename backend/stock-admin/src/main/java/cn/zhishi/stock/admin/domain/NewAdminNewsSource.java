package cn.zhishi.stock.admin.domain;

import cn.zhishi.stock.news.domain.NewsSource;
import cn.zhishi.stock.news.domain.NewsSourceType;
import java.time.LocalDate;

/**
 * 新建资讯来源的命令（契约 §17.1 ADM-NEWS-03 的请求体形状）。
 *
 * <h2>为什么不带 {@code authorizationStatus}</h2>
 * 契约原文："授权状态不能由客户端任意伪造为有效"。因此它不是请求体的一部分：
 * 由 {@code AdminNewsSourceService} 从授权区间推导（推导规则见该类 javadoc），
 * 与 {@code sourceId} 一样在存储边界显式传入。请求体里混进多余字段会被
 * Jackson 静默忽略（Spring Boot 默认不因未知字段失败），文档里写明即可。
 */
public record NewAdminNewsSource(
        Long providerId,
        String sourceCode,
        String sourceName,
        NewsSourceType sourceType,
        String homepageUrl,
        LocalDate rightsValidFrom,
        LocalDate rightsValidTo,
        boolean allowAiAnalysis,
        NewsSource.SourceStatus status) {
}
