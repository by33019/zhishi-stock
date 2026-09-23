package cn.zhishi.stock.export.infrastructure;

import cn.zhishi.stock.export.application.ExportException;
import cn.zhishi.stock.export.domain.ExportCell;
import cn.zhishi.stock.export.domain.ExportColumn;
import cn.zhishi.stock.export.domain.ExportDataSource;
import cn.zhishi.stock.export.domain.ExportPolicy;
import cn.zhishi.stock.export.domain.ExportRequest;
import cn.zhishi.stock.export.domain.ExportTable;
import cn.zhishi.stock.export.domain.RankingExportFilters;
import cn.zhishi.stock.export.domain.StockRankingColumn;
import cn.zhishi.stock.market.application.RankingCriteria;
import cn.zhishi.stock.market.application.StockRankingDataset;
import cn.zhishi.stock.market.application.StockRankingQueryService;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.RankingType;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 用榜单查询用例取数（{@link ExportDataSource} 的唯一实现）。
 *
 * <h2>它一件事都不自己算</h2>
 * 筛选条件、排序口径、整批快照版本、板块成分关系，全部来自
 * {@link StockRankingQueryService#dataset}。本类只做三件事：
 * <ol>
 *   <li>把导出请求翻译成 {@link RankingCriteria}；</li>
 *   <li>按白名单把每行映射成单元格；</li>
 *   <li>行数超限时拒绝（不截断）。</li>
 * </ol>
 * 任何"顺手在这里排一下"的改动都会让文件里的顺序与页面脱钩。
 *
 * <h2>行数超限为什么不在用例层同步挡</h2>
 * 要知道有多少行只能先把数据取出来，而取数本身就是这个异步作业的主体。
 * 因此超限表现为作业 {@code FAILED}（业务码 {@code EXPORT_LIMIT_EXCEEDED}），
 * 前端拿到的是"共 N 行，请缩小范围"而不是一份被悄悄砍到 5,000 行的文件。
 */
public class StockRankingExportDataSource implements ExportDataSource {

    private final StockRankingQueryService rankings;
    private final Clock clock;

    public StockRankingExportDataSource(StockRankingQueryService rankings, Clock clock) {
        this.rankings = rankings;
        this.clock = clock;
    }

    @Override
    public ExportTable tableOf(ExportRequest request) {
        RankingExportFilters filters = request.filters();
        StockRankingDataset dataset = rankings.dataset(toCriteria(filters));

        if (dataset.rowCount() > ExportPolicy.MAX_ROWS) {
            throw ExportException.limitExceeded(dataset.rowCount());
        }

        List<StockRankingColumn> columns = StockRankingColumn.resolve(request.columns());
        List<List<ExportCell>> rows = new ArrayList<>(dataset.rowCount());
        for (var snapshot : dataset.items()) {
            List<ExportCell> row = new ArrayList<>(columns.size());
            for (StockRankingColumn column : columns) {
                row.add(column.cellOf(snapshot));
            }
            rows.add(List.copyOf(row));
        }

        return new ExportTable(
                titleOf(dataset.rankingType()),
                criteriaLines(filters, dataset),
                dataset.dataTime(),
                OffsetDateTime.now(clock),
                dataset.snapshotVersion(),
                dataStatusLabel(dataset.dataStatus()),
                columns.stream().map(StockRankingColumn::column).toList(),
                rows);
    }

    /**
     * 导出与 QTE-01 的参数逐项对应，只有 {@code page} / {@code size} 恒为 {@code null}。
     *
     * <p>{@code null} 不是"忘了传"：导出没有分页，而传一个具体值会让
     * {@link StockRankingQueryService#dataset} 的"取全量"变成"取恰好一页"。
     */
    private static RankingCriteria toCriteria(RankingExportFilters filters) {
        return new RankingCriteria(
                filters.rankingType(),
                filters.exchangeCodes(),
                filters.boardCodes(),
                filters.sectorId(),
                filters.excludeSt(),
                filters.excludeSuspended(),
                null,
                null);
    }

    /**
     * 报表标题。{@code switch} 不写 {@code default} 分支：新增一种榜单口径时它会**编译失败**，
     * 而不是悄悄产出一份标题写着"行情榜单"、内容却是别的口径的文件。
     */
    private static String titleOf(String rankingTypeCode) {
        String label = switch (RankingType.fromCode(rankingTypeCode).orElseThrow()) {
            case GAINERS -> "涨幅榜";
            case LOSERS -> "跌幅榜";
            case TURNOVER -> "成交额榜";
        };
        return "行情榜单 · " + label;
    }

    /**
     * 口径说明。**写出的是生效值，不是请求里的原样**。
     *
     * <p>"未传"与"传了 false"必须归一到同一个结论：调用方看到的是文件的筛选条件，
     * 而"是否包含 ST"这一类只有"包含 / 排除"两种可能，写"未指定"等于没写。
     * 默认值因此取自 {@link StockRankingQueryService}，不在这里另抄一份。
     */
    private static List<String> criteriaLines(
            RankingExportFilters filters, StockRankingDataset dataset) {
        List<String> lines = new ArrayList<>();
        lines.add("榜单类型：" + titleOf(dataset.rankingType()).replace("行情榜单 · ", ""));
        lines.add("交易所：" + orDefault(filters.exchangeCodes(), "全部"));
        lines.add("板块：" + orDefault(filters.boardCodes(), "全部"));
        if (filters.sectorId() != null && !filters.sectorId().isBlank()) {
            lines.add("板块成分筛选：" + filters.sectorId().trim());
        }
        boolean excludeSt = filters.excludeSt() != null
                ? filters.excludeSt()
                : StockRankingQueryService.DEFAULT_EXCLUDE_ST;
        boolean excludeSuspended = filters.excludeSuspended() != null
                ? filters.excludeSuspended()
                : StockRankingQueryService.DEFAULT_EXCLUDE_SUSPENDED;
        lines.add("ST 证券：" + (excludeSt ? "已排除" : "包含"));
        lines.add("停牌证券：" + (excludeSuspended ? "已排除" : "包含"));
        lines.add("数据行数：" + dataset.rowCount() + " 行（单次导出上限 "
                + ExportPolicy.MAX_ROWS + " 行）");
        return lines;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /**
     * 数据状态的中文说明。
     *
     * <p>用 {@code switch} 而非 {@code toString()}：文件是给人看的，
     * {@code DELAYED} 这种枚举名不该出现在报表里；同时新增状态会编译失败而不是悄悄漏掉。
     */
    private static String dataStatusLabel(MarketOverview.DataStatus status) {
        if (status == null) {
            return "无数据";
        }
        return switch (status) {
            case REALTIME -> "实时";
            case DELAYED -> "延时";
            case STALE -> "陈旧（最近有效快照）";
            case UNAVAILABLE -> "无数据";
        };
    }
}
