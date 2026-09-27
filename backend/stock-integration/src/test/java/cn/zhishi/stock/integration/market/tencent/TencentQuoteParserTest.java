package cn.zhishi.stock.integration.market.tencent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 腾讯行情解析器的字段级固定。
 *
 * <h2>夹具是 2026-09-27 实测的真实响应</h2>
 * 位置数组没有任何自描述性——第 3 位是最新价还是昨收，只有拿真实数据
 * 逐位核对才能钉住。这里按实测报文断言每个用到的字段；腾讯调整字段顺序时，
 * 这组断言是第一道（也是唯一一道）防线。
 */
class TencentQuoteParserTest {

  /** 2026-09-27 抓取的原始响应（GBK 解码后），周六拉到的是 09-24 收盘。 */
  private static final String REAL_PAYLOAD = """
      v_sh600000="1~浦发银行~600000~9.00~8.98~8.99~528364~280575~247789~9.00~1887~8.99~4117~8.98~2916~8.97~5001~8.96~5904~9.01~1053~9.02~8162~9.03~16537~9.04~9345~9.05~17969~~20260924161454~0.02~0.22~9.05~8.97~9.00/528364/475964884~528364~47596~0.16~5.85~~9.05~8.97~0.89~2997.53~2997.53~0.40~9.88~8.08~0.99~-33241~9.01~4.84~5.99~~~0.00~47596.4884~36.8100~409~   A~GP-A~-25.12~-0.66~4.67~6.14~0.50~13.11~8.07~-3.74~-0.77~8.70~33305838300~33305838300~-45.60~-23.40~33305838300~~~-23.73~-0.11~~CNY~0~___D__F__N~9.06~-9141~";
      v_sz000001="51~平安银行~000001~11.30~11.35~11.35~1043819~475274~568545~11.30~2697~11.29~10758~11.28~5643~11.27~1406~11.26~2556~11.31~2612~11.32~1174~11.33~785~11.34~140~11.35~904~~20260924161421~-0.05~-0.44~11.47~11.29~11.30/1043819/1186736896~1043819~118674~0.54~5.05~~11.47~11.29~1.59~2192.84~2192.87~0.47~12.49~10.22~1.30~17445~11.37~4.27~5.14~~~0.17~118673.6896~3.0092~27~   A~GP-A~4.62~-0.54~5.39~7.93~0.72~11.83~9.74~-2.59~-0.36~12.65~19405684991~19405918198~60.84~3.19~19405684991~~~6.45~-0.09~~CNY~0~C~11.22~3864~";
      """;

  @Test
  void parsesRealPayloadFieldByField() {
    List<TencentQuoteParser.ParsedQuote> quotes = TencentQuoteParser.parse(REAL_PAYLOAD);

    assertThat(quotes).hasSize(2);

    TencentQuoteParser.ParsedQuote sh600000 = quotes.get(0);
    assertThat(sh600000.marketPrefix()).isEqualTo("sh");
    assertThat(sh600000.code()).isEqualTo("600000");
    assertThat(sh600000.name()).isEqualTo("浦发银行");
    assertThat(sh600000.latestPrice()).isEqualTo("9.00");
    assertThat(sh600000.previousClosePrice()).isEqualTo("8.98");
    assertThat(sh600000.openPrice()).isEqualTo("8.99");
    assertThat(sh600000.highPrice()).isEqualTo("9.05");
    assertThat(sh600000.lowPrice()).isEqualTo("8.97");
    assertThat(sh600000.changeAmount()).isEqualTo("0.02");
    assertThat(sh600000.changeRatePercent()).isEqualTo("0.22");
    assertThat(sh600000.volumeHands()).isEqualTo("528364");
    assertThat(sh600000.amountWan()).isEqualTo("47596");
    assertThat(sh600000.turnoverRatePercent()).isEqualTo("0.16");
    assertThat(sh600000.tradedAt()).isEqualTo(LocalDateTime.of(2026, 9, 24, 16, 14, 54));

    TencentQuoteParser.ParsedQuote sz000001 = quotes.get(1);
    assertThat(sz000001.marketPrefix()).isEqualTo("sz");
    assertThat(sz000001.name()).isEqualTo("平安银行");
    assertThat(sz000001.latestPrice()).isEqualTo("11.30");
  }

  @Test
  void skipsInvalidCodeLinesAndBlankPayloads() {
    assertThat(TencentQuoteParser.parse("v_pv_none=\"1\";")).isEmpty();
    assertThat(TencentQuoteParser.parse("")).isEmpty();
    assertThat(TencentQuoteParser.parse(null)).isEmpty();
  }

  @Test
  void skipsLinesWithTooFewFields() {
    // 半截响应：字段数不足时整行跳过，而不是拿 null 拼一张假的快照
    assertThat(TencentQuoteParser.parse("v_sh600000=\"1~浦发银行~600000~9.00\";")).isEmpty();
  }
}
