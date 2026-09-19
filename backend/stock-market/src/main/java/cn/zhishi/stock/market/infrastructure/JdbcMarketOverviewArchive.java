package cn.zhishi.stock.market.infrastructure;

import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketOverviewArchive;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcMarketOverviewArchive implements MarketOverviewArchive {

    private static final String FIND_LATEST = """
            SELECT snapshot_json
            FROM market_overview_snapshot
            WHERE market_code = ?
            ORDER BY data_time DESC, id DESC
            LIMIT 1
            """;

    /** 与 FIND_LATEST 共用索引 idx_market_overview_latest (market_code, data_time)。 */
    private static final String FIND_AT = """
            SELECT snapshot_json
            FROM market_overview_snapshot
            WHERE market_code = ? AND data_time <= ?
            ORDER BY data_time DESC, id DESC
            LIMIT 1
            """;

    private static final String INSERT = """
            INSERT IGNORE INTO market_overview_snapshot (
              id, market_code, trade_date, data_time, data_status,
              snapshot_version, snapshot_json, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final MarketOverviewJsonCodec codec;
    private final LongSupplier idGenerator;

    public JdbcMarketOverviewArchive(
            JdbcTemplate jdbc,
            MarketOverviewJsonCodec codec,
            LongSupplier idGenerator) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.idGenerator = idGenerator;
    }

    @Override
    public Optional<MarketOverview> findLatest(String marketCode) {
        List<MarketOverview> snapshots = jdbc.query(
                FIND_LATEST,
                (resultSet, rowNumber) -> codec.decode(resultSet.getString("snapshot_json")),
                marketCode);
        return snapshots.stream().findFirst();
    }

    @Override
    public Optional<MarketOverview> findAt(String marketCode, OffsetDateTime snapshotTime) {
        List<MarketOverview> snapshots = jdbc.query(
                FIND_AT,
                (resultSet, rowNumber) -> codec.decode(resultSet.getString("snapshot_json")),
                marketCode,
                Timestamp.valueOf(snapshotTime.toLocalDateTime()));
        return snapshots.stream().findFirst();
    }

    @Override
    public void save(MarketOverview snapshot) {
        jdbc.update(
                INSERT,
                idGenerator.getAsLong(),
                snapshot.marketCode(),
                Date.valueOf(snapshot.tradeDate()),
                Timestamp.valueOf(snapshot.dataTime().toLocalDateTime()),
                snapshot.dataStatus().name(),
                snapshot.snapshotVersion(),
                codec.encode(snapshot),
                Timestamp.valueOf(snapshot.lastSuccessfulSyncAt().toLocalDateTime()));
    }
}
