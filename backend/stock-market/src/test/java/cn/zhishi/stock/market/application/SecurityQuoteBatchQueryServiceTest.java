package cn.zhishi.stock.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.QuoteSnapshotBatchProvider;
import cn.zhishi.stock.market.domain.SecuritySummary;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * 批量行情查询用例（契约 §8 STK-05）。
 *
 * <h2>本类钉的是"批次"与"缺失"两个语义</h2>
 * 统一批次版本取自返回项的 sequence（全缺失时为 null，不编造）；
 * 查不到的进 {@code missingSecurityIds} 而不是 404——契约的形状决定了
 * "有一只查不到"是正常答案；只有整批行情不可用才是 503。
 */
class SecurityQuoteBatchQueryServiceTest {

  private static final OffsetDateTime DATA_TIME =
      OffsetDateTime.parse("2026-09-27T15:00:00+08:00");

  @Test
  void returnsItemsInRequestOrderWithTheSharedSnapshotVersion() {
    SecurityQuoteBatchQueryService service =
        new SecurityQuoteBatchQueryService(market -> List.of(snapshot("sim-600000"), snapshot("sim-600519")));

    SecurityQuoteBatchQueryService.SecurityQuoteBatchQueryResult result =
        service.query(List.of("sim-600519", "sim-600000"));

    assertThat(result.items()).extracting(item -> item.security().securityId())
        .containsExactly("sim-600519", "sim-600000");
    assertThat(result.missingSecurityIds()).isEmpty();
    assertThat(result.snapshotVersion()).isEqualTo("sim-2026-09-27");
  }

  @Test
  void deduplicatesAndReportsMissingIdsInsteadOfFailing() {
    SecurityQuoteBatchQueryService service =
        new SecurityQuoteBatchQueryService(market -> List.of(snapshot("sim-600000")));

    SecurityQuoteBatchQueryService.SecurityQuoteBatchQueryResult result =
        service.query(List.of("sim-600000", "sim-600000", "sim-999999"));

    assertThat(result.items()).hasSize(1);
    assertThat(result.missingSecurityIds()).containsExactly("sim-999999");
    assertThat(result.snapshotVersion()).isEqualTo("sim-2026-09-27");
  }

  /** 全部缺失时没有批次可报：snapshotVersion 为 null 而不是编一个版本号。 */
  @Test
  void leavesTheSnapshotVersionNullWhenEverythingIsMissing() {
    SecurityQuoteBatchQueryService service =
        new SecurityQuoteBatchQueryService(market -> List.of(snapshot("sim-600000")));

    SecurityQuoteBatchQueryService.SecurityQuoteBatchQueryResult result =
        service.query(List.of("sim-999999"));

    assertThat(result.items()).isEmpty();
    assertThat(result.missingSecurityIds()).containsExactly("sim-999999");
    assertThat(result.snapshotVersion()).isNull();
  }

  @Test
  void rejectsAnEmptyRequest() {
    SecurityQuoteBatchQueryService service =
        new SecurityQuoteBatchQueryService(market -> List.of(snapshot("sim-600000")));

    assertThatThrownBy(() -> service.query(List.of()))
        .isInstanceOf(InvalidSecurityQueryException.class);
  }

  @Test
  void rejectsABlankSecurityId() {
    SecurityQuoteBatchQueryService service =
        new SecurityQuoteBatchQueryService(market -> List.of(snapshot("sim-600000")));

    assertThatThrownBy(() -> service.query(List.of("sim-600000", "  ")))
        .isInstanceOf(InvalidSecurityQueryException.class);
  }

  /** 上限按"去重后"计算：同一只提交 60 次是 1 只，不超限。 */
  @Test
  void countsTheLimitAgainstDeduplicatedIds() {
    SecurityQuoteBatchQueryService service =
        new SecurityQuoteBatchQueryService(market -> List.of(snapshot("sim-600000")));

    List<String> duplicated = IntStream.rangeClosed(1, 60)
        .mapToObj(index -> "sim-600000")
        .toList();

    assertThat(service.query(duplicated).items()).hasSize(1);
  }

  @Test
  void rejectsMoreThanFiftyDistinctSecurities() {
    SecurityQuoteBatchQueryService service =
        new SecurityQuoteBatchQueryService(market -> List.of(snapshot("sim-600000")));

    List<String> tooMany = IntStream.rangeClosed(0, 50)
        .mapToObj(index -> "sim-%06d".formatted(index))
        .toList();

    assertThatThrownBy(() -> service.query(tooMany))
        .isInstanceOf(InvalidSecurityQueryException.class)
        .hasMessageContaining("50");
  }

  @Test
  void propagatesMarketUnavailabilityAs503() {
    SecurityQuoteBatchQueryService service = new SecurityQuoteBatchQueryService(market -> List.of());

    assertThatThrownBy(() -> service.query(List.of("sim-600000")))
        .isInstanceOf(MarketDataUnavailableException.class);
  }

  // ---------- 夹具 ----------

  private static QuoteSnapshot snapshot(String securityId) {
    SecuritySummary security = new SecuritySummary(
        securityId,
        "SSE." + securityId.substring(4),
        securityId.substring(4),
        "证券 " + securityId,
        "SSE",
        "STOCK",
        "MAIN",
        "LISTED",
        false,
        false,
        2,
        null,
        null);
    return new QuoteSnapshot(
        security,
        "12.00", "12.10", "12.34", "12.50", "11.90", "0.34", "0.0283",
        "1200000", "14700000.00", "0.0125",
        DATA_TIME,
        DATA_TIME,
        "sim-2026-09-27",
        cn.zhishi.stock.market.domain.MarketOverview.DataStatus.REALTIME,
        null);
  }
}
