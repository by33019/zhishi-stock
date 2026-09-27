package cn.zhishi.stock.market.application;

import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.QuoteSnapshotBatchProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批量个股行情查询（契约 §8 STK-05 {@code POST /quotes/securities/batch-query}）。
 *
 * <h2>整批一个版本，而不是 N 次单查</h2>
 * 契约要求"返回统一批次版本"。若把 N 个 {@code securityId} 逐个走 STK-04，
 * 得到的是 N 个不同时刻的快照，拼不出"同一批次"；因此这里从
 * {@link QuoteSnapshotBatchProvider} 一次性取整批横截面，再按请求的标识挑出来。
 * 代价是"50 只也要拖全市场"——但自选与板块成分本来就是横截面切片，
 * 榜单链路已经证明整批取数在一次采集周期内的成本可以接受。
 *
 * <h2>查不到的进 {@code missingSecurityIds}，不报 404</h2>
 * 契约的形状（{@code missingSecurityIds}）决定了"有一只查不到"是正常答案：
 * 停牌未产生快照、代码写错、已退市——都进 missing 列表，其余照常返回。
 * 整批行情不可用（采集从未成功）才是 503。
 */
public class SecurityQuoteBatchQueryService {

    private static final String MARKET_CODE = "CN";
    private static final int MAX_BATCH_SIZE = 50;

    private final QuoteSnapshotBatchProvider batchProvider;

    public SecurityQuoteBatchQueryService(QuoteSnapshotBatchProvider batchProvider) {
        this.batchProvider = batchProvider;
    }

    /**
     * @param securityIds 对外证券标识（{@code sim-600519} 形态）；重复项按一次计算，
     *                    结果顺序与请求顺序一致
     */
    public SecurityQuoteBatchQueryResult query(List<String> securityIds) {
        if (securityIds == null || securityIds.isEmpty()) {
            throw new InvalidSecurityQueryException("securityIds 不能为空");
        }
        Set<String> requested = new LinkedHashSet<>();
        for (String securityId : securityIds) {
            if (securityId == null || securityId.isBlank()) {
                throw new InvalidSecurityQueryException("securityIds 含空白项");
            }
            requested.add(securityId.trim());
        }
        if (requested.size() > MAX_BATCH_SIZE) {
            throw new InvalidSecurityQueryException(
                    "单次最多查询 " + MAX_BATCH_SIZE + " 只证券，收到 " + requested.size() + " 只");
        }

        List<QuoteSnapshot> batch = batchProvider.fetchBatch(MARKET_CODE);
        if (batch.isEmpty()) {
            throw new MarketDataUnavailableException(MARKET_CODE);
        }
        Map<String, QuoteSnapshot> bySecurityId = new LinkedHashMap<>();
        for (QuoteSnapshot snapshot : batch) {
            bySecurityId.putIfAbsent(snapshot.security().securityId(), snapshot);
        }

        List<QuoteSnapshot> items = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String securityId : requested) {
            QuoteSnapshot snapshot = bySecurityId.get(securityId);
            if (snapshot == null) {
                missing.add(securityId);
            } else {
                items.add(snapshot);
            }
        }
        // 同一批次内 sequence 一致；全缺失时没有版本可报，null 比"假装有版本"诚实。
        String snapshotVersion = items.isEmpty() ? null : items.get(0).sequence();
        return new SecurityQuoteBatchQueryResult(List.copyOf(items), List.copyOf(missing), snapshotVersion);
    }

    /** STK-05 的响应（契约：{@code items}、{@code missingSecurityIds}、{@code snapshotVersion}）。 */
    public record SecurityQuoteBatchQueryResult(
            List<QuoteSnapshot> items,
            List<String> missingSecurityIds,
            String snapshotVersion) {
    }
}
