package cn.zhishi.stock.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketSessionStatus;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcMarketOverviewArchiveTest {

  private static final OffsetDateTime DATA_TIME =
      OffsetDateTime.of(2026, 9, 11, 10, 0, 0, 0, ZoneOffset.ofHours(8));

  @Test
  void readsMostRecentStandardizedSnapshot() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    var snapshot = snapshot();
    when(jdbc.query(
        anyString(),
        org.mockito.ArgumentMatchers.<RowMapper<MarketOverview>>any(),
        eq("CN")))
        .thenReturn(List.of(snapshot));
    var archive = new JdbcMarketOverviewArchive(jdbc, codec(), () -> 1001L);

    assertThat(archive.findLatest("CN")).contains(snapshot);
  }

  @Test
  void readsSnapshotAtOrBeforeRequestedMoment() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    var snapshot = snapshot();
    when(jdbc.query(
        anyString(),
        org.mockito.ArgumentMatchers.<RowMapper<MarketOverview>>any(),
        eq("CN"),
        any(Timestamp.class)))
        .thenReturn(List.of(snapshot));
    var archive = new JdbcMarketOverviewArchive(jdbc, codec(), () -> 1001L);

    assertThat(archive.findAt("CN", DATA_TIME.plusHours(1))).contains(snapshot);
  }

  @Test
  void returnsEmptyWhenNoSnapshotIsAtOrBeforeRequestedMoment() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(
        anyString(),
        org.mockito.ArgumentMatchers.<RowMapper<MarketOverview>>any(),
        eq("CN"),
        any(Timestamp.class)))
        .thenReturn(List.of());
    var archive = new JdbcMarketOverviewArchive(jdbc, codec(), () -> 1001L);

    assertThat(archive.findAt("CN", DATA_TIME)).isEmpty();
  }

  private static MarketOverviewJsonCodec codec() {
    return new MarketOverviewJsonCodec(
        JsonMapper.builder().addModule(new JavaTimeModule()).build());
  }

  private static MarketOverview snapshot() {
    return new MarketOverview(
        "CN",
        MarketSessionStatus.TRADING,
        LocalDate.of(2026, 9, 11),
        DATA_TIME,
        MarketOverview.DataStatus.REALTIME,
        List.of(),
        new MarketOverview.BreadthData(1, 1, 0, 0, 0, 0),
        new MarketOverview.TurnoverData("0", "0", List.of()),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        DATA_TIME,
        "v1");
  }
}
