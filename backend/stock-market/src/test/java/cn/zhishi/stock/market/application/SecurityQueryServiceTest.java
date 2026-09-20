package cn.zhishi.stock.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecuritySearchMatch;
import cn.zhishi.stock.market.domain.SecuritySearchMatch.MatchedField;
import cn.zhishi.stock.market.domain.SecuritySearchResult;
import cn.zhishi.stock.market.domain.SecuritySummary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecurityQueryServiceTest {

  /**
   * 桩数据集刻意包含一只**带拼音的真实证券**（贵州茅台）：
   * 生产模拟数据里拼音一律为空，但匹配能力必须可用且被测试覆盖。
   */
  private static final List<SecuritySummary> UNIVERSE = List.of(
      summary("sim-600000", "SH", "600000", "模拟证券600000", "MAIN", "STOCK", false, false, null, null),
      summary("sim-600001", "SH", "600001", "模拟证券600001", "MAIN", "STOCK", false, false, null, null),
      summary("sim-000001", "SZ", "000001", "模拟证券000001", "MAIN", "STOCK", true, false, null, null),
      summary("sim-300001", "SZ", "300001", "模拟证券300001", "GEM", "STOCK", false, true, null, null),
      summary("sim-600519", "SH", "600519", "贵州茅台", "MAIN", "STOCK", false, false, "guizhoumaotai", "gzmt"));

  // ---------- STK-01 搜索 ----------

  @Test
  void matchesSecurityCodeByPrefix() {
    // 600519 的前四位是 "6005" 而非 "6000"，所以前缀匹配是逐字符的，不能用"看起来像"推断。
    var result = search("600");

    assertThat(ids(result)).containsExactly("sim-600000", "sim-600001", "sim-600519");
    assertThat(result.items()).allSatisfy(item ->
        assertThat(item.matchedField()).isEqualTo(MatchedField.CODE));
  }

  @Test
  void matchesFullSymbolByPrefixIgnoringCase() {
    var result = search("sh.6005");

    assertThat(ids(result)).containsExactly("sim-600519");
    assertThat(result.items().get(0).matchedField()).isEqualTo(MatchedField.CODE);
  }

  @Test
  void matchesSecurityNameByContains() {
    var result = search("茅台");

    assertThat(ids(result)).containsExactly("sim-600519");
    assertThat(result.items().get(0).matchedField()).isEqualTo(MatchedField.NAME);
    assertThat(result.items().get(0).highlight()).isEqualTo("茅台");
  }

  @Test
  void matchesPinyinAndPinyinAbbreviationByPrefix() {
    var byPinyin = search("guizhou");
    var byAbbr = search("gzmt");

    assertThat(ids(byPinyin)).containsExactly("sim-600519");
    assertThat(byPinyin.items().get(0).matchedField()).isEqualTo(MatchedField.PINYIN);
    assertThat(ids(byAbbr)).containsExactly("sim-600519");
    assertThat(byAbbr.items().get(0).matchedField()).isEqualTo(MatchedField.PINYIN_ABBR);
  }

  @Test
  void prefersCodeOverNameForTheSameSecurity() {
    // "模拟证券600000" 的名称里也含 "600000"，但代码前缀优先级更高。
    var result = search("600000");

    assertThat(ids(result)).containsExactly("sim-600000");
    assertThat(result.items().get(0).matchedField()).isEqualTo(MatchedField.CODE);
  }

  @Test
  void ordersByMatchedFieldThenFullSymbol() {
    // "00"：sim-000001 命中代码前缀；三只模拟证券命中名称包含；贵州茅台不命中。
    var result = search("00");

    assertThat(ids(result))
        .containsExactly("sim-000001", "sim-600000", "sim-600001", "sim-300001");
    assertThat(result.items())
        .extracting(SecuritySearchMatch::matchedField)
        .containsExactly(MatchedField.CODE, MatchedField.NAME, MatchedField.NAME, MatchedField.NAME);
  }

  @Test
  void isDeterministicAcrossRuns() {
    assertThat(ids(search("0"))).isEqualTo(ids(search("0")));
  }

  @Test
  void filtersBySecurityType() {
    assertThat(search("0", "ETF", null, null).items()).isEmpty();
    assertThat(ids(search("0", "stock", null, null))).isNotEmpty();
  }

  @Test
  void filtersByExchangeCode() {
    var result = search("0", null, "sz", null);

    assertThat(ids(result)).containsExactly("sim-000001", "sim-300001");
  }

  @Test
  void ignoresBlankEntriesInCsvFilters() {
    var result = search("0", " , STOCK ,", " , SZ ,", null);

    assertThat(ids(result)).containsExactly("sim-000001", "sim-300001");
  }

  @Test
  void capsResultsAtLimit() {
    assertThat(search("0", null, null, 2).items()).hasSize(2);
  }

  @Test
  void defaultsLimitToTen() {
    assertThat(search("0", null, null, null).items()).hasSizeGreaterThan(1);
  }

  @Test
  void trimsQueryBeforeMatching() {
    assertThat(ids(search("  600000  "))).containsExactly("sim-600000");
  }

  @Test
  void rejectsMissingBlankOrTooLongQuery() {
    assertThatThrownBy(() -> search(null))
        .isInstanceOf(InvalidSecurityQueryException.class)
        .hasMessageContaining("q");
    assertThatThrownBy(() -> search("   "))
        .isInstanceOf(InvalidSecurityQueryException.class)
        .hasMessageContaining("q");
    assertThatThrownBy(() -> search("x".repeat(51)))
        .isInstanceOf(InvalidSecurityQueryException.class)
        .hasMessageContaining("q");
  }

  @Test
  void acceptsQueryOfExactlyFiftyCharacters() {
    assertThat(search("x".repeat(50)).items()).isEmpty();
  }

  @Test
  void rejectsLimitOutsideOneToTwenty() {
    assertThatThrownBy(() -> search("0", null, null, 0))
        .isInstanceOf(InvalidSecurityQueryException.class)
        .hasMessageContaining("limit");
    assertThatThrownBy(() -> search("0", null, null, 21))
        .isInstanceOf(InvalidSecurityQueryException.class)
        .hasMessageContaining("limit");
  }

  @Test
  void returnsEmptyResultWhenProviderHasNoUniverse() {
    var service = new SecurityQueryService(marketCode -> List.of(), StubSectorProvider.empty());

    assertThat(service.search("600000", null, null, null).items()).isEmpty();
  }

  // ---------- STK-02 列表 ----------

  @Test
  void listsEverythingByDefaultSortedByFullSymbol() {
    var page = service().list(SecurityListCriteria.empty());

    assertThat(page.items()).extracting(SecuritySummary::fullSymbol)
        .containsExactly("SH.600000", "SH.600001", "SH.600519", "SZ.000001", "SZ.300001");
    assertThat(page.page()).isEqualTo(1);
    assertThat(page.size()).isEqualTo(20);
    assertThat(page.total()).isEqualTo(5);
    assertThat(page.totalPages()).isEqualTo(1);
    assertThat(page.hasNext()).isFalse();
  }

  @Test
  void filtersListByKeywordAcrossCodeNameAndFullSymbol() {
    assertThat(ids(service().list(criteria("茅台", null, null, null, null, null, null, null, null))))
        .containsExactly("sim-600519");
    assertThat(ids(service().list(criteria("sh.6005", null, null, null, null, null, null, null, null))))
        .containsExactly("sim-600519");
    assertThat(ids(service().list(criteria("3000", null, null, null, null, null, null, null, null))))
        .containsExactly("sim-300001");
  }

  @Test
  void filtersListByExactFieldsIgnoringCase() {
    assertThat(ids(service().list(criteria(null, null, null, "gem", null, null, null, null, null))))
        .containsExactly("sim-300001");
    assertThat(ids(service().list(criteria(null, null, "sz", null, null, null, null, null, null))))
        .containsExactly("sim-000001", "sim-300001");
    assertThat(ids(service().list(criteria(null, null, null, "main", null, null, null, null, null))))
        .containsExactly("sim-600000", "sim-600001", "sim-600519", "sim-000001");
  }

  @Test
  void filtersListByListingStatus() {
    assertThat(ids(service().list(criteria(null, null, null, null, "suspended", null, null, null, null))))
        .containsExactly("sim-300001");
  }

  /**
   * 板块筛选按成分关系取数：{@link StubSectorProvider#INDUSTRY_ID} 只含三只沪市证券。
   *
   * <p>与"关键字包含"这类字段筛选不同，板块条件来自**另一份数据**（关系表），
   * 因此必须验证它是真的按关系过滤，而不是碰巧和某个字段筛选结果一致。
   */
  @Test
  void filtersListBySectorMembership() {
    var page = service().list(
        criteria(null, null, null, null, null, StubSectorProvider.INDUSTRY_ID, null, null, null));

    assertThat(ids(page)).containsExactly("sim-600000", "sim-600001", "sim-600519");
    assertThat(page.total()).isEqualTo(3);
  }

  /** 板块条件与其它条件是**交集**：板块里的 sim-600519 不含 "6000"，被关键字排除。 */
  @Test
  void intersectsSectorWithOtherFilters() {
    var page = service().list(
        criteria("6000", null, null, null, null, StubSectorProvider.INDUSTRY_ID, null, null, null));

    assertThat(ids(page)).containsExactly("sim-600000", "sim-600001");
  }

  /**
   * 板块 ID 不存在时返回空页而不是报错，与 {@code securityType=ETF} 同口径：
   * 合法取值只是没有数据，报 400 就把"没有数据"错报成"参数非法"。
   */
  @Test
  void returnsEmptyPageForUnknownSectorIdInsteadOfFailing() {
    var page = service().list(
        criteria(null, null, null, null, null, "stub-bk-missing", null, null, null));

    assertThat(page.items()).isEmpty();
    assertThat(page.total()).isZero();
    assertThat(page.totalPages()).isZero();
  }

  /** 空串按"未传"处理，与其它查询参数的解析口径一致。 */
  @Test
  void treatsBlankSectorIdAsAbsent() {
    assertThat(service().list(
        criteria(null, null, null, null, null, "   ", null, null, null)).total()).isEqualTo(5);
  }

  @Test
  void supportsDescendingSortAndWhitelistedFields() {
    assertThat(ids(service().list(criteria(null, null, null, null, null, null, null, null, "securityCode,desc"))))
        .containsExactly("sim-600519", "sim-600001", "sim-600000", "sim-300001", "sim-000001");
    assertThat(ids(service().list(criteria(null, null, null, null, null, null, null, null, "securityId"))))
        .containsExactly("sim-000001", "sim-300001", "sim-600000", "sim-600001", "sim-600519");
  }

  @Test
  void rejectsSortFieldOutsideWhitelistInsteadOfSilentlyFallingBack() {
    assertThatThrownBy(() ->
        service().list(criteria(null, null, null, null, null, null, null, null, "updatedAt,asc")))
        .isInstanceOf(InvalidSecurityQueryException.class)
        .hasMessageContaining("sort");
  }

  @Test
  void rejectsUnknownSortDirection() {
    assertThatThrownBy(() ->
        service().list(criteria(null, null, null, null, null, null, null, null, "fullSymbol,sideways")))
        .isInstanceOf(InvalidSecurityQueryException.class)
        .hasMessageContaining("sort");
  }

  @Test
  void rejectsPageBelowOneAndSizeOutsideRange() {
    assertThatThrownBy(() -> service().list(criteria(null, null, null, null, null, null, 0, null, null)))
        .isInstanceOf(InvalidSecurityQueryException.class)
        .hasMessageContaining("page");
    assertThatThrownBy(() -> service().list(criteria(null, null, null, null, null, null, null, 0, null)))
        .isInstanceOf(InvalidSecurityQueryException.class)
        .hasMessageContaining("size");
    assertThatThrownBy(() -> service().list(criteria(null, null, null, null, null, null, null, 101, null)))
        .isInstanceOf(InvalidSecurityQueryException.class)
        .hasMessageContaining("size");
  }

  @Test
  void computesPageSliceAndNavigationFlags() {
    var second = service().list(criteria(null, null, null, null, null, null, 2, 2, null));
    var third = service().list(criteria(null, null, null, null, null, null, 3, 2, null));

    assertThat(second.items()).extracting(SecuritySummary::fullSymbol)
        .containsExactly("SH.600519", "SZ.000001");
    assertThat(second.total()).isEqualTo(5);
    assertThat(second.totalPages()).isEqualTo(3);
    assertThat(second.hasNext()).isTrue();
    assertThat(third.items()).hasSize(1);
    assertThat(third.hasNext()).isFalse();
  }

  @Test
  void returnsEmptyPageBeyondLastPage() {
    var page = service().list(criteria(null, null, null, null, null, null, 9, 20, null));

    assertThat(page.items()).isEmpty();
    assertThat(page.total()).isEqualTo(5);
    assertThat(page.hasNext()).isFalse();
  }

  // ---------- 辅助 ----------

  private static SecurityQueryService service() {
    return new SecurityQueryService(provider(true), SECTOR_PROVIDER);
  }

  /**
   * 桩板块只覆盖三只沪市证券——刻意**不是**全集的一个"整齐子集"，
   * 否则"按板块筛选"与"按交易所筛选"会给出同样的结果，实现写错也测不出来。
   */
  private static final StubSectorProvider SECTOR_PROVIDER = StubSectorProvider.of(Map.of(
      StubSectorProvider.INDUSTRY_ID, List.of("sim-600000", "sim-600001", "sim-600519"),
      StubSectorProvider.GROUP_ID, List.of("sim-600519", "sim-000001")));

  private static SecurityMasterProvider provider(boolean supported) {
    return marketCode -> supported && "CN".equals(marketCode) ? UNIVERSE : List.of();
  }

  private static SecuritySearchResult search(String q) {
    return search(q, null, null, null);
  }

  private static SecuritySearchResult search(
      String q, String types, String exchangeCodes, Integer limit) {
    return service().search(q, types, exchangeCodes, limit);
  }

  private static List<String> ids(SecuritySearchResult result) {
    return result.items().stream().map(item -> item.security().securityId()).toList();
  }

  private static List<String> ids(PageData<SecuritySummary> page) {
    return page.items().stream().map(SecuritySummary::securityId).toList();
  }

  private static SecurityListCriteria criteria(
      String keyword, String securityType, String exchangeCode, String boardCode,
      String listingStatus, String sectorId, Integer page, Integer size, String sort) {
    return new SecurityListCriteria(
        keyword, securityType, exchangeCode, boardCode, listingStatus, sectorId, page, size, sort);
  }

  private static SecuritySummary summary(
      String securityId, String exchangeCode, String securityCode, String securityName,
      String boardCode, String securityType, boolean st, boolean suspended,
      String pinyin, String pinyinAbbr) {
    return new SecuritySummary(
        securityId,
        exchangeCode + "." + securityCode,
        securityCode,
        securityName,
        exchangeCode,
        securityType,
        boardCode,
        suspended ? "SUSPENDED" : "LISTED",
        st,
        suspended,
        2,
        pinyin,
        pinyinAbbr);
  }
}
