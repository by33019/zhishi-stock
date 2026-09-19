package cn.zhishi.stock.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhishi.stock.market.domain.TurnoverTrend;
import cn.zhishi.stock.market.domain.TurnoverTrendProvider;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TurnoverTrendQueryServiceTest {

  private static final OffsetDateTime CUTOFF =
      OffsetDateTime.of(2026, 9, 11, 11, 30, 0, 0, ZoneOffset.ofHours(8));

  @Test
  void defaultsToOneMinuteIntervalForIntradayRange() {
    var service = service(true);

    var result = service.getTrend("CN", "TODAY", null);

    assertThat(result.range()).isEqualTo("TODAY");
    assertThat(result.interval()).isEqualTo("1m");
  }

  @Test
  void acceptsEveryDocumentedInterval() {
    var service = service(true);

    for (String interval : List.of("1m", "5m", "15m", "30m", "60m")) {
      assertThat(service.getTrend("CN", "TODAY", interval).interval()).isEqualTo(interval);
    }
  }

  @Test
  void acceptsDailyRangesAndOmitsInterval() {
    var service = service(true);

    for (String range : List.of("5D", "20D")) {
      var result = service.getTrend("CN", range, null);

      assertThat(result.range()).isEqualTo(range);
      assertThat(result.interval()).isNull();
    }
  }

  @Test
  void normalizesMarketCodeRangeAndInterval() {
    var service = service(true);

    var result = service.getTrend("cn", "today", "5M");

    assertThat(result.marketCode()).isEqualTo("CN");
    assertThat(result.range()).isEqualTo("TODAY");
    assertThat(result.interval()).isEqualTo("5m");
  }

  @Test
  void rejectsMissingOrUnknownRange() {
    var service = service(true);

    assertThatThrownBy(() -> service.getTrend("CN", null, null))
        .isInstanceOf(InvalidTurnoverParameterException.class)
        .hasMessageContaining("range");
    assertThatThrownBy(() -> service.getTrend("CN", "30D", null))
        .isInstanceOf(InvalidTurnoverParameterException.class)
        .hasMessageContaining("range");
  }

  @Test
  void rejectsUnsupportedInterval() {
    var service = service(true);

    assertThatThrownBy(() -> service.getTrend("CN", "TODAY", "2m"))
        .isInstanceOf(InvalidTurnoverParameterException.class)
        .hasMessageContaining("interval");
  }

  @Test
  void rejectsIntervalOnDailyRangeInsteadOfSilentlyIgnoringIt() {
    var service = service(true);

    assertThatThrownBy(() -> service.getTrend("CN", "5D", "5m"))
        .isInstanceOf(InvalidTurnoverParameterException.class)
        .hasMessageContaining("5D");
  }

  @Test
  void throwsMarketNotFoundWhenProviderHasNoTrendForTheMarket() {
    var service = service(false);

    assertThatThrownBy(() -> service.getTrend("US", "TODAY", null))
        .isInstanceOf(MarketNotFoundException.class)
        .hasMessageContaining("US");
  }

  /** 桩 Provider 回显收到的参数，使断言验证的是服务层真正传下去的值。 */
  private static TurnoverTrendQueryService service(boolean marketSupported) {
    TurnoverTrendProvider provider = (marketCode, range, interval) -> marketSupported
        ? Optional.of(new TurnoverTrend(
            marketCode,
            range.code(),
            interval,
            TurnoverTrend.Unit.standard(),
            CUTOFF,
            List.of(new TurnoverTrend.Point("2026-09-11T11:30:00+08:00", "100", "10"))))
        : Optional.empty();
    return new TurnoverTrendQueryService(provider);
  }
}
