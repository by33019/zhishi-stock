package cn.zhishi.stock.export.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 榜单导出列白名单（契约 §9.2 EXP-01："只允许导出白名单字段"）。
 *
 * <p>这里钉死的是"列的选择与顺序"，与取数、写文件都无关。
 * 它值得单独一组用例，是因为 {@code columns} 是**用户直接给的输入**：
 * 拼错一个 key、把同一列点两次、顺序与页面不一致，都会直接落到用户的文件里，
 * 而这三件事在用例层与写出层都看不出来。
 */
class StockRankingColumnTest {

  @Test
  void resolveKeepsCallerSuppliedOrder() {
    assertThat(resolveKeys(List.of("changeRate", "securityCode", "latestPrice")))
        .containsExactly("changeRate", "securityCode", "latestPrice");
  }

  @Test
  void resolveTrimsSurroundingWhitespace() {
    assertThat(resolveKeys(List.of("  securityCode  "))).containsExactly("securityCode");
  }

  /**
   * 重复点名只保留第一次出现的位置。并排两列表头相同的数字，从文件本身看不出
   * "这本该是一列"，所以宁可在解析时就合成一列。
   */
  @Test
  void resolveKeepsOnlyTheFirstOccurrenceOfRepeatedKeys() {
    assertThat(resolveKeys(List.of("securityCode", "changeRate", "securityCode")))
        .containsExactly("securityCode", "changeRate");
  }

  @Test
  void resolveFallsBackToDefaultColumnsWhenNothingIsSupplied() {
    List<String> defaults = defaults();

    assertThat(resolveKeys(List.of())).containsExactlyElementsOf(defaults);
    assertThat(resolveKeys(null)).containsExactlyElementsOf(defaults);
  }

  /**
   * 白名单之外的 key 在这里被丢掉，判据属于 {@link StockRankingColumn#firstUnknown}：
   * 分成两道是为了让 400 的文案能说出"是哪个 key 不合法"，而这里只负责把合法的挑出来。
   */
  @Test
  void resolveDropsKeysOutsideWhitelistAndLeavesRejectionToValidation() {
    assertThat(resolveKeys(List.of("securityCode", "internalCost")))
        .containsExactly("securityCode");
  }

  @Test
  void resolveDropsNullElements() {
    assertThat(resolveKeys(Arrays.asList("securityCode", null, "changeRate")))
        .containsExactly("securityCode", "changeRate");
  }

  @Test
  void firstUnknownNamesTheFirstOffender() {
    assertThat(StockRankingColumn.firstUnknown(List.of("securityCode", "internalCost", "secret")))
        .contains("internalCost");
  }

  /**
   * JSON 数组里出现 {@code null} 是可能的。若把它归一成空串，用户收到的会是一句
   * "含白名单之外的字段："——冒号后面什么都没有，没法据此改对请求。
   */
  @Test
  void firstUnknownReportsNullElementsAsSuchInsteadOfBlank() {
    assertThat(StockRankingColumn.firstUnknown(Arrays.asList("securityCode", null)))
        .contains("null");
  }

  @Test
  void firstUnknownIsEmptyWhenEveryKeyIsWhitelisted() {
    assertThat(StockRankingColumn.firstUnknown(defaults())).isEmpty();
    assertThat(StockRankingColumn.firstUnknown(null)).isEmpty();
    assertThat(StockRankingColumn.firstUnknown(List.of())).isEmpty();
  }

  /**
   * 默认列集刻意不含昨收价：三种榜单共用一份默认列，三列（最新价 / 涨跌额 / 涨跌幅）
   * 已足以还原方向与基准。需要它的人在 {@code columns} 里点名。
   */
  @Test
  void defaultColumnsDeliberatelyExcludePreviousClosePrice() {
    assertThat(defaults()).doesNotContain("previousClosePrice");
    assertThat(StockRankingColumn.keys()).contains("previousClosePrice");
  }

  /**
   * key 重复会让 {@code byKey} 悄悄丢掉一个枚举常量：现象是"这一列无论怎么点都不出现"，
   * 而且不报任何错。表头与 key 都不能为空，否则文件里会出现无名列。
   */
  @Test
  void everyColumnHasAUniqueKeyAndANonBlankHeader() {
    assertThat(StockRankingColumn.keys())
        .doesNotHaveDuplicates()
        .allSatisfy(key -> assertThat(key).isNotBlank());
    assertThat(Arrays.stream(StockRankingColumn.values()).map(StockRankingColumn::header).toList())
        .doesNotHaveDuplicates()
        .allSatisfy(header -> assertThat(header).isNotBlank());
  }

  /** 请求里能点名的 key 必须与"能解析出来的列"完全对上，否则会出现永远解析不出东西的合法 key。 */
  @Test
  void everyAdvertisedKeyResolvesToItsOwnColumn() {
    for (String key : StockRankingColumn.keys()) {
      assertThat(resolveKeys(List.of(key))).containsExactly(key);
    }
  }

  private static List<String> defaults() {
    return StockRankingColumn.defaultColumns().stream().map(StockRankingColumn::key).toList();
  }

  private static List<String> resolveKeys(List<String> keys) {
    return StockRankingColumn.resolve(keys).stream().map(StockRankingColumn::key).toList();
  }
}
