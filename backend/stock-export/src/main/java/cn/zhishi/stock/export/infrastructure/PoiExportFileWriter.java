package cn.zhishi.stock.export.infrastructure;

import cn.zhishi.stock.export.domain.ExportCell;
import cn.zhishi.stock.export.domain.ExportColumn;
import cn.zhishi.stock.export.domain.ExportColumnFormat;
import cn.zhishi.stock.export.domain.ExportFileWriter;
import cn.zhishi.stock.export.domain.ExportTable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 用 Apache POI 把 {@link ExportTable} 写成一份 xlsx（{@link ExportFileWriter} 的唯一实现）。
 *
 * <h2>文件形状</h2>
 * <pre>
 *   行情榜单 · 涨幅榜                                  ← 标题
 *   数据截止时间：2026-09-18 15:00:00+08:00
 *   文件生成时间：2026-09-22 15:55:00+08:00
 *   快照版本：sim-2026-09-18　数据状态：实时
 *   筛选口径
 *   榜单类型：涨幅榜
 *   交易所：全部                                   ← 口径与免责声明都写进文件（契约 §9.2 EXP-03）
 *   …
 *   （空行）
 *   证券代码 | 证券名称 | …          ← 冻结窗格 + 筛选器挂在这一行
 *   …数据…
 *   本文件由知势…不构成任何投资建议…                ← 免责声明
 * </pre>
 *
 * <h2>为什么不用 EasyExcel</h2>
 * 上面这种"说明区 + 网格 + 脚注"的形状，EasyExcel 的动态表头只覆盖网格那两段，
 * 用它反而要反过来操作底层 {@code Sheet}。导出上限 5,000 行，
 * 流式写出的优势在这里用不上，而 POI 直接写是一遍成型。
 *
 * <h2>数值为什么转 {@code double} 再写</h2>
 * POI 5.1 的 {@code Cell} 没有 {@code setCellValue(BigDecimal)}。转 {@code double}
 * 在这里是安全的，判据是**有效位数**而不是数值大小：double 有约 15-17 位有效数字，
 * 而价格与比率最多 4 位小数、成交量与成交额是不超过 13 位的整数，全部落在精确区间内。
 * 将来若出现超过 15 位有效数字的列，应当改写成文本列并在表头标注量纲，
 * 而不是继续依赖这条结论。
 *
 * <h2>公式注入防护在这里是被动生效的</h2>
 * POI 的 {@code setCellValue(String)} 写出的是**字符串单元格**，Excel 不会把它当公式求值，
 * 因此 xlsx 本身的主向量已经被类型系统堵住了。{@link ExportCell} 那一层的前置单引号
 * 是契约 §22.2 要求的第二道防线（防的是"把内容复制到 CSV 再用 Excel 打开"这类二次流转）。
 */
public class PoiExportFileWriter implements ExportFileWriter {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX", Locale.ROOT);

    /** Excel 列宽单位是 1/256 个字符宽。 */
    private static final int WIDTH_UNIT = 256;
    private static final int MIN_WIDTH_CHARS = 9;
    private static final int MAX_WIDTH_CHARS = 40;

    /** 列宽取样行数：够覆盖"最长的名字/最大的数字"，又不至于为了一个极端值把列撑爆。 */
    private static final int WIDTH_SAMPLE_ROWS = 200;

    private static final String PRICE_FORMAT = "#,##0.00";
    private static final String AMOUNT_FORMAT = "#,##0";
    private static final String PERCENT_FORMAT = "0.00%";

    private static final short TITLE_FONT_POINTS = 14;
    private static final float LINE_POINTS = 15f;

    @Override
    public byte[] write(ExportTable table) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            Sheet sheet = workbook.createSheet(sheetNameOf(table));
            int columnCount = Math.max(1, table.columns().size());
            List<Integer> widths = columnWidths(table, columnCount);
            applyColumnWidths(sheet, widths);

            Cursor cursor = new Cursor();
            writeTitle(sheet, table, columnCount, styles, cursor);
            writeCriteria(sheet, table, columnCount, styles, cursor);
            cursor.row++;
            int headerRow = cursor.row;
            writeHeader(sheet, table, styles, cursor);
            int firstDataRow = cursor.row;
            writeRows(sheet, table, styles, cursor);
            int lastDataRow = cursor.row - 1;
            cursor.row++;
            writeDisclaimer(sheet, columnCount, styles, cursor, widths);

            sheet.createFreezePane(0, firstDataRow);
            if (lastDataRow >= firstDataRow) {
                sheet.setAutoFilter(new CellRangeAddress(
                        headerRow, lastDataRow, 0, columnCount - 1));
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("导出文件写出失败", exception);
        }
    }

    // ---------- 说明区 ----------

    private static void writeTitle(
            Sheet sheet, ExportTable table, int columnCount, Styles styles, Cursor cursor) {
        Row row = sheet.createRow(cursor.row++);
        row.setHeightInPoints(TITLE_FONT_POINTS * 1.6f);
        cell(row, 0).setCellValue(table.title());
        style(row, 0, styles.title);
        merge(sheet, cursor.row - 1, columnCount);
    }

    private static void writeCriteria(
            Sheet sheet, ExportTable table, int columnCount, Styles styles, Cursor cursor) {
        writeMergedRow(sheet, columnCount, styles.label,
                "数据截止时间：" + time(table.dataTime()), cursor);
        writeMergedRow(sheet, columnCount, styles.label,
                "文件生成时间：" + time(table.generatedAt()), cursor);
        writeMergedRow(sheet, columnCount, styles.label,
                "快照版本：" + orDash(table.snapshotVersion())
                        + "　数据状态：" + orDash(table.dataStatus()), cursor);
        writeMergedRow(sheet, columnCount, styles.sectionLabel, "筛选口径", cursor);
        for (String line : table.criteria()) {
            writeMergedRow(sheet, columnCount, styles.note, line, cursor);
        }
    }

    private static void writeDisclaimer(
            Sheet sheet, int columnCount, Styles styles, Cursor cursor, List<Integer> widths) {
        Row row = sheet.createRow(cursor.row);
        cell(row, 0).setCellValue(ExportTable.DISCLAIMER);
        style(row, 0, styles.wrappedNote);
        merge(sheet, cursor.row, columnCount);
        // 合并单元格里的自动行高在 Excel 里不可靠，因此按"合并后的可用宽度"算出需要的行数后显式给定。
        int available = widths.stream().mapToInt(Integer::intValue).sum();
        int lines = Math.max(1, (int) Math.ceil(
                (double) displayWidth(ExportTable.DISCLAIMER) / Math.max(1, available)));
        row.setHeightInPoints(Math.min(lines, 6) * LINE_POINTS);
    }

    // ---------- 表格 ----------

    private static void writeHeader(
            Sheet sheet, ExportTable table, Styles styles, Cursor cursor) {
        Row row = sheet.createRow(cursor.row++);
        List<ExportColumn> columns = table.columns();
        for (int index = 0; index < columns.size(); index++) {
            cell(row, index).setCellValue(columns.get(index).header());
            style(row, index, styles.header);
        }
    }

    private static void writeRows(Sheet sheet, ExportTable table, Styles styles, Cursor cursor) {
        List<ExportColumn> columns = table.columns();
        for (List<ExportCell> values : table.rows()) {
            Row row = sheet.createRow(cursor.row++);
            for (int index = 0; index < values.size(); index++) {
                ExportColumnFormat format = index < columns.size()
                        ? columns.get(index).format()
                        : ExportColumnFormat.TEXT;
                writeCell(cell(row, index), values.get(index), format, styles);
            }
        }
    }

    /**
     * 按单元格**自己的类型**决定写字符串还是数字，按**列**的格式决定显示与对齐。
     *
     * <p>两者分开处理，是为了让"数值列里出现了一个解析不了的值"（{@link ExportCell#decimal}
     * 会把它退化成文本）仍然写得出文件：那一格按文本落笔，其余照旧。
     * 若改用列的格式强行转换，遇到这种值就会抛异常，整份导出失败在一个格式意外上。
     */
    private static void writeCell(
            Cell cell, ExportCell value, ExportColumnFormat format, Styles styles) {
        if (value instanceof ExportCell.Decimal decimal) {
            cell.setCellValue(decimalValue(decimal));
            style(cell, styles.numeric(format));
            return;
        }
        String text = value instanceof ExportCell.Text typed && typed.value() != null
                ? typed.value()
                : String.valueOf(value.excelValue());
        cell.setCellValue(text);
        style(cell, styles.text);
    }

    private static double decimalValue(ExportCell.Decimal cell) {
        BigDecimal value = cell.value();
        return value == null ? 0d : value.doubleValue();
    }

    // ---------- 列宽 ----------

    /**
     * 按"表头 + 前若干行的显示宽度"定列宽。
     *
     * <p>刻意不用 {@code autoSizeColumn}：它需要 AWT 字体度量，在无图形环境（容器）里
     * 要么报错要么给出错乱的宽度，而这个文件是要在容器里生成的。
     */
    private static List<Integer> columnWidths(ExportTable table, int columnCount) {
        List<Integer> widths = new ArrayList<>(columnCount);
        for (int index = 0; index < columnCount; index++) {
            int chars = MIN_WIDTH_CHARS;
            if (index < table.columns().size()) {
                chars = Math.max(chars, displayWidth(table.columns().get(index).header()) + 2);
            }
            int sample = Math.min(table.rowCount(), WIDTH_SAMPLE_ROWS);
            for (int rowIndex = 0; rowIndex < sample; rowIndex++) {
                List<ExportCell> row = table.rows().get(rowIndex);
                if (index < row.size()) {
                    chars = Math.max(chars, displayWidth(textOf(row.get(index))) + 2);
                }
            }
            widths.add(Math.min(chars, MAX_WIDTH_CHARS));
        }
        return widths;
    }

    private static void applyColumnWidths(Sheet sheet, List<Integer> widths) {
        for (int index = 0; index < widths.size(); index++) {
            sheet.setColumnWidth(index, widths.get(index) * WIDTH_UNIT);
        }
    }

    private static String textOf(ExportCell cell) {
        if (cell instanceof ExportCell.Text text) {
            return text.value();
        }
        if (cell instanceof ExportCell.Decimal decimal && decimal.value() != null) {
            return decimal.value().toPlainString();
        }
        return "";
    }

    /** 中日韩字符按两个字符宽估算，其余按一个。列宽是给人看的，不需要像素级精确。 */
    private static int displayWidth(String text) {
        if (text == null) {
            return 0;
        }
        int width = 0;
        for (int index = 0; index < text.length(); index++) {
            width += isWide(text.charAt(index)) ? 2 : 1;
        }
        return width;
    }

    private static boolean isWide(char character) {
        return character >= 0x1100
                && (character <= 0x115F
                        || character == 0x2329
                        || character == 0x232A
                        || (character >= 0x2E80 && character <= 0xA4CF)
                        || (character >= 0xAC00 && character <= 0xD7A3)
                        || (character >= 0xF900 && character <= 0xFAFF)
                        || (character >= 0xFE30 && character <= 0xFE6F)
                        || (character >= 0xFF00 && character <= 0xFF60)
                        || (character >= 0xFFE0 && character <= 0xFFE6));
    }

    // ---------- 小工具 ----------

    private static void writeMergedRow(
            Sheet sheet, int columnCount, CellStyle style, String value, Cursor cursor) {
        Row row = sheet.createRow(cursor.row);
        cell(row, 0).setCellValue(value);
        style(row, 0, style);
        merge(sheet, cursor.row, columnCount);
        cursor.row++;
    }

    private static void merge(Sheet sheet, int rowIndex, int columnCount) {
        if (columnCount > 1) {
            sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, columnCount - 1));
        }
    }

    private static Cell cell(Row row, int index) {
        return row.createCell(index);
    }

    private static void style(Row row, int index, CellStyle style) {
        row.getCell(index).setCellStyle(style);
    }

    private static void style(Cell cell, CellStyle style) {
        cell.setCellStyle(style);
    }

    private static String time(OffsetDateTime value) {
        return value == null ? "未知" : TIME_FORMAT.format(value);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "--" : value;
    }

    /** Excel 工作表名上限 31 字符，且不允许 {@code [ ] : * ? / \} 这些字符。 */
    private static String sheetNameOf(ExportTable table) {
        String name = table.title() == null ? "导出" : table.title();
        String sanitized = name.replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
        if (sanitized.isEmpty()) {
            return "导出";
        }
        return sanitized.length() <= 31 ? sanitized : sanitized.substring(0, 31);
    }

    /** 行游标。写成一个小对象，避免每个写出步骤都要回传"下一个行号"。 */
    private static final class Cursor {
        private int row;
    }

    /**
     * 全部样式在这里一次性建好。
     *
     * <p>POI 的样式必须由工作簿创建、且**同一个工作簿里的样式数量有上限**（约 64000）。
     * 因此每格现建一个 {@code CellStyle} 是这类实现最常见的写法，也是 5000 行时
     * 会直接把工作簿写坏的那个写法（{@code Too many cell formats}）。
     */
    private static final class Styles {

        private final CellStyle title;
        private final CellStyle label;
        private final CellStyle sectionLabel;
        private final CellStyle note;
        private final CellStyle wrappedNote;
        private final CellStyle header;
        private final CellStyle text;
        private final CellStyle price;
        private final CellStyle amount;
        private final CellStyle percent;

        Styles(XSSFWorkbook workbook) {
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints(TITLE_FONT_POINTS);

            Font bold = workbook.createFont();
            bold.setBold(true);

            Font normal = workbook.createFont();

            title = plain(workbook, titleFont, HorizontalAlignment.LEFT);
            label = plain(workbook, normal, HorizontalAlignment.LEFT);
            sectionLabel = plain(workbook, bold, HorizontalAlignment.LEFT);
            note = plain(workbook, normal, HorizontalAlignment.LEFT);
            wrappedNote = plain(workbook, normal, HorizontalAlignment.LEFT);
            wrappedNote.setWrapText(true);
            wrappedNote.setVerticalAlignment(VerticalAlignment.TOP);

            header = plain(workbook, bold, HorizontalAlignment.CENTER);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);

            text = plain(workbook, normal, HorizontalAlignment.LEFT);
            price = numeric(workbook, normal, PRICE_FORMAT);
            amount = numeric(workbook, normal, AMOUNT_FORMAT);
            percent = numeric(workbook, normal, PERCENT_FORMAT);
        }

        CellStyle numeric(ExportColumnFormat format) {
            return switch (format) {
                case PRICE -> price;
                case AMOUNT -> amount;
                case PERCENT -> percent;
                case TEXT -> text;
            };
        }

        private static CellStyle plain(XSSFWorkbook workbook, Font font, HorizontalAlignment align) {
            CellStyle style = workbook.createCellStyle();
            style.setFont(font);
            style.setAlignment(align);
            return style;
        }

        private static CellStyle numeric(XSSFWorkbook workbook, Font font, String numberFormat) {
            CellStyle style = plain(workbook, font, HorizontalAlignment.RIGHT);
            style.setDataFormat(workbook.createDataFormat().getFormat(numberFormat));
            return style;
        }
    }
}
