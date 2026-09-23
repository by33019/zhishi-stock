package cn.zhishi.stock.export.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.export.domain.ExportCell;
import cn.zhishi.stock.export.domain.ExportColumn;
import cn.zhishi.stock.export.domain.ExportColumnFormat;
import cn.zhishi.stock.export.domain.ExportTable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * xlsx 写出（契约 §9.2 EXP-03：文件包含生成时间、数据截止时间、口径和免责声明；
 * §22.2 / PRD §12：文本以 {@code = + - @} 开头时做公式注入防护）。
 *
 * <h2>为什么把文件读回来断言，而不是断言 POI 的调用</h2>
 * 这一层是"内容"与"Excel 真正看到的东西"之间的最后一跳，也是防护唯一**可能失效**的地方：
 * {@code ExportCell} 加了撇号、写出器却把它当成公式写进去，前一层再怎么防都没用。
 * 只有把字节流重新解析成工作簿，"单元格是什么类型、里面到底是什么文本"才是可核对的事实。
 */
class PoiExportFileWriterTest {

  private static final OffsetDateTime DATA_TIME =
      OffsetDateTime.parse("2026-09-18T15:00:00+08:00");

  private static final OffsetDateTime GENERATED_AT =
      OffsetDateTime.parse("2026-09-22T15:55:00+08:00");

  private final PoiExportFileWriter writer = new PoiExportFileWriter();

  // ---------- 文件形状 ----------

  @Test
  void writesTheTitleOnTheFirstRow() throws IOException {
    withSheet(table(), sheet ->
        assertThat(textAt(sheet, 0, 0)).isEqualTo("行情榜单 · 涨幅榜"));
  }

  /** 契约 §9.2 EXP-03 / PRD §10：文件必须自己说明数据截止时间，且不能用生成时间代替它。 */
  @Test
  void statesTheDataCutoffAndTheGeneratedAtSeparately() throws IOException {
    withSheet(table(), sheet -> {
      List<String> lines = allText(sheet);
      assertThat(lines).anyMatch(line -> line.equals("数据截止时间：2026-09-18 15:00:00+08:00"));
      assertThat(lines).anyMatch(line -> line.equals("文件生成时间：2026-09-22 15:55:00+08:00"));
    });
  }

  @Test
  void writesSnapshotVersionDataStatusAndTheCriteriaLines() throws IOException {
    withSheet(table(), sheet -> {
      List<String> lines = allText(sheet);
      assertThat(lines).anyMatch(line -> line.contains("快照版本：sim-2026-09-18"));
      assertThat(lines).anyMatch(line -> line.contains("数据状态：实时"));
      assertThat(lines).contains("筛选口径");
      assertThat(lines).contains("榜单类型：涨幅榜");
      assertThat(lines).contains("交易所：全部");
    });
  }

  @Test
  void writesTheDisclaimerRequiredByTheContract() throws IOException {
    withSheet(table(), sheet ->
        assertThat(allText(sheet)).anyMatch(line -> line.equals(ExportTable.DISCLAIMER)));
  }

  @Test
  void writesHeadersInOrderWithOneRowPerRecord() throws IOException {
    withSheet(table(), sheet -> {
      int header = headerRow(sheet);
      assertThat(textAt(sheet, header, 0)).isEqualTo("证券代码");
      assertThat(textAt(sheet, header, 1)).isEqualTo("涨跌幅");
      assertThat(textAt(sheet, header, 2)).isEqualTo("最新价");
      assertThat(textAt(sheet, header + 1, 0)).isEqualTo("600000");
      assertThat(textAt(sheet, header + 2, 0)).isEqualTo("300001");
      // 表头之后恰好两条数据，再之后是空行与免责声明。
      assertThat(textAt(sheet, header + 3, 0)).isEmpty();
    });
  }

  /** 冻结窗格与筛选器都挂在数据区之上，否则用户滚到第 3000 行就不知道哪一列是什么。 */
  @Test
  void freezesBelowTheHeaderAndFiltersTheDataRange() throws IOException {
    withSheet(table(), sheet -> {
      int header = headerRow(sheet);
      int lastData = header + 2;

      assertThat(sheet.getPaneInformation()).isNotNull();
      // 分割线落在**第一条数据行**上，因此整块说明区与表头都留在冻结区里。
      // POI 的这个访问器返回 short，先转成 int 再比：否则 AssertJ 会拿 Short 与 Integer 比装箱类型。
      assertThat((int) sheet.getPaneInformation().getHorizontalSplitPosition())
          .isEqualTo(header + 1);
      assertThat(sheet.getCTWorksheet().getAutoFilter().getRef())
          .isEqualTo("A" + (header + 1) + ":" + columnLetter(columns().size()) + (lastData + 1));
    });
  }

  @Test
  void toleratesAnEmptyResultTable() throws IOException {
    ExportTable empty = new ExportTable(
        "行情榜单 · 涨幅榜", List.of("榜单类型：涨幅榜"), DATA_TIME, GENERATED_AT,
        "sim-2026-09-18", "实时", columns(), List.of());

    withSheet(empty, sheet -> {
      int header = headerRow(sheet);
      assertThat(textAt(sheet, header, 0)).isEqualTo("证券代码");
      // 没有数据行时筛选器不该圈住空区间——Excel 打开一个 "A5:C4" 是坏文件。
      assertThat(sheet.getCTWorksheet().isSetAutoFilter()).isFalse();
      assertThat(allText(sheet)).anyMatch(line -> line.equals(ExportTable.DISCLAIMER));
    });
  }

  // ---------- 数值与显示格式 ----------

  /**
   * 涨跌幅存的是**小数形式的比率**（{@code 0.0300} 表示 3%）。
   * 写成数值 + {@code 0.00%} 格式，Excel 里显示 {@code 3.00%}；
   * 若写成文本 {@code 0.03}，读的人会当成 0.03%——差 100 倍且打开看不出异常。
   */
  @Test
  void writesPercentColumnsAsNumbersWithAPercentFormat() throws IOException {
    withSheet(table(), sheet -> {
      int header = headerRow(sheet);
      Cell rate = cellAt(sheet, header + 1, 1);

      assertThat(rate.getCellType()).isEqualTo(CellType.NUMERIC);
      assertThat(rate.getNumericCellValue()).isEqualTo(0.03d);
      assertThat(rate.getCellStyle().getDataFormatString()).isEqualTo("0.00%");
    });
  }

  @Test
  void writesPriceAndAmountColumnsWithTheirOwnFormats() throws IOException {
    withSheet(table(), sheet -> {
      int header = headerRow(sheet);
      Cell price = cellAt(sheet, header + 1, 2);
      Cell amount = cellAt(sheet, header + 1, 3);

      assertThat(price.getNumericCellValue()).isEqualTo(10.2d);
      assertThat(price.getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
      assertThat(amount.getNumericCellValue()).isEqualTo(1_000_000d);
      assertThat(amount.getCellStyle().getDataFormatString()).isEqualTo("#,##0");
    });
  }

  /**
   * 防护只做在**文本**上：给数值也加撇号会让整列无法求和。
   * 因此负数必须仍是 {@code NUMERIC} 的 {@code -1.23}，而不是文本 {@code "'-1.23"}。
   */
  @Test
  void keepsNegativeNumbersNumericInsteadOfGuardingThem() throws IOException {
    ExportTable table = new ExportTable(
        "行情榜单 · 跌幅榜", List.of(), DATA_TIME, GENERATED_AT, "sim", "实时",
        List.of(new ExportColumn("changeAmount", "涨跌额", ExportColumnFormat.PRICE)),
        List.of(List.of(ExportCell.decimal("-1.23"))));

    withSheet(table, sheet -> {
      Cell cell = cellAt(sheet, headerRow(sheet) + 1, 0);
      assertThat(cell.getCellType()).isEqualTo(CellType.NUMERIC);
      assertThat(cell.getNumericCellValue()).isEqualTo(-1.23d);
    });
  }

  /**
   * 数值列里出现解析不了的值时**只退化那一格**，而不是让整份导出失败在一个格式意外上。
   */
  @Test
  void fallsBackToTextForAnUnparsableValueInANumericColumn() throws IOException {
    ExportTable table = new ExportTable(
        "行情榜单 · 涨幅榜", List.of(), DATA_TIME, GENERATED_AT, "sim", "实时",
        List.of(new ExportColumn("changeRate", "涨跌幅", ExportColumnFormat.PERCENT)),
        List.of(
            List.of(ExportCell.decimal("0.0300")),
            List.of(ExportCell.decimal("停牌"))));

    withSheet(table, sheet -> {
      int header = headerRow(sheet);
      assertThat(cellAt(sheet, header + 1, 0).getCellType()).isEqualTo(CellType.NUMERIC);
      assertThat(cellAt(sheet, header + 2, 0).getCellType()).isEqualTo(CellType.STRING);
      assertThat(textAt(sheet, header + 2, 0)).isEqualTo("停牌");
    });
  }

  // ---------- 公式注入防护 ----------

  /**
   * 契约 §22.2 逐字给出的四个起点都要被挡住，且**原文不能丢**：
   * 撇号只是让 Excel 不当公式解释，用户复制出来的内容必须还是他自己写的那串。
   */
  @Test
  void guardsEveryFormulaStarterInTextCells() throws IOException {
    ExportTable table = new ExportTable(
        "行情榜单 · 涨幅榜", List.of(), DATA_TIME, GENERATED_AT, "sim", "实时",
        List.of(new ExportColumn("securityName", "证券名称", ExportColumnFormat.TEXT)),
        List.of(
            List.of(ExportCell.text("=1+1")),
            List.of(ExportCell.text("+SUM(A1)")),
            List.of(ExportCell.text("-2+3")),
            List.of(ExportCell.text("@SUM(A1)")),
            List.of(ExportCell.text("正常名称"))));

    withSheet(table, sheet -> {
      int header = headerRow(sheet);
      assertThat(textAt(sheet, header + 1, 0)).isEqualTo("'=1+1");
      assertThat(textAt(sheet, header + 2, 0)).isEqualTo("'+SUM(A1)");
      assertThat(textAt(sheet, header + 3, 0)).isEqualTo("'-2+3");
      assertThat(textAt(sheet, header + 4, 0)).isEqualTo("'@SUM(A1)");
      assertThat(textAt(sheet, header + 5, 0)).isEqualTo("正常名称");
    });
  }

  /**
   * 全表**任何一格都不能是公式格**。逐个起点去核对不够：
   * 一条将来新加的列、一处新的写出路径都可能绕过防护，而扫描"类型"能一次覆盖全部。
   */
  @Test
  void neverProducesAFormulaCell() throws IOException {
    List<String> payloads = List.of(
        "=cmd|'/c calc'!A1", "=HYPERLINK(\"http://evil\")", "+1+1", "-1+1", "@SUM(1)",
        "=\tDDE", "'already-guarded");

    List<List<ExportCell>> rows = new ArrayList<>();
    for (String payload : payloads) {
      rows.add(List.of(ExportCell.text(payload)));
    }
    ExportTable table = new ExportTable(
        "行情榜单 · 涨幅榜", List.of(), DATA_TIME, GENERATED_AT, "sim", "实时",
        List.of(new ExportColumn("securityName", "证券名称", ExportColumnFormat.TEXT)),
        rows);

    withSheet(table, sheet -> assertThat(allCells(sheet))
        .allSatisfy(cell -> assertThat(cell.getCellType())
            .isNotEqualTo(CellType.FORMULA)));
  }

  // ---------- 工作表名 ----------

  /**
   * Excel 工作表名上限 31 字符，且不允许 {@code [ ] : * ? / \}。
   *
   * <p>不合法的字符被**替换成空格**而不是删掉：直接删除会把"涨幅/跌幅"粘成"涨幅跌幅"。
   * 空白本身是合法的，所以这里只断言非法字符消失——断言"没有空格"会与实现相矛盾。
   */
  @Test
  void sanitizesIllegalSheetNameCharactersAndCapsTheLength() throws IOException {
    String longTitle = "行情榜单/涨幅:跌幅*测试?[1]\\x " + "很长的标题".repeat(10);

    withSheet(exportWithTitle(longTitle), sheet -> {
      assertThat(sheet.getSheetName()).hasSizeLessThanOrEqualTo(31);
      assertThat(sheet.getSheetName())
          .doesNotContain("[", "]", ":", "*", "?", "/", "\\");
      // 标题整串仍完整地留在第一行，被改的只是工作表名。
      assertThat(textAt(sheet, 0, 0)).isEqualTo(longTitle);
    });
  }

  // ---------- 规模 ----------

  /**
   * 5,000 行是契约上限，也正是"每格现建一个 {@code CellStyle}"会写坏工作簿的规模
   * （{@code Too many cell formats}）。这条用例钉的是样式被提到工作簿级复用这件事。
   */
  @Test
  void writesTheFullRowLimitWithoutExhaustingCellFormats() throws IOException {
    List<List<ExportCell>> rows = new ArrayList<>();
    for (int index = 0; index < 5_000; index++) {
      rows.add(List.of(
          ExportCell.text(String.format("%06d", index)),
          ExportCell.decimal("0.0300"),
          ExportCell.decimal("10.20"),
          ExportCell.decimal("1000000")));
    }
    ExportTable table = new ExportTable(
        "行情榜单 · 涨幅榜", List.of("榜单类型：涨幅榜"), DATA_TIME, GENERATED_AT,
        "sim-2026-09-18", "实时", columns(), rows);

    byte[] bytes = writer.write(table);

    assertThat(bytes).isNotEmpty();
    withSheetBytes(bytes, sheet -> {
      int header = headerRow(sheet);
      assertThat(textAt(sheet, header + 1, 0)).isEqualTo("000000");
      assertThat(textAt(sheet, header + 5_000, 0)).isEqualTo("004999");
    });
  }

  // ---------- 夹具与读取工具 ----------

  private static List<ExportColumn> columns() {    return List.of(
        new ExportColumn("securityCode", "证券代码", ExportColumnFormat.TEXT),
        new ExportColumn("changeRate", "涨跌幅", ExportColumnFormat.PERCENT),
        new ExportColumn("latestPrice", "最新价", ExportColumnFormat.PRICE),
        new ExportColumn("tradeAmount", "成交额", ExportColumnFormat.AMOUNT));
  }

  private static ExportTable table() {
    return new ExportTable(
        "行情榜单 · 涨幅榜",
        List.of("榜单类型：涨幅榜", "交易所：全部"),
        DATA_TIME,
        GENERATED_AT,
        "sim-2026-09-18",
        "实时",
        columns(),
        List.of(
            List.of(
                ExportCell.text("600000"),
                ExportCell.decimal("0.0300"),
                ExportCell.decimal("10.20"),
                ExportCell.decimal("1000000")),
            List.of(
                ExportCell.text("300001"),
                ExportCell.decimal("0.2000"),
                ExportCell.decimal("20.40"),
                ExportCell.decimal("2000000"))));
  }

  private static ExportTable exportWithTitle(String title) {
    return new ExportTable(
        title, List.of(), DATA_TIME, GENERATED_AT, "sim", "实时", columns(),
        List.of(List.of(
            ExportCell.text("600000"), ExportCell.decimal("0.0300"),
            ExportCell.decimal("10.20"), ExportCell.decimal("1000000"))));
  }

  private void withSheet(ExportTable table, SheetCheck check) throws IOException {
    withSheetBytes(writer.write(table), check);
  }

  private static void withSheetBytes(byte[] bytes, SheetCheck check) throws IOException {
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
      check.run(workbook.getSheetAt(0));
    }
  }

  /**
   * 表头行的位置由**版式**定位：说明区之后第一个空行，其下一行就是表头。
   *
   * <p>刻意不写死列名，也不写死行号——写出器为"说明区与网格之间留一个空行"这件事
   * 是稳定的版式约定，而列名与说明区行数都会变（少一条口径、换一次第一列），
   * 写死任何一个都会让这一组用例在无关改动下集体失准。
   */
  private static int headerRow(XSSFSheet sheet) {
    for (int index = 1; index <= sheet.getLastRowNum(); index++) {
      if (sheet.getRow(index) == null) {
        return index + 1;
      }
    }
    throw new AssertionError("没有找到表头行：说明区与网格之间没有空行");
  }

  /** Excel 列号转字母。只覆盖本组用例的列数（远小于 26），因此不引入更通用的实现。 */
  private static String columnLetter(int columnCount) {
    return String.valueOf((char) ('A' + columnCount - 1));
  }

  private static Cell cellAt(XSSFSheet sheet, int row, int column) {
    Row found = sheet.getRow(row);
    assertThat(found).as("第 %s 行不存在", row).isNotNull();
    Cell cell = found.getCell(column);
    assertThat(cell).as("第 %s 行第 %s 列不存在", row, column).isNotNull();
    return cell;
  }

  private static String textAt(XSSFSheet sheet, int row, int column) {
    Row found = sheet.getRow(row);
    if (found == null) {
      return "";
    }
    Cell cell = found.getCell(column);
    return cell == null ? "" : cell.toString();
  }

  private static List<String> allText(XSSFSheet sheet) {
    List<String> lines = new ArrayList<>();
    for (Cell cell : allCells(sheet)) {
      String text = cell.toString();
      if (!text.isEmpty()) {
        lines.add(text);
      }
    }
    return lines;
  }

  private static List<Cell> allCells(XSSFSheet sheet) {
    List<Cell> cells = new ArrayList<>();
    for (int index = 0; index <= sheet.getLastRowNum(); index++) {
      Row row = sheet.getRow(index);
      if (row == null) {
        continue;
      }
      for (int column = 0; column < row.getLastCellNum(); column++) {
        Cell cell = row.getCell(column);
        if (cell != null) {
          cells.add(cell);
        }
      }
    }
    return cells;
  }

  /** 让每个用例的断言写在 try-with-resources 里面，而不是把工作簿泄漏出去。 */
  @FunctionalInterface
  private interface SheetCheck {
    void run(XSSFSheet sheet) throws IOException;
  }
}
