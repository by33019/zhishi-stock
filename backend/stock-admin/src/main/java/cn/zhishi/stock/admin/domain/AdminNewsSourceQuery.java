package cn.zhishi.stock.admin.domain;

import cn.zhishi.stock.news.domain.NewsSource;
import cn.zhishi.stock.news.domain.NewsSourceType;

/**
 * 资讯来源的查询条件（契约 §17.1 ADM-NEWS-01）。
 *
 * <p>与 {@code OperationLogQuery} 同一条约定：紧凑构造器只做与时间无关的夹取
 * （页码、每页条数）。来源列表没有时间维度，因此这里不需要 {@code withRange}。
 *
 * <p>筛选枚举直接复用资讯域的类型（{@link NewsSource.AuthorizationStatus} 等）：
 * 查询参数与存储值是同一套词汇，为后台另造一份筛选枚举只会在
 * "新增一种来源类型"时多出一处要改的地方，而漏改的那一处不会报错。
 */
public record AdminNewsSourceQuery(
        Long providerId,
        NewsSourceType sourceType,
        NewsSource.AuthorizationStatus authorizationStatus,
        NewsSource.SourceStatus status,
        int page,
        int size) {

    public static final int MAX_SIZE = 100;

    public AdminNewsSourceQuery {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), MAX_SIZE);
    }

    public int offset() {
        return (page - 1) * size;
    }
}
