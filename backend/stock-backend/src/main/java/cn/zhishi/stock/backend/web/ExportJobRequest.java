package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.export.application.InvalidExportRequestException;
import cn.zhishi.stock.export.domain.ExportRequest;
import cn.zhishi.stock.export.domain.ExportType;
import cn.zhishi.stock.export.domain.RankingExportFilters;
import java.util.List;

/**
 * EXP-01 的请求体（契约 §9.2）。
 *
 * <h2>为什么这里直接把 {@code RankingExportFilters} 当字段用，而不是另抄一份</h2>
 * 导出的筛选条件与 QTE-01 的查询参数是**同一个集合**（榜单类型、交易所、板块、板块成分、
 * 是否含 ST、是否含停牌）。另立一个 DTO 就要在两处同步字段名，
 * 而漏改一处的表现是"列表能筛、导出筛不动"，两边各自看都正常。
 *
 * <h2>为什么解析 {@code exportType} 放在 Web 层</h2>
 * 只有这一层能同时看到"没传字段"与"传了一个不在白名单里的值"，而这两句话对用户
 * 完全不同：前者是"少填了"，后者是"填错了"。领域层拿到的是已经解析好的枚举，
 * 不必为输入格式负责。取值清单从 {@link ExportType#expectedCodes()} 取，
 * 因此提示语不会与枚举本身脱节。
 */
public record ExportJobRequest(
        String exportType,
        RankingExportFilters filters,
        List<String> columns) {

    /** 转成领域请求。{@code filters} / {@code columns} 的 null 归一由 {@link ExportRequest} 负责。 */
    public ExportRequest toDomain() {
        return new ExportRequest(requireType(), filters, columns);
    }

    private ExportType requireType() {
        if (exportType == null || exportType.isBlank()) {
            throw new InvalidExportRequestException(
                    "exportType 必填，可选值：" + ExportType.expectedCodes());
        }
        return ExportType.fromCode(exportType).orElseThrow(() -> new InvalidExportRequestException(
                "exportType 必须为 " + ExportType.expectedCodes() + " 之一"));
    }
}
