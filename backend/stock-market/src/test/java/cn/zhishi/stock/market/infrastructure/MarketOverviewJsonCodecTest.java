package cn.zhishi.stock.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketSessionStatus;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarketOverviewJsonCodecTest {

  @Test
  void roundTripsStandardizedSnapshot() {
    var mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    var codec = new MarketOverviewJsonCodec(mapper);
    var now = OffsetDateTime.of(2026, 9, 11, 10, 0, 0, 0, ZoneOffset.ofHours(8));
    var snapshot = new MarketOverview(
        "CN",
        MarketSessionStatus.TRADING,
        LocalDate.of(2026, 9, 11),
        now,
        MarketOverview.DataStatus.DELAYED,
        List.of(),
        new MarketOverview.BreadthData(10, 5, 1, 2, 0),
        new MarketOverview.TurnoverData("100亿", "90亿", List.of(1.0, 2.0)),
        List.of(),
        List.of(),
        List.of(),
        Map.of("indices", MarketOverview.DataStatus.REALTIME),
        now,
        "v1");

    var json = codec.encode(snapshot);
    assertThat(json).contains("2026-09-11T10:00:00+08:00");

    var decoded = codec.decode(json);

    assertThat(decoded).isEqualTo(snapshot);
  }
}
