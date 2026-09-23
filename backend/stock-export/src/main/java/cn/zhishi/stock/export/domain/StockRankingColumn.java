package cn.zhishi.stock.export.domain;

import cn.zhishi.stock.market.domain.QuoteSnapshot;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 榜单导出的列白名单（契约 §9.2 EXP-01："只允许导出白名单字段"）。
 *
 * <h2>为什么是枚举而不是从请求里读字段名</h2>
 * 契约 §22.2 与 PRD §12 同一条规则："搜索、排序和筛选字段使用白名单映射；
 * 不接受客户端原始字段名"。若按请求给的字段名反射取值，客户端就能决定导出什么
 * ——今天是内部字段泄漏，明天是 {@code securityId} 这类代理键外流。
 * 枚举让"能导出什么"成为编译期事实：请求里出现枚举之外的 key 直接 400。
 *
 * <h2>取值口径跟着列走</h2>
 * 每一列的取值函数与它的表头定义在同一个常量上。分成"表头表"和"取值表"两份的话，
 * 漏改一份的表现是**表头写着"涨跌幅"、列里是成交额**——这种错不会报任何异常。
 * 显示格式（{@link ExportColumnFormat}）同样挂在这里：涨跌幅必须按百分比显示，
 * 而这个知识只有"知道这一列是什么"的地方才掌握。
 */
public enum StockRankingColumn {

    SECURITY_CODE("securityCode", "证券代码", ExportColumnFormat.TEXT,
            snapshot -> ExportCell.text(snapshot.security().securityCode())),
    SECURITY_NAME("securityName", "证券名称", ExportColumnFormat.TEXT,
            snapshot -> ExportCell.text(snapshot.security().securityName())),
    EXCHANGE_CODE("exchangeCode", "交易所", ExportColumnFormat.TEXT,
            snapshot -> ExportCell.text(snapshot.security().exchangeCode())),
    BOARD_CODE("boardCode", "板块", ExportColumnFormat.TEXT,
            snapshot -> ExportCell.text(snapshot.security().boardCode())),
    LATEST_PRICE("latestPrice", "最新价", ExportColumnFormat.PRICE,
            snapshot -> ExportCell.decimal(snapshot.latestPrice())),
    PREVIOUS_CLOSE_PRICE("previousClosePrice", "昨收价", ExportColumnFormat.PRICE,
            snapshot -> ExportCell.decimal(snapshot.previousClosePrice())),
    CHANGE_AMOUNT("changeAmount", "涨跌额", ExportColumnFormat.PRICE,
            snapshot -> ExportCell.decimal(snapshot.changeAmount())),
    // 涨跌幅与换手率都是**小数形式的比率**（0.0300 表示 3%），必须按百分比显示，
    // 否则文件里写 0.03 会被读成 0.03%——差 100 倍的误读，且打开看不出任何异常。
    CHANGE_RATE("changeRate", "涨跌幅", ExportColumnFormat.PERCENT,
            snapshot -> ExportCell.decimal(snapshot.changeRate())),
    // 成交量与成交额在契约里是整数字符串；保留小数位只会带来无意义的噪声。
    TRADE_VOLUME("tradeVolume", "成交量", ExportColumnFormat.AMOUNT,
            snapshot -> ExportCell.decimal(snapshot.tradeVolume())),
    TRADE_AMOUNT("tradeAmount", "成交额", ExportColumnFormat.AMOUNT,
            snapshot -> ExportCell.decimal(snapshot.tradeAmount())),
    TURNOVER_RATE("turnoverRate", "换手率", ExportColumnFormat.PERCENT,
            snapshot -> ExportCell.decimal(snapshot.turnoverRate())),
    IS_ST("isSt", "是否 ST", ExportColumnFormat.TEXT,
            snapshot -> ExportCell.flag(snapshot.security().isSt())),
    IS_SUSPENDED("isSuspended", "是否停牌", ExportColumnFormat.TEXT,
            snapshot -> ExportCell.flag(snapshot.security().isSuspended())),
    DATA_TIME("dataTime", "数据时间", ExportColumnFormat.TEXT,
            snapshot -> ExportCell.text(format(snapshot.dataTime())));

    /** 与接口响应同名的时间格式：秒级、带时区，便于与页面上的数字对账。 */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX", Locale.ROOT);

    private final ExportColumn column;
    private final ColumnValue value;

    StockRankingColumn(String key, String header, ExportColumnFormat format, ColumnValue value) {
        this.column = new ExportColumn(key, header, format);
        this.value = value;
    }

    /** 取某一行的这一格。 */
    public ExportCell cellOf(QuoteSnapshot snapshot) {
        return value.cellOf(snapshot);
    }

    public String key() {
        return column.key();
    }

    public String header() {
        return column.header();
    }

    public ExportColumn column() {
        return column;
    }

    /**
     * 默认列集。
     *
     * <p>刻意不含昨收价：三种榜单（涨幅 / 跌幅 / 成交额）共用一份默认列，
     * 而"涨跌额 + 涨跌幅 + 最新价"已经能还原出方向与基准；
     * 把一个只对部分用户有用的列塞进默认集，会让每次导出都多一列噪声。
     * 需要它的人可以在 {@code columns} 里显式点名。
     */
    public static List<StockRankingColumn> defaultColumns() {
        return List.of(
                SECURITY_CODE, SECURITY_NAME, EXCHANGE_CODE, BOARD_CODE,
                LATEST_PRICE, CHANGE_AMOUNT, CHANGE_RATE,
                TRADE_VOLUME, TRADE_AMOUNT, TURNOVER_RATE,
                IS_ST, IS_SUSPENDED, DATA_TIME);
    }

    /**
     * 把请求里的列 key 解析成列。顺序**按请求给的顺序**保留——调用方点名的顺序就是他要的顺序。
     *
     * <p>重复的 key 只保留第一次出现的位置。理由不是洁癖：两次点名同一列会在文件里
     * 并排出现两列表头相同的数字，而"这两列本该是一列"这件事从文件本身看不出来。
     * 保留首次出现处，既去了重，又不打乱调用方给的顺序。
     *
     * <p>白名单之外的 key 在这里被**静默丢弃**，因为这一层不是判据——判据在
     * {@link #firstUnknown}，由用例层先行调用并给出 400。分两道是为了让报错点
     * 能说出"是哪个 key 不合法"，而这里只负责"把合法的挑出来"。
     */
    public static List<StockRankingColumn> resolve(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return defaultColumns();
        }
        Map<String, StockRankingColumn> byKey = byKey();
        return keys.stream()
                .map(key -> key == null ? null : byKey.get(key.trim()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * 请求里是否出现了白名单之外的列 key。
     *
     * <p>{@code null} 元素单独报成 {@code "null"}：JSON 数组里出现 null 是可能的，
     * 若把它归一成空串，调用方收到的会是一句"含白名单之外的字段："
     * ——冒号后面什么都没有，没人能据此改对请求。
     */
    public static Optional<String> firstUnknown(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Optional.empty();
        }
        Map<String, StockRankingColumn> byKey = byKey();
        for (String key : keys) {
            if (key == null) {
                return Optional.of("null");
            }
            String trimmed = key.trim();
            if (!byKey.containsKey(trimmed)) {
                return Optional.of(trimmed);
            }
        }
        return Optional.empty();
    }

    public static List<String> keys() {
        return Arrays.stream(values()).map(StockRankingColumn::key).toList();
    }

    private static Map<String, StockRankingColumn> byKey() {
        Map<String, StockRankingColumn> byKey = new LinkedHashMap<>();
        for (StockRankingColumn column : values()) {
            byKey.put(column.key(), column);
        }
        return byKey;
    }

    private static String format(java.time.OffsetDateTime time) {
        return time == null ? "" : TIME_FORMATTER.format(time);
    }

    @FunctionalInterface
    private interface ColumnValue {
        ExportCell cellOf(QuoteSnapshot snapshot);
    }
}
