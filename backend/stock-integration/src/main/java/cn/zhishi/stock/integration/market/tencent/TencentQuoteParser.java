package cn.zhishi.stock.integration.market.tencent;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 腾讯行情响应的解析（**纯函数**：字符串进、记录出，无 IO 无时钟）。
 *
 * <h2>为什么单独一个解析器</h2>
 * 腾讯的返回是 GBK 编码、{@code ~} 分隔的**位置数组**——第 4 位是最新价、
 * 第 32 位是涨跌幅。位置契约没有任何自描述性，写错一位不会报错，
 * 只会让"浦发银行的涨幅显示成平安银行的成交量"。把它抽成纯函数，
 * 用**真实响应**做夹具逐字段断言，是唯一能钉住它的方式。
 *
 * <h2>格式（2026-09 实测）</h2>
 * {@code v_sh600000="1~浦发银行~600000~9.00~8.98~8.99~...~20260924161454~0.02~0.22~9.05~8.97~...~528364~47596~0.16~...";}
 * 位置（0 起）：1 名称、2 代码、3 最新价、4 昨收、5 今开、
 * 30 时间（{@code yyyyMMddHHmmss}）、31 涨跌额、32 涨跌幅（百分数值）、
 * 33 最高、34 最低、36 成交量（手）、37 成交额（万元）、38 换手率（百分数值）。
 * 无效代码的行是 {@code v_pv_none="1"}，直接跳过。
 */
public final class TencentQuoteParser {

    /** 一条解析后的行情（原始字符串形态，换算由 Provider 负责）。 */
    public record ParsedQuote(
            String marketPrefix,
            String code,
            String name,
            String latestPrice,
            String previousClosePrice,
            String openPrice,
            String highPrice,
            String lowPrice,
            String changeAmount,
            String changeRatePercent,
            String volumeHands,
            String amountWan,
            String turnoverRatePercent,
            LocalDateTime tradedAt) {
    }

    private static final Pattern LINE = Pattern.compile("v_([a-z]{2})(\\d+)=\"([^\"]*)\"");
    private static final DateTimeFormatter TRADED_AT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** 解析所需的最低字段数（换手率在第 38 位）。 */
    private static final int MIN_FIELDS = 39;

    private TencentQuoteParser() {
    }

    public static List<ParsedQuote> parse(String payload) {
        List<ParsedQuote> quotes = new ArrayList<>();
        if (payload == null || payload.isBlank()) {
            return quotes;
        }
        Matcher matcher = LINE.matcher(payload);
        while (matcher.find()) {
            String marketPrefix = matcher.group(1);
            String code = matcher.group(2);
            String[] fields = matcher.group(3).split("~", -1);
            if (fields.length < MIN_FIELDS) {
                continue;
            }
            quotes.add(new ParsedQuote(
                    marketPrefix,
                    code,
                    fields[1],
                    numeric(fields[3]),
                    numeric(fields[4]),
                    numeric(fields[5]),
                    numeric(fields[33]),
                    numeric(fields[34]),
                    numeric(fields[31]),
                    numeric(fields[32]),
                    numeric(fields[36]),
                    numeric(fields[37]),
                    numeric(fields[38]),
                    tradedAt(fields[30])));
        }
        return quotes;
    }

    /** 空串统一成 null；数值字段本身不做格式改写（单位换算归 Provider）。 */
    private static String numeric(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static LocalDateTime tradedAt(String value) {
        if (value == null || value.length() != 14 || !value.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return LocalDateTime.parse(value, TRADED_AT);
    }
}
