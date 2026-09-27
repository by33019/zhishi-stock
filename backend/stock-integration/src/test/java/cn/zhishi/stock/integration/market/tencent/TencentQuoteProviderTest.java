package cn.zhishi.stock.integration.market.tencent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.SecuritySummary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 腾讯适配器的编排固定（解析器另有字段级测试）。
 *
 * <h2>本类钉的是缓存、分片与换算</h2>
 * 20 秒 TTL 内复用同一批（频控保护）；TTL 过期后刷新；60 只一片、
 * 多片串行；手→股、万元→元、百分数→小数的三条换算各有一条字段级断言。
 * 时钟走手动的 {@code MutableClock}，缓存过期用推时钟模拟而不是 sleep。
 */
class TencentQuoteProviderTest {

  private static final Instant START = Instant.parse("2026-09-27T02:00:00Z");
  private static final String PAYLOAD = """
      v_sh600000="1~浦发银行~600000~9.00~8.98~8.99~528364~280575~247789~9.00~1887~8.99~4117~8.98~2916~8.97~5001~8.96~5904~9.01~1053~9.02~8162~9.03~16537~9.04~9345~9.05~17969~~20260924161454~0.02~0.22~9.05~8.97~9.00/528364/475964884~528364~47596~0.16~5.85~~9.05~8.97~0.89~2997.53~2997.53~0.40~9.88~8.08~0.99~-33241~9.01~4.84~5.99~~~0.00~47596.4884~36.8100~409~   A~GP-A~-25.12~-0.66~4.67~6.14~0.50~13.11~8.07~-3.74~-0.77~8.70~33305838300~33305838300~-45.60~-23.40~33305838300~~~-23.73~-0.11~~CNY~0~___D__F__N~9.06~-9141~";
      v_sz000001="51~平安银行~000001~11.30~11.35~11.35~1043819~475274~568545~11.30~2697~11.29~10758~11.28~5643~11.27~1406~11.26~2556~11.31~2612~11.32~1174~11.33~785~11.34~140~11.35~904~~20260924161421~-0.05~-0.44~11.47~11.29~11.30/1043819/1186736896~1043819~118674~0.54~5.05~~11.47~11.29~1.59~2192.84~2192.87~0.47~12.49~10.22~1.30~17445~11.37~4.27~5.14~~~0.17~118673.6896~3.0092~27~   A~GP-A~4.62~-0.54~5.39~7.93~0.72~11.83~9.74~-2.59~-0.36~12.65~19405684991~19405918198~60.84~3.19~19405684991~~~6.45~-0.09~~CNY~0~C~11.22~3864~";
      """;

  private final StubFetcher fetcher = new StubFetcher(PAYLOAD);
  private final MutableClock clock = new MutableClock(START);

  @Test
  void buildsSnapshotsWithContractUnitsAndSharedSequence() {
    TencentQuoteProvider provider = provider(Duration.ofSeconds(20));

    List<QuoteSnapshot> batch = provider.fetchBatch("CN");

    assertThat(batch).hasSize(2);
    QuoteSnapshot sh600000 = batch.get(0);
    assertThat(sh600000.security().securityId()).isEqualTo("sim-600000");
    // 价格三件套原样透传
    assertThat(sh600000.latestPrice()).isEqualTo("9.00");
    assertThat(sh600000.previousClosePrice()).isEqualTo("8.98");
    // 手 → 股、万元 → 元、百分数 → 小数
    assertThat(sh600000.tradeVolume()).isEqualTo("52836400");
    assertThat(sh600000.tradeAmount()).isEqualTo("475960000");
    assertThat(sh600000.changeRate()).isEqualTo("0.0022");
    assertThat(sh600000.turnoverRate()).isEqualTo("0.0016");
    // 同一批共用一个 sequence（契约 STK-05 的"统一批次版本"）
    assertThat(sh600000.sequence()).isEqualTo(batch.get(1).sequence());
    assertThat(sh600000.sequence()).startsWith("tencent-");
    assertThat(sh600000.dataStatus()).isEqualTo(MarketOverview.DataStatus.REALTIME);
    // 周六拉到的是 09-24 收盘：delaySeconds 如实反映数据年龄，而不是编 0
    assertThat(sh600000.dataTime()).isNotNull();
    assertThat(sh600000.delaySeconds()).isGreaterThanOrEqualTo(0);
  }

  @Test
  void reusesTheCachedBatchWithinTheTtl() {
    TencentQuoteProvider provider = provider(Duration.ofSeconds(20));

    provider.fetchBatch("CN");
    clock.advance(Duration.ofSeconds(10));
    provider.fetchBatch("CN");

    assertThat(fetcher.calls).hasSize(1);
  }

  @Test
  void refetchesWhenTheTtlExpires() {
    TencentQuoteProvider provider = provider(Duration.ofSeconds(20));

    provider.fetchBatch("CN");
    clock.advance(Duration.ofSeconds(21));
    provider.fetchBatch("CN");

    assertThat(fetcher.calls).hasSize(2);
  }

  /** 缓存命中时单只查询不再发外网请求。 */
  @Test
  void servesSingleQuotesFromTheCachedBatch() {
    TencentQuoteProvider provider = provider(Duration.ofSeconds(20));
    provider.fetchBatch("CN");
    int callsAfterBatch = fetcher.calls.size();

    assertThat(provider.fetch("sim-600000", "CN")).isPresent();
    assertThat(fetcher.calls).hasSize(callsAfterBatch);
  }

  /** 缓存为空（冷启动）时单只查询定向探测 sh+sz 两个市场。 */
  @Test
  void probesBothMarketsOnAColdSingleQuote() {
    TencentQuoteProvider provider = provider(Duration.ofSeconds(20));

    assertThat(provider.fetch("sim-600000", "CN")).isPresent();
    assertThat(fetcher.calls.get(0)).isEqualTo("sh600000,sz600000");
  }

  /** 61 只证券 → 2 个请求（60 + 1），分片边界正确。 */
  @Test
  void chunksRequestsAtSixtyCodes() {
    List<SecuritySummary> many = new ArrayList<>();
    many.add(summary("sim-600000", "SH"));
    for (int index = 1; index <= 60; index++) {
      many.add(summary("sim-%06d".formatted(index), "SZ"));
    }
    StubFetcher multiFetcher = new StubFetcher(PAYLOAD);
    TencentQuoteProvider provider = new TencentQuoteProvider(
        multiFetcher, market -> many, clock, 0, Duration.ofSeconds(20));

    provider.fetchBatch("CN");

    assertThat(multiFetcher.calls).hasSize(2);
    assertThat(multiFetcher.calls.get(0).split(",").length).isEqualTo(60);
  }

  /** SZ 代码进 sz 前缀：市场前缀映射按 exchangeCode 而不是代码首位猜。 */
  @Test
  void mapsExchangeCodesToRequestPrefixes() {
    TencentQuoteProvider provider = provider(Duration.ofSeconds(20));
    provider.fetchBatch("CN");

    assertThat(fetcher.calls.get(0)).isEqualTo("sh600000,sz000001");
  }

  // ---------- 夹具 ----------

  private TencentQuoteProvider provider(Duration cacheTtl) {
    return new TencentQuoteProvider(
        fetcher,
        market -> List.of(
            summary("sim-600000", "SH"),
            summary("sim-000001", "SZ")),
        clock,
        0,
        cacheTtl);
  }

  private static SecuritySummary summary(String securityId, String exchangeCode) {
    String code = securityId.substring(4);
    return new SecuritySummary(
        securityId,
        exchangeCode + "." + code,
        code,
        "证券 " + code,
        exchangeCode,
        "STOCK",
        "MAIN",
        "LISTED",
        false,
        false,
        2,
        null,
        null);
  }

  /** 记录每次请求的代码串；响应固定为真实报文。 */
  private static final class StubFetcher implements TencentQuoteFetcher {

    private final String payload;
    private final List<String> calls = new ArrayList<>();

    private StubFetcher(String payload) {
      this.payload = payload;
    }

    @Override
    public String fetch(String codesCsv) {
      calls.add(codesCsv);
      return payload;
    }
  }

  /** 可手动推进的时钟（缓存 TTL 的确定性测试）。 */
  private static final class MutableClock extends Clock {

    private Instant instant;

    private MutableClock(Instant start) {
      this.instant = start;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("Asia/Shanghai");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
