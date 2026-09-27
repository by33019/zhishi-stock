package cn.zhishi.stock.integration.market.tencent;

import cn.zhishi.stock.integration.market.SimulatedSecurityIds;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.QuoteSnapshotBatchProvider;
import cn.zhishi.stock.market.domain.QuoteSnapshotProvider;
import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecuritySummary;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 腾讯行情适配器（方案 A：免费准实时，仅学习用途）——实现 STK-04 单只
 * （{@link QuoteSnapshotProvider}）与整批（{@link QuoteSnapshotBatchProvider}）两个端口。
 *
 * <h2>20 秒内存缓存：既保新鲜，又护频控</h2>
 * 全市场 5149 只按 60 只一片要发 86 个请求；若每次页面查询都现拉，
 * 一次榜单浏览就是一轮频控风险。因此整批结果带 **TTL 缓存**（默认 20 秒）：
 * 缓存过期后的第一个调用者触发刷新并**串行等待**，其余调用者直接复用——
 * 15~30 秒的准实时语义由此成立。单只查询（STK-04）优先吃同一份缓存，
 * 缓存里没有该证券时才按代码定向取一次。
 *
 * <h2>单位换算全部收敛在这里</h2>
 * 腾讯口径：量=手、额=万元、比例=百分数值；契约口径：量=股、额=元、比例=小数
 * （"0.10" 即 10%）。换算只在本类出现一次——写错单位不会报错，
 * 只会让成交额差一万倍，因此每条换算都有对应的字段级测试。
 *
 * <h2>数据状态与版本</h2>
 * {@code dataStatus=REALTIME}、{@code delaySeconds} 用当前时刻减数据时刻如实计算
 * （周末拉到的就是上一交易日收盘，延迟是几天而不是 0——诚实比好看重要）。
 * {@code sequence} 一次整批共用一个（{@code tencent-<抓取时刻>}），契约要求的
 * "统一批次版本"由此成立。
 */
public class TencentQuoteProvider implements QuoteSnapshotProvider, QuoteSnapshotBatchProvider {

    /** 每个请求最多携带的代码数：腾讯单次约 60 只上限。 */
    private static final int CODES_PER_REQUEST = 60;
    private static final ZoneId DATA_ZONE = ZoneId.of("Asia/Shanghai");

    private final TencentQuoteFetcher fetcher;
    private final SecurityMasterProvider securityMaster;
    private final Clock clock;
    private final long chunkDelayMillis;
    private final Duration cacheTtl;

    private final AtomicReference<CachedBatch> cachedBatch = new AtomicReference<>();
    private final AtomicLong cycleSequence = new AtomicLong();

    public TencentQuoteProvider(
            TencentQuoteFetcher fetcher,
            SecurityMasterProvider securityMaster,
            Clock clock,
            long chunkDelayMillis,
            Duration cacheTtl) {
        this.fetcher = fetcher;
        this.securityMaster = securityMaster;
        this.clock = clock;
        this.chunkDelayMillis = chunkDelayMillis;
        this.cacheTtl = cacheTtl;
    }

    @Override
    public Optional<QuoteSnapshot> fetch(String securityId, String marketCode) {
        // 缓存**温热**时直接命中：不为一只股票再发外网请求。
        CachedBatch cached = cachedBatch.get();
        if (cached != null
                && Duration.between(cached.fetchedAt(), clock.instant()).compareTo(cacheTtl) < 0) {
            return cached.snapshots().stream()
                    .filter(snapshot -> snapshot.security().securityId().equals(securityId))
                    .findFirst();
        }
        // 冷缓存走**定向探测**（sh/sz 各一个代码，一次请求）：若这里触发整批刷新，
        // STK-04 的首次调用要等 86 个分片请求——个股页不该为"顺便暖缓存"买单。
        String code = SimulatedSecurityIds.securityCodeOf(securityId).orElse(null);
        if (code == null) {
            return Optional.empty();
        }
        return fetchSingle(code, "sh" + code + ",sz" + code);
    }

    @Override
    public List<QuoteSnapshot> fetchBatch(String marketCode) {
        return cachedBatch();
    }

    // ---------- 内部 ----------

    /**
     * 取"当前有效"的整批：缓存未过期直接返回；过期则刷新
     * （{@code synchronized} 让并发过期时只有一个调用者真正去打外网）。
     * 刷新失败时**退回过期数据**——行情数据"旧一点"远好于"没有"，
     * 与 MKT 查询侧对归档数据的降级是同一口径；连旧值都没有时才上抛。
     */
    private synchronized List<QuoteSnapshot> cachedBatch() {
        CachedBatch cached = cachedBatch.get();
        if (cached != null
                && Duration.between(cached.fetchedAt(), clock.instant()).compareTo(cacheTtl) < 0) {
            return cached.snapshots();
        }
        try {
            CachedBatch fresh = fetchWholeMarket();
            cachedBatch.set(fresh);
            return fresh.snapshots();
        } catch (RuntimeException exception) {
            if (cached != null) {
                return cached.snapshots();
            }
            throw exception;
        }
    }

    private CachedBatch fetchWholeMarket() {
        List<SecuritySummary> summaries = securityMaster.findAll("CN");
        Map<String, String> codesBySecurityId = new LinkedHashMap<>();
        for (SecuritySummary summary : summaries) {
            String code = SimulatedSecurityIds.securityCodeOf(summary.securityId()).orElse(null);
            if (code == null) {
                continue;
            }
            String prefix = exchangePrefix(summary.exchangeCode());
            if (prefix != null) {
                codesBySecurityId.put(summary.securityId(), prefix + code);
            }
        }

        Map<String, TencentQuoteParser.ParsedQuote> parsedByCode = fetchByChunks(codesBySecurityId.values());

        List<QuoteSnapshot> snapshots = new ArrayList<>();
        String sequence = "tencent-" + cycleSequence.incrementAndGet();
        for (SecuritySummary summary : summaries) {
            String requestCode = codesBySecurityId.get(summary.securityId());
            TencentQuoteParser.ParsedQuote quote = requestCode == null
                    ? null
                    : parsedByCode.get(requestCode);
            if (quote != null) {
                snapshots.add(toSnapshot(summary, quote, sequence));
            }
        }
        return new CachedBatch(List.copyOf(snapshots), clock.instant());
    }

    /** 60 只一片串行抓取；片间可配延迟，给免费源的频控留余地。 */
    private Map<String, TencentQuoteParser.ParsedQuote> fetchByChunks(Iterable<String> requestCodes) {
        Map<String, TencentQuoteParser.ParsedQuote> parsedByCode = new LinkedHashMap<>();
        List<String> current = new ArrayList<>();
        for (String requestCode : requestCodes) {
            current.add(requestCode);
            if (current.size() == CODES_PER_REQUEST) {
                fetchChunk(current, parsedByCode);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            fetchChunk(current, parsedByCode);
        }
        return parsedByCode;
    }

    private void fetchChunk(List<String> codes, Map<String, TencentQuoteParser.ParsedQuote> parsedByCode) {
        if (chunkDelayMillis > 0) {
            try {
                Thread.sleep(chunkDelayMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("分片抓取被中断", exception);
            }
        }
        String payload = fetcher.fetch(String.join(",", codes));
        for (TencentQuoteParser.ParsedQuote quote : TencentQuoteParser.parse(payload)) {
            parsedByCode.put(quote.marketPrefix() + quote.code(), quote);
        }
    }

    private Optional<QuoteSnapshot> fetchSingle(String code, String requestCodes) {
        try {
            List<TencentQuoteParser.ParsedQuote> parsed =
                    TencentQuoteParser.parse(fetcher.fetch(requestCodes));
            TencentQuoteParser.ParsedQuote quote = parsed.stream()
                    .filter(candidate -> candidate.code().equals(code))
                    .findFirst()
                    .orElse(null);
            if (quote == null) {
                return Optional.empty();
            }
            String securityId = SimulatedSecurityIds.securityIdOf(code);
            SecuritySummary summary = securityMaster.findAll("CN").stream()
                    .filter(candidate -> candidate.securityId().equals(securityId))
                    .findFirst()
                    .orElse(null);
            if (summary == null) {
                return Optional.empty();
            }
            String sequence = "tencent-" + cycleSequence.incrementAndGet();
            return Optional.of(toSnapshot(summary, quote, sequence));
        } catch (RuntimeException exception) {
            // 定向单查失败不对外炸 500：STK-04 的"查不到"语义（404）比
            // "数据源抖动"（503）更接近调用方的真实处境。
            return Optional.empty();
        }
    }

    /** 契约的对外标识 ↔ 腾讯的市场前缀。仅学习用途，非商用授权。 */
    private static String exchangePrefix(String exchangeCode) {
        return switch (exchangeCode) {
            case "SH" -> "sh";
            case "SZ" -> "sz";
            default -> null;
        };
    }

    private QuoteSnapshot toSnapshot(
            SecuritySummary security, TencentQuoteParser.ParsedQuote quote, String sequence) {
        OffsetDateTime dataTime = quote.tradedAt() == null
                ? null
                : quote.tradedAt().atZone(DATA_ZONE).toOffsetDateTime();
        OffsetDateTime now = OffsetDateTime.now(clock);
        Integer delaySeconds = dataTime == null
                ? null
                : (int) Math.max(0, Duration.between(dataTime, now).toSeconds());
        return new QuoteSnapshot(
                security,
                quote.previousClosePrice(),
                quote.openPrice(),
                quote.latestPrice(),
                quote.highPrice(),
                quote.lowPrice(),
                quote.changeAmount(),
                percentToRatio(quote.changeRatePercent()),
                handsToShares(quote.volumeHands()),
                wanToYuan(quote.amountWan()),
                percentToRatio(quote.turnoverRatePercent()),
                dataTime,
                now,
                sequence,
                MarketOverview.DataStatus.REALTIME,
                delaySeconds);
    }

    /** 百分数值 → 契约的小数比例："0.22" → "0.0022"。 */
    private static String percentToRatio(String percent) {
        return scale(percent, -2);
    }

    /** 手 → 股："528364" → "52836400"。 */
    private static String handsToShares(String hands) {
        return scale(hands, 2);
    }

    /** 万元 → 元："47596" → "475960000"。 */
    private static String wanToYuan(String wan) {
        return scale(wan, 4);
    }

    private static String scale(String value, int places) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value).movePointRight(places).toPlainString();
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private record CachedBatch(List<QuoteSnapshot> snapshots, java.time.Instant fetchedAt) {
    }
}
