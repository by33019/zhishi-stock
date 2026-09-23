package cn.zhishi.stock.export.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 一份待写出的表格：**内容**，不含任何格式。
 *
 * <h2>为什么口径与免责声明是这张表的一部分，而不是写文件时另外拼</h2>
 * 契约 §9.2 EXP-03 要求"文件包含生成时间、数据截止时间、口径和免责声明"。
 * 把它们放在写出层拼装，就会出现"表是 A 时刻算的、口径写的是 B 时刻"这种
 * 只在跨批次时才暴露的分叉。{@code dataTime} / {@code criteria} 随表一起产生，
 * 写文件的人只负责把它们排到格子里。
 *
 * <h2>{@code generatedAt} 与 {@code dataTime} 是两件事</h2>
 * 前者是"这份文件什么时候做出来的"，后者是"里面的数字截止到哪一刻"。
 * PRD §10（验收 §609）明确要求导出文件展示自己的数据截止时间，
 * 且"不以页面加载时间替代数据时间"——所以两个字段都必须存在于文件里。
 *
 * @param title           报表标题（含榜单类型），文件第一行
 * @param criteria        筛选口径，一行一条，直接写进文件的说明区
 * @param dataTime        数据截止时间：里面的数字属于哪一刻的快照
 * @param generatedAt     文件生成时间
 * @param snapshotVersion 快照版本。榜单接口返回它、导出也返回它，同一个来源
 * @param dataStatus      数据状态（{@code COMPLETE} / {@code PARTIAL} / {@code STALE} 等）
 * @param columns         列（顺序即文件里的列序）
 * @param rows            数据行，每行的单元格数与 {@code columns} 等长
 */
public record ExportTable(
        String title,
        List<String> criteria,
        OffsetDateTime dataTime,
        OffsetDateTime generatedAt,
        String snapshotVersion,
        String dataStatus,
        List<ExportColumn> columns,
        List<List<ExportCell>> rows) {

    /**
     * 免责声明。**只有这一处定义**——PRD §7.9 与验收 §25.9 都要求它出现在文件里，
     * 而"每个导出点各写一份"迟早会出现某一份少了它。
     */
    public static final String DISCLAIMER =
            "本文件由知势股票分析平台按上述数据截止时间的行情快照自动生成，仅供研究参考，"
                    + "不构成任何投资建议或买卖依据。数据口径以平台页面展示为准；"
                    + "因数据源延迟、缺失或本地系统差异产生的偏差，平台不承担相应责任。";

    public ExportTable {
        criteria = criteria == null ? List.of() : List.copyOf(criteria);
        columns = List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public int rowCount() {
        return rows.size();
    }

    public List<String> headers() {
        return columns.stream().map(ExportColumn::header).toList();
    }
}
