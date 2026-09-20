package cn.zhishi.stock.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import cn.zhishi.stock.market.application.MarketIngestionService;
import cn.zhishi.stock.market.application.MarketOverviewQueryService;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketOverviewArchive;
import cn.zhishi.stock.market.domain.MarketOverviewStore;
import cn.zhishi.stock.market.domain.QuoteProvider;
import cn.zhishi.stock.system.auth.AuthErrorCode;
import cn.zhishi.stock.system.auth.AuthException;
import cn.zhishi.stock.system.auth.AuthenticationService;
import cn.zhishi.stock.system.auth.RefreshSessionService;
import cn.zhishi.stock.system.auth.UserAccount;
import cn.zhishi.stock.system.auth.UserAccountRepository;
import cn.zhishi.stock.system.watchlist.WatchlistGroup;
import cn.zhishi.stock.system.watchlist.WatchlistGroupRepository;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "stock.auth.jwt-secret=0123456789abcdef0123456789abcdef",
        "stock.auth.cookie-secure=false",
        "spring.flyway.enabled=true"
})
@ActiveProfiles("test")
@Testcontainers
class InfrastructureIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.2-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        String migrations = Path.of("..", "..", "sql", "flyway")
                .toAbsolutePath().normalize().toString().replace('\\', '/');
        registry.add("spring.flyway.locations", () -> "filesystem:" + migrations);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired UserAccountRepository accounts;
    @Autowired RefreshSessionService sessions;
    @Autowired AuthenticationService authentication;
    @Autowired QuoteProvider provider;
    @Autowired MarketOverviewStore store;
    @Autowired MarketOverviewArchive archive;
    @Autowired MarketOverviewQueryService query;
    @Autowired Clock clock;

    @Test
    void executesAllMigrationsAndSeedsOnlyTheTestProfileAccount() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class))
                .isEqualTo(8);
        assertThat(accounts.findByUsername("demo")).isPresent();
    }

    @Test
    void fallsBackToMysqlWhenRedisSnapshotIsMissing() {
        new MarketIngestionService(provider, store, archive, clock).collect("CN");
        redis.delete("market:overview:CN");

        MarketOverview result = query.getOverview("CN");

        assertThat(result.dataStatus()).isEqualTo(MarketOverview.DataStatus.STALE);
    }

    @Test
    void revokedRefreshSessionCanNoLongerBeRotated() {
        UserAccount user = accounts.findByUsername("demo").orElseThrow();
        String refreshToken = sessions.start(user, accounts.findPermissions(user.id())).refreshToken();

        sessions.revoke(refreshToken);

        assertThatThrownBy(() -> sessions.rotate(refreshToken))
                .isInstanceOfSatisfying(AuthException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN));
    }

    @Test
    void concurrentRefreshAllowsOnlyOneSuccessAndReplayRevokesItsSuccessor() throws Exception {
        UserAccount user = accounts.findByUsername("demo").orElseThrow();
        String refreshToken = sessions.start(user, accounts.findPermissions(user.id())).refreshToken();
        int competitors = 12;
        var ready = new CountDownLatch(competitors);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(competitors);
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < competitors; index++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return sessions.rotate(refreshToken);
                    } catch (AuthException exception) {
                        return exception.code();
                    }
                }));
            }
            ready.await();
            start.countDown();
            List<Object> results = futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
            List<cn.zhishi.stock.system.auth.RefreshResult> successes = results.stream()
                    .filter(cn.zhishi.stock.system.auth.RefreshResult.class::isInstance)
                    .map(cn.zhishi.stock.system.auth.RefreshResult.class::cast)
                    .toList();

            assertThat(successes).hasSize(1);
            assertThat(results).contains(AuthErrorCode.REFRESH_TOKEN_REUSED);
            assertThatThrownBy(() -> sessions.rotate(successes.get(0).refreshToken()))
                    .isInstanceOf(AuthException.class);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentPasswordFailuresStillLockTheAccountAtFiveAttempts() throws Exception {
        UserAccount user = accounts.findByUsername("demo").orElseThrow();
        redis.delete("auth:login-attempt:" + user.id());
        int competitors = 8;
        var ready = new CountDownLatch(competitors);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(competitors);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < competitors; index++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        authentication.login("demo", "wrong-password");
                    } catch (AuthException ignored) {
                        // 每个错误请求都必须被原子计数。
                    }
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }

            assertThatThrownBy(() -> authentication.login("demo", "Stock@123"))
                    .isInstanceOfSatisfying(AuthException.class,
                            exception -> assertThat(exception.code())
                                    .isEqualTo(AuthErrorCode.ACCOUNT_LOCKED));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * V5 的两张表在此之前一直"结构就绪、无代码引用"（见 M3-01 spec §1.1）。
     *
     * <p>这一组把生成列、唯一索引、CHECK 约束与乐观锁的**实际行为**钉在真实 MySQL 8.4 上：
     * 这些都不是"看着像对"就能放过的 SQL——生成列在软删后是否真的让名字可复用、
     * 大小写不敏感的 collation 是否真的按预期冲突，只有真库能回答。
     *
     * <p>每个用例各自用一个新 {@code userId}，因此彼此不干扰、也不依赖执行顺序。
     */
    @Nested
    class WatchlistGroups {

        /** 静态：JUnit 为每个测试方法新建外层与内层实例，实例字段的计数器会从头开始。 */
        private static final AtomicLong IDS = new AtomicLong(9_200_000_000_000L);
        private static final AtomicLong USERS = new AtomicLong(9_300_000_000_000L);

        @Autowired WatchlistGroupRepository watchlistGroups;

        @Test
        void theSeededDemoAccountStartsWithExactlyOneDefaultGroup() {
            UserAccount demo = accounts.findByUsername("demo").orElseThrow();

            assertThat(watchlistGroups.findActiveByUser(demo.id()))
                    .extracting(WatchlistGroup::groupName, WatchlistGroup::isDefault)
                    .containsExactly(tuple("默认分组", true));
        }

        @Test
        void roundTripsAGroupAndCountsItsItems() {
            long owner = USERS.incrementAndGet();
            long groupId = insertGroup(owner, "默认分组", 0, true);

            assertThat(watchlistGroups.findActive(owner, groupId))
                    .hasValueSatisfying(group -> {
                        assertThat(group.groupName()).isEqualTo("默认分组");
                        assertThat(group.isDefault()).isTrue();
                        assertThat(group.version()).isZero();
                        assertThat(group.itemCount()).isZero();
                    });

            insertItem(owner, groupId, 601L);
            insertItem(owner, groupId, 602L);

            assertThat(watchlistGroups.findActive(owner, groupId).orElseThrow().itemCount())
                    .isEqualTo(2);
        }

        @Test
        void rejectsADuplicateActiveNameEvenWhenOnlyTheCasingDiffers() {
            long owner = USERS.incrementAndGet();
            insertGroup(owner, "My Group", 0, false);

            // 表是 utf8mb4_general_ci：唯一索引大小写不敏感。
            // 应用层不做任何"大小写归一"，因此两边判定天然一致。
            assertThatThrownBy(() -> insertGroup(owner, "my group", 1, false))
                    .isInstanceOf(DuplicateKeyException.class);
        }

        @Test
        void allowsReusingTheNameOfASoftDeletedGroup() {
            long owner = USERS.incrementAndGet();
            long deleted = insertGroup(owner, "我的自选", 0, false);
            assertThat(watchlistGroups.softDelete(owner, deleted, 0)).isTrue();

            long recreated = insertGroup(owner, "我的自选", 1, false);

            // 生成列 active_group_name 在软删后变 NULL，而 NULL 在唯一索引里互不冲突。
            assertThat(watchlistGroups.findActiveByUser(owner))
                    .extracting(WatchlistGroup::groupId)
                    .containsExactly(recreated);
        }

        @Test
        void rejectsASecondActiveDefaultGroupForTheSameUser() {
            long owner = USERS.incrementAndGet();
            insertGroup(owner, "默认分组", 0, true);

            assertThatThrownBy(() -> insertGroup(owner, "另一个默认", 1, true))
                    .isInstanceOf(DuplicateKeyException.class);
        }

        @Test
        void conditionalRenameOnlyAppliesForTheCurrentVersionAndTheRightOwner() {
            long owner = USERS.incrementAndGet();
            long groupId = insertGroup(owner, "我的自选", 0, false);

            assertThat(watchlistGroups.rename(owner, groupId, "核心持仓", 7)).isFalse();
            assertThat(watchlistGroups.rename(USERS.incrementAndGet(), groupId, "别人的改名", 0))
                    .isFalse();
            assertThat(watchlistGroups.findActive(owner, groupId).orElseThrow().groupName())
                    .isEqualTo("我的自选");

            assertThat(watchlistGroups.rename(owner, groupId, "核心持仓", 0)).isTrue();
            assertThat(watchlistGroups.findActive(owner, groupId).orElseThrow())
                    .satisfies(group -> {
                        assertThat(group.groupName()).isEqualTo("核心持仓");
                        assertThat(group.version()).isEqualTo(1);
                    });
        }

        @Test
        void softDeleteIsAlsoVersionGuarded() {
            long owner = USERS.incrementAndGet();
            long groupId = insertGroup(owner, "我的自选", 0, false);

            assertThat(watchlistGroups.softDelete(owner, groupId, 5)).isFalse();
            assertThat(watchlistGroups.softDelete(owner, groupId, 0)).isTrue();
            assertThat(watchlistGroups.softDelete(owner, groupId, 1)).isFalse();
            assertThat(watchlistGroups.findActive(owner, groupId)).isEmpty();
        }

        @Test
        void reorderRewritesSortNumbersAndBumpsEveryVersion() {
            long owner = USERS.incrementAndGet();
            long first = insertGroup(owner, "A", 0, true);
            long second = insertGroup(owner, "B", 1, false);
            long third = insertGroup(owner, "C", 2, false);

            watchlistGroups.reorder(owner, List.of(third, first, second));

            assertThat(watchlistGroups.findActiveByUser(owner))
                    .extracting(
                            WatchlistGroup::groupId,
                            WatchlistGroup::sortNo,
                            WatchlistGroup::version)
                    .containsExactly(
                            tuple(third, 0, 1),
                            tuple(first, 1, 1),
                            tuple(second, 2, 1));
        }

        @Test
        void movingItemsMergesTheOnesAlreadyPresentInTheTarget() {
            long owner = USERS.incrementAndGet();
            long source = insertGroup(owner, "源分组", 0, false);
            long target = insertGroup(owner, "目标分组", 1, false);
            insertItem(owner, source, 601L);
            insertItem(owner, source, 602L);
            insertItem(owner, source, 603L);
            insertItem(owner, target, 602L);

            int moved = watchlistGroups.moveItems(owner, source, target);

            assertThat(moved).describedAs("被目标组合并掉的重复项不算搬移").isEqualTo(2);
            assertThat(countItems(target)).isEqualTo(3);
            assertThat(countItems(source)).isZero();
        }

        @Test
        void theDatabaseItselfRejectsNamesTheApplicationWouldAlsoReject() {
            long owner = USERS.incrementAndGet();

            // 应用层用 trim() + codePointCount 判断，数据库用 CHAR_LENGTH(TRIM())；
            // 这里绕过应用层直接写库，证明两边判定一致。
            assertThatThrownBy(() -> insertGroup(owner, "a".repeat(21), 0, false))
                    .describedAs("21 个字符应当被 CHECK 约束拒绝")
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> insertGroup(owner, "   ", 0, false))
                    .describedAs("全空白应当被 CHECK 约束拒绝")
                    .isInstanceOf(DataAccessException.class);
        }

        private long insertGroup(long userId, String name, int sortNo, boolean isDefault) {
            long groupId = IDS.incrementAndGet();
            watchlistGroups.insert(
                    new WatchlistGroup(groupId, userId, name, sortNo, isDefault, 0, 0));
            return groupId;
        }

        private void insertItem(long userId, long groupId, long securityId) {
            jdbc.update("""
                    INSERT INTO user_watchlist_item (id, user_id, group_id, security_id, sort_no)
                    VALUES (?, ?, ?, ?, 0)
                    """, IDS.incrementAndGet(), userId, groupId, securityId);
        }

        private int countItems(long groupId) {
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_watchlist_item WHERE group_id = ?",
                    Integer.class,
                    groupId);
        }
    }
}
