package cn.zhishi.stock.export.domain;

import java.util.List;

/**
 * 一次导出请求（契约 §9.2 EXP-01 的 Body）。
 *
 * <p>{@code columns} 为空表示"用默认列集"。刻意不在这里做校验——
 * 白名单校验属于用例层（它要给出 400 与具体是哪个 key 不合法），
 * 而领域记录只负责承载"调用方要什么"。
 */
public record ExportRequest(
        ExportType exportType,
        RankingExportFilters filters,
        List<String> columns) {

    public ExportRequest {
        filters = filters == null ? RankingExportFilters.empty() : filters;
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
