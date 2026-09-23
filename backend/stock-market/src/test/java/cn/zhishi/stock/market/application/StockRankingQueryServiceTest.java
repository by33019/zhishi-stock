package cn.zhishi.stock.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.QuoteSnapshotBatchProvider;
import cn.zhishi.stock.market.domain.SecuritySummary;
import cn.zhishi.stock.market.domain.StockRanking;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 榜单用例（QTE-01）。
 *
 * <p>桩批次刻意包含四种"排序键不完整"的行，用来钉死各口径的排除规则：
 * 停牌行、无涨跌幅行、无成交额行、以及两只涨跌幅相同但代码不同的行。
 */
class StockRankingQueryServiceTest {

  private static final OffsetDateTime DATA_TIME =
      OffsetDateTime.parse("2026-09-18T15:00:00+08:00");

  private static final String SEQUENCE = "sim-2026-09-18";

  private static final List<QuoteSnapshot> BATCH = List.of(
      // 0.0300，与 sim-600005 同值，用于验证兜底键
      snapshot("sim-600000", "SH", "MAIN", false, false, "0.0300", "500000000"),
      // 涨停（主板 10%）
      snapshot("sim-600001", "SH", "MAIN", false, false, "0.1000", "300000000"),
      // ST，成交额最高
      snapshot("sim-000001", "SZ", "MAIN", true, false, "-0.0500", "900000000"),
      // 涨停（创业板 20%）
      snapshot("sim-300001", "SZ", "GEM", false, false, "0.2000", "100000000"),
      // 涨停（北交所 30%）
      snapshot("sim-430001", "BJ", "BSE", false, false, "0.3000", "200000000"),
      // 停牌
      snapshot("sim-600002", "SH", "MAIN", false, true, "0.0000", "0"),
      // 有成交额但没有涨跌幅：涨幅榜排除，成交额榜保留
      snapshot("sim-600003", "SH", "MAIN", false, false, null, "700000000"),
      // 有涨跌幅但没有成交额：涨幅榜保留，成交额榜排除
      snapshot("sim-600004", "SH", "MAIN", false, false, "0.0100", null),
      // 与 sim-600000 同涨跌幅，代码更大
      snapshot("sim-600005", "SH", "MAIN", false, false, "0.0300", "400000000"));

  // ---------- 排序口径 ----------

  @Test
  void sortsGainersByChangeRateDescending() {
    StockRanking ranking = rank(criteria("GAINERS"));

    assertThat(ids(ranking)).containsExactly(
        "sim-430001", "sim-300001", "sim-600001",
        "sim-600000", "sim-600005", "sim-600004", "sim-000001");
    assertThat(ranking.total()).isEqualTo(7);
  }

  @Test
  void sortsLosersByChangeRateAscending() {
    StockRanking ranking = rank(criteria("LOSERS"));

    assertThat(ids(ranking)).containsExactly(
        "sim-000001", "sim-600004", "sim-600000",
        "sim-600005", "sim-600001", "sim-300001", "sim-430001");
  }

  @Test
  void sortsTurnoverByTradeAmountDescending() {
    StockRanking ranking = rank(criteria("TURNOVER"));

    assertThat(ids(ranking)).containsExactly(
        "sim-000001", "sim-600003", "sim-600000",
        "sim-600005", "sim-600001", "sim-430001", "sim-300001");
  }

  /**
   * 兜底键必须**不随主键方向翻转**：涨跌方向相反的两种榜单里，
   * 同涨跌幅的两只证券先后关系必须相同，否则"排序稳定"就是空话。
   */
  @Test
  void breaksTiesByFullSymbolAscendingRegardlessOfDirection() {
    List<String> gainers = ids(rank(criteria("GAINERS")));
    List<String> losers = ids(rank(criteria("LOSERS")));

    assertThat(indexOf(gainers, "sim-600000")).isLessThan(indexOf(gainers, "sim-600005"));
    assertThat(indexOf(losers, "sim-600000")).isLessThan(indexOf(losers, "sim-600005"));
  }

  @Test
  void echoesEffectiveRankingTypeInUpperCase() {
    assertThat(rank(criteria("gainers")).rankingType()).isEqualTo("GAINERS");
  }

  // ---------- 排序键与排除规则 ----------

  /** 排序键按口径取：涨幅榜要涨跌幅，成交额榜要成交额。缺哪个就排除在哪个榜之外。 */
  @Test
  void excludesRowsMissingTheSortKeyOfThatRankingType() {
    assertThat(ids(rank(criteria("GAINERS")))).doesNotContain("sim-600003");
    assertThat(ids(rank(criteria("TURNOVER")))).contains("sim-600003");

    assertThat(ids(rank(criteria("TURNOVER")))).doesNotContain("sim-600004");
    assertThat(ids(rank(criteria("GAINERS")))).contains("sim-600004");
  }

  @Test
  void excludesSuspendedSecuritiesByDefault() {
    assertThat(ids(rank(criteria("GAINERS")))).doesNotContain("sim-600002");
  }

  @Test
  void includesSuspendedSecuritiesWhenExplicitlyRequested() {
    RankingCriteria criteria = new RankingCriteria(
        "GAINERS", null, null, null, null, false, null, null);

    assertThat(ids(rank(criteria))).contains("sim-600002");
    assertThat(rank(criteria).total()).isEqualTo(8);
  }

  /** ST 与停牌的默认值**不同**：PRD 只说停牌默认排除，ST 是投资者的主动选择。 */
  @Test
  void keepsStSecuritiesByDefaultAndDropsThemWhenRequested() {
    assertThat(ids(rank(criteria("TURNOVER")))).contains("sim-000001");

    RankingCriteria excludeSt = new RankingCriteria(
        "TURNOVER", null, null, null, true, null, null, null);

    assertThat(ids(rank(excludeSt))).doesNotContain("sim-000001");
    assertThat(rank(excludeSt).total()).isEqualTo(6);
  }

  // ---------- 筛选 ----------

  @Test
  void filtersByExchangeAndBoardTakingIntersection() {
    RankingCriteria criteria = new RankingCriteria(
        "GAINERS", "SH,SZ", "MAIN", null, null, null, null, null);

    assertThat(ids(rank(criteria))).containsExactly(
        "sim-600001", "sim-600000", "sim-600005", "sim-600004", "sim-000001");
  }

  @Test
  void matchesExchangeCodesIgnoringCaseAndEmptyItems() {
    RankingCriteria criteria = new RankingCriteria(
        "GAINERS", "sh,, bj ", null, null, null, null, null, null);

    assertThat(ids(rank(criteria))).containsExactly("sim-430001", "sim-600001", "sim-600000",
        "sim-600005", "sim-600004");
  }

  /**
   * 筛选值不存在于数据里返回空页而不是报错：
   * {@code exchangeCodes=XX} 是合法取值，只是没有数据；报 400 就把"没有数据"
   * 错报成"参数非法"了（同 STK-02 的处理）。
   */
  @Test
  void returnsEmptyPageForUnknownFilterValueInsteadOfFailing() {
    RankingCriteria criteria = new RankingCriteria(
        "GAINERS", "XX", null, null, null, null, null, null);

    StockRanking ranking = rank(criteria);

    assertThat(ranking.items()).isEmpty();
    assertThat(ranking.total()).isZero();
    assertThat(ranking.totalPages()).isZero();
    assertThat(ranking.hasNext()).isFalse();
  }

  /**
   * 板块筛选按**成分关系**取数，而不是按"证券是否属于该板块"的某种猜测。
   *
   * <p>桩数据里 {@link StubSectorProvider#INDUSTRY_ID} 只含 {@code sim-600001} 与
   * {@code sim-600000}，其余 5 只有行情的证券都不在板块内。
   */
  @Test
  void filtersBySectorMembership() {
    RankingCriteria criteria = new RankingCriteria(
        "GAINERS", null, null, StubSectorProvider.INDUSTRY_ID, null, null, null, null);

    StockRanking ranking = rank(criteria);

    assertThat(ids(ranking)).containsExactly("sim-600001", "sim-600000");
    assertThat(ranking.total()).isEqualTo(2);
  }

  /** 两个板块的成分不同：一个板块的筛选结果不能"顺带"等于另一个板块的结果。 */
  @Test
  void keepsEachSectorMembershipIndependent() {
    RankingCriteria group = new RankingCriteria(
        "GAINERS", null, null, StubSectorProvider.GROUP_ID, null, null, null, null);

    assertThat(ids(rank(group))).containsExactly("sim-600001", "sim-000001");
  }

  /** 板块条件与交易所条件是**交集**：不是"满足其一"。 */
  @Test
  void intersectsSectorWithOtherFilters() {
    RankingCriteria criteria = new RankingCriteria(
        "GAINERS", "SH", null, StubSectorProvider.GROUP_ID, null, null, null, null);

    // 大类里的 sim-000001 在深市，被 exchangeCodes=SH 排除
    assertThat(ids(rank(criteria))).containsExactly("sim-600001");
  }

  /**
   * 板块 ID 不存在时返回空页而不是报错，与 {@code exchangeCodes=XX} 同口径：
   * 合法取值只是没有数据，报 400 就把"没有数据"错报成"参数非法"。
   */
  @Test
  void returnsEmptyPageForUnknownSectorIdInsteadOfFailing() {
    RankingCriteria criteria = new RankingCriteria(
        "GAINERS", null, null, "stub-bk-missing", null, null, null, null);

    StockRanking ranking = rank(criteria);

    assertThat(ranking.items()).isEmpty();
    assertThat(ranking.total()).isZero();
  }

  /** 存在但没有成分关系的板块同样返回空页——"板块里没有证券"是事实，不是错误。 */
  @Test
  void returnsEmptyPageForSectorWithoutMembers() {
    RankingCriteria criteria = new RankingCriteria(
        "GAINERS", null, null, StubSectorProvider.EMPTY_ID, null, null, null, null);

    assertThat(rank(criteria).items()).isEmpty();
  }

  /** 空串按"未传"处理，与 {@code exchangeCodes} 的 CSV 解析口径一致。 */
  @Test
  void treatsBlankSectorIdAsAbsent() {
    RankingCriteria criteria = new RankingCriteria(
        "GAINERS", null, null, "   ", null, null, null, null);

    assertThat(rank(criteria).total()).isEqualTo(7);
  }

  // ---------- 参数校验 ----------

  @Test
  void rejectsMissingRankingType() {
    assertThatThrownBy(() -> rank(RankingCriteria.empty()))
        .isInstanceOf(InvalidRankingQueryException.class)
        .hasMessageContaining("GAINERS");
  }

  /** 未知口径必须报错，不能回落成默认榜单——静默回落会让调用方拿到"看起来正常"的错误结果。 */
  @Test
  void rejectsUnknownRankingTypeInsteadOfFallingBack() {
    assertThatThrownBy(() -> rank(criteria("TOP100")))
        .isInstanceOf(InvalidRankingQueryException.class)
        .hasMessageContaining("rankingType");
  }

  @Test
  void acceptsRankingTypeIgnoringCase() {
    assertThat(rank(criteria("  losers  ")).rankingType()).isEqualTo("LOSERS");
  }

  @Test
  void rejectsNonPositivePageAndOutOfRangeSize() {
    assertThatThrownBy(() -> rank(new RankingCriteria(
        "GAINERS", null, null, null, null, null, 0, null)))
        .isInstanceOf(InvalidRankingQueryException.class)
        .hasMessageContaining("page");

    assertThatThrownBy(() -> rank(new RankingCriteria(
        "GAINERS", null, null, null, null, null, null, 101)))
        .isInstanceOf(InvalidRankingQueryException.class)
        .hasMessageContaining("size");
  }

  // ---------- 分页 ----------

  @Test
  void defaultsToFirstPageOfTwenty() {
    StockRanking ranking = rank(criteria("GAINERS"));

    assertThat(ranking.page()).isEqualTo(1);
    assertThat(ranking.size()).isEqualTo(20);
    assertThat(ranking.totalPages()).isEqualTo(1);
    assertThat(ranking.hasNext()).isFalse();
  }

  /** 翻到最后一页之后是正常客户端行为，返回空页并给出真实总数，便于客户端纠正页码。 */
  @Test
  void returnsEmptyPageBeyondLastPageKeepingTotal() {
    StockRanking ranking = rank(new RankingCriteria(
        "GAINERS", null, null, null, null, null, 9, 3));

    assertThat(ranking.items()).isEmpty();
    assertThat(ranking.total()).isEqualTo(7);
    assertThat(ranking.totalPages()).isEqualTo(3);
  }

  /** 不变式：逐页取完后拼接的结果，必须与不分页取全量逐元素相同。 */
  @Test
  void paginatesWithoutGapsOrDuplicates() {
    List<String> whole = ids(rank(new RankingCriteria(
        "TURNOVER", null, null, null, null, null, 1, 100)));

    List<String> stitched = new ArrayList<>();
    for (int page = 1; page <= 3; page++) {
      stitched.addAll(ids(rank(new RankingCriteria(
          "TURNOVER", null, null, null, null, null, page, 3))));
    }

    assertThat(stitched).containsExactlyElementsOf(whole);
  }

  // ---------- 批次自洽 ----------

  /**
   * 契约要求"整个榜单使用同一已完成快照版本"。外层版本号与行情时间**投影自批次**，
   * 因此必然等于每一行的取值——这条不是约定，是构造出来的。
   */
  @Test
  void projectsBatchVersionAndDataTimeFromEveryRow() {
    StockRanking ranking = rank(new RankingCriteria(
        "GAINERS", null, null, null, null, null, 1, 3));

    assertThat(ranking.snapshotVersion()).isEqualTo(SEQUENCE);
    assertThat(ranking.dataTime()).isEqualTo(DATA_TIME);
    assertThat(ranking.dataStatus()).isEqualTo(MarketOverview.DataStatus.REALTIME);
    assertThat(ranking.items()).allSatisfy(item -> {
      assertThat(item.sequence()).isEqualTo(ranking.snapshotVersion());
      assertThat(item.dataTime()).isEqualTo(ranking.dataTime());
    });
  }

  /** 批次为空时没有版本可言，返回空串而不是编造一个版本号。 */
  @Test
  void reportsUnavailableWhenBatchIsEmpty() {
    StockRanking ranking =
        new StockRankingQueryService(marketCode -> List.of(), StubSectorProvider.empty())
            .rank(criteria("GAINERS"));

    assertThat(ranking.items()).isEmpty();
    assertThat(ranking.total()).isZero();
    assertThat(ranking.snapshotVersion()).isEmpty();
    assertThat(ranking.dataTime()).isNull();
    assertThat(ranking.dataStatus()).isEqualTo(MarketOverview.DataStatus.UNAVAILABLE);
  }

  // ---------- 导出取数（M3-12） ----------

  /**
   * 全量入口与分页入口必须是**同一份**筛选与排序：导出若自己再排一遍，
   * 就会出现"页面第一名、文件里第三名"这类只在同值时暴露的偏差。
   * 这里逐项比对两个入口的结果与批次属性。
   */
  @Test
  void datasetMatchesRankOrderingAndBatchAttributes() {
    StockRanking firstPage = rank(criteria("GAINERS"));
    StockRankingDataset dataset = dataset(criteria("GAINERS"));

    assertThat(idsOf(dataset)).isEqualTo(ids(firstPage));
    assertThat(dataset.rankingType()).isEqualTo(firstPage.rankingType());
    assertThat(dataset.snapshotVersion()).isEqualTo(firstPage.snapshotVersion());
    assertThat(dataset.dataTime()).isEqualTo(firstPage.dataTime());
    assertThat(dataset.dataStatus()).isEqualTo(firstPage.dataStatus());
  }

  /** 导出没有分页：分页入口只有一页 20 条，全量入口必须给出全部 7 行。 */
  @Test
  void datasetReturnsAllRowsRegardlessOfPageSize() {
    StockRankingDataset dataset = dataset(
        new RankingCriteria("TURNOVER", null, null, null, null, null, 2, 2));

    assertThat(dataset.rowCount()).isEqualTo(7);
    assertThat(idsOf(dataset)).containsExactly(
        "sim-000001", "sim-600003", "sim-600000",
        "sim-600005", "sim-600001", "sim-430001", "sim-300001");
  }

  /** 筛选条件同样共用：按板块筛选后，两个入口看到的行集合一致。 */
  @Test
  void datasetSharesFiltersWithPagedRanking() {
    RankingCriteria industryOnly = new RankingCriteria(
        "GAINERS", null, null, StubSectorProvider.INDUSTRY_ID, null, null, null, null);

    assertThat(idsOf(dataset(industryOnly))).isEqualTo(ids(rank(industryOnly)));
  }

  // ---------- 小工具 ----------

  private static StockRanking rank(RankingCriteria criteria) {
    return new StockRankingQueryService(fixedBatch(), SECTOR_PROVIDER).rank(criteria);
  }

  private static StockRankingDataset dataset(RankingCriteria criteria) {
    return new StockRankingQueryService(fixedBatch(), SECTOR_PROVIDER).dataset(criteria);
  }

  private static List<String> idsOf(StockRankingDataset dataset) {
    return dataset.items().stream().map(item -> item.security().securityId()).toList();
  }

  private static QuoteSnapshotBatchProvider fixedBatch() {
    return marketCode -> BATCH;
  }

  /** 大类与行业的成分刻意有重叠但不相同，用来证明两个板块各自独立取数。 */
  private static final StubSectorProvider SECTOR_PROVIDER = StubSectorProvider.of(Map.of(
      StubSectorProvider.INDUSTRY_ID, List.of("sim-600001", "sim-600000"),
      StubSectorProvider.GROUP_ID, List.of("sim-600001", "sim-000001")));

  private static RankingCriteria criteria(String rankingType) {
    return new RankingCriteria(rankingType, null, null, null, null, null, null, null);
  }

  private static List<String> ids(StockRanking ranking) {
    return ranking.items().stream().map(item -> item.security().securityId()).toList();
  }

  private static int indexOf(List<String> ids, String securityId) {
    return ids.indexOf(securityId);
  }

  private static QuoteSnapshot snapshot(
      String securityId,
      String exchangeCode,
      String boardCode,
      boolean st,
      boolean suspended,
      String changeRate,
      String tradeAmount) {
    return new QuoteSnapshot(
        summary(securityId, exchangeCode, boardCode, st, suspended),
        "10.00",
        "10.10",
        "10.20",
        "10.30",
        "9.90",
        "0.20",
        changeRate,
        "1000000",
        tradeAmount,
        "0.0100",
        DATA_TIME,
        DATA_TIME,
        SEQUENCE,
        MarketOverview.DataStatus.REALTIME,
        null);
  }

  private static SecuritySummary summary(
      String securityId,
      String exchangeCode,
      String boardCode,
      boolean st,
      boolean suspended) {
    String code = securityId.substring(securityId.indexOf('-') + 1);
    return new SecuritySummary(
        securityId,
        exchangeCode + "." + code,
        code,
        "模拟证券" + code,
        exchangeCode,
        "STOCK",
        boardCode,
        suspended ? "SUSPENDED" : "LISTED",
        st,
        suspended,
        2,
        null,
        null);
  }
}
