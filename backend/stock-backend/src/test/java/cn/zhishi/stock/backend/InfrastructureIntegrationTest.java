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
import cn.zhishi.stock.news.domain.NewsArticle;
import cn.zhishi.stock.news.domain.NewsArticleStore;
import cn.zhishi.stock.news.domain.NewsContentStatus;
import cn.zhishi.stock.news.domain.NewsDedupStatus;
import cn.zhishi.stock.news.domain.NewsOriginalAccessStatus;
import cn.zhishi.stock.news.domain.NewsRecord;
import cn.zhishi.stock.news.domain.NewsRelation;
import cn.zhishi.stock.news.domain.NewsRelationMethod;
import cn.zhishi.stock.news.domain.NewsRelationStore;
import cn.zhishi.stock.news.domain.NewsRelationStatus;
import cn.zhishi.stock.news.domain.NewsSource;
import cn.zhishi.stock.news.domain.NewsSourceStore;
import cn.zhishi.stock.news.domain.NewsSourceType;
import cn.zhishi.stock.news.domain.NewsTargetType;
import cn.zhishi.stock.news.domain.NewsType;
import cn.zhishi.stock.system.auth.AuthErrorCode;
import cn.zhishi.stock.system.auth.AuthException;
import cn.zhishi.stock.system.auth.AuthenticationService;
import cn.zhishi.stock.system.auth.RefreshSessionService;
import cn.zhishi.stock.system.auth.UserAccount;
import cn.zhishi.stock.system.auth.UserAccountRepository;
import cn.zhishi.stock.system.watchlist.WatchlistGroup;
import cn.zhishi.stock.system.watchlist.WatchlistGroupRepository;
import cn.zhishi.stock.system.watchlist.WatchlistItem;
import cn.zhishi.stock.system.watchlist.WatchlistItemRepository;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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

    /**
     * {@code user_watchlist_item} 的存储契约（M3-02）。
     *
     * <p>M3-01 的分组干脆不读 {@code created_at}，所以列默认值的时区歧义从没暴露过；
     * 自选项的契约要求把它回显给前端，于是改成由应用显式写入。这一组用真实 MySQL 8.4 钉住四件
     * "看着像对但只有真库能回答"的事：
     *
     * <ul>
     *   <li>唯一索引确实按 {@code (group_id, security_id)} 生效——同组同证券撞，跨组不撞；
     *   <li>WAT-08 的删除是**硬删除**（表里没有 {@code deleted_at}），行真的消失；
     *   <li>条件写（{@code version} 写在 {@code WHERE} 里）在版本不符时一行都不改；
     *   <li>应用给的 {@code created_at} 被原样读回，而不是被 {@code CURRENT_TIMESTAMP(3)} 覆盖。
     * </ul>
     *
     * <p>每个用例各自用一个新 {@code userId}，因此彼此不干扰、也不依赖执行顺序。
     */
    @Nested
    class WatchlistItems {

        private static final AtomicLong IDS = new AtomicLong(9_400_000_000_000L);
        private static final AtomicLong USERS = new AtomicLong(9_500_000_000_000L);
        private static final LocalDateTime CREATED = LocalDateTime.of(2026, 9, 20, 14, 30);

        @Autowired WatchlistItemRepository items;
        @Autowired WatchlistGroupRepository watchlistGroups;

        @Test
        void roundTripsTheApplicationSuppliedCreatedAt() {
            long owner = USERS.incrementAndGet();
            long groupId = insertGroup(owner, "默认分组");
            // 用一个明显"不像现在"的值：如果读回来的是 2026-09-20，说明列默认值生效了，
            // 应用写入被丢掉——那 WAT-06 回显的 createdAt 就是假的。
            LocalDateTime written = LocalDateTime.of(2020, 1, 2, 3, 4, 5, 123_000_000);
            long itemId = insertItem(owner, groupId, 601L, 0, written);

            assertThat(items.find(owner, groupId, itemId))
                    .hasValueSatisfying(item -> {
                        assertThat(item.securityId()).isEqualTo(601L);
                        assertThat(item.version()).isZero();
                        assertThat(item.createdAt()).isEqualTo(written);
                    });
        }

        @Test
        void theUniqueIndexOnlyForbidsTheSameSecurityInTheSameGroup() {
            long owner = USERS.incrementAndGet();
            long groupId = insertGroup(owner, "默认分组");
            insertItem(owner, groupId, 601L, 0, CREATED);

            assertThatThrownBy(() -> insertItem(owner, groupId, 601L, 1, CREATED))
                    .describedAs("同组同证券必须被唯一索引拦住")
                    .isInstanceOf(DuplicateKeyException.class);

            long otherGroup = insertGroup(owner, "另一个分组");
            assertThat(insertItem(owner, otherGroup, 601L, 0, CREATED))
                    .describedAs("同一只证券放进另一个分组是合法的")
                    .isPositive();
        }

        @Test
        void deleteIsPhysicalAndScopedToTheOwner() {
            long owner = USERS.incrementAndGet();
            long intruder = USERS.incrementAndGet();
            long groupId = insertGroup(owner, "默认分组");
            long itemId = insertItem(owner, groupId, 601L, 0, CREATED);

            assertThat(items.delete(intruder, groupId, itemId))
                    .describedAs("user_id 写在 WHERE 里，删不到别人的行")
                    .isFalse();
            assertThat(countItems(groupId)).isEqualTo(1);

            assertThat(items.delete(owner, groupId, itemId)).isTrue();
            assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM user_watchlist_item WHERE id = ?",
                            Integer.class,
                            itemId))
                    .describedAs("WAT-08 是硬删除：行真的没了，不是打软删标记")
                    .isZero();
            assertThat(items.delete(owner, groupId, itemId)).isFalse();
        }

        @Test
        void conditionalWritesDoNothingWhenTheVersionDoesNotMatch() {
            long owner = USERS.incrementAndGet();
            long groupId = insertGroup(owner, "源分组");
            long target = insertGroup(owner, "目标分组");
            long itemId = insertItem(owner, groupId, 601L, 0, CREATED);

            assertThat(items.deleteIfVersion(owner, groupId, itemId, 7)).isFalse();
            assertThat(items.updateSortNo(owner, groupId, itemId, 5, 7)).isFalse();
            assertThat(items.moveToGroup(owner, groupId, itemId, target, 0, 7)).isFalse();

            assertThat(items.find(owner, groupId, itemId))
                    .hasValueSatisfying(item -> {
                        assertThat(item.groupId()).isEqualTo(groupId);
                        assertThat(item.sortNo()).isZero();
                        assertThat(item.version()).isZero();
                    });

            assertThat(items.updateSortNo(owner, groupId, itemId, 5, 0)).isTrue();
            assertThat(items.find(owner, groupId, itemId).orElseThrow().version()).isEqualTo(1);
        }

        @Test
        void moveToGroupRewritesTheGroupKeepsCreatedAtAndBumpsTheVersion() {
            long owner = USERS.incrementAndGet();
            long source = insertGroup(owner, "源分组");
            long target = insertGroup(owner, "目标分组");
            LocalDateTime written = LocalDateTime.of(2026, 9, 20, 14, 30, 0, 500_000_000);
            long itemId = insertItem(owner, source, 601L, 3, written);

            assertThat(items.moveToGroup(owner, source, itemId, target, 0, 0)).isTrue();

            assertThat(items.findByGroup(owner, source)).isEmpty();
            assertThat(items.find(owner, target, itemId))
                    .hasValueSatisfying(item -> {
                        assertThat(item.groupId()).isEqualTo(target);
                        assertThat(item.sortNo()).isZero();
                        assertThat(item.version()).isEqualTo(1);
                        assertThat(item.createdAt()).isEqualTo(written);
                    });
        }

        @Test
        void mergingADuplicateLeavesTheTargetRowUntouched() {
            long owner = USERS.incrementAndGet();
            long source = insertGroup(owner, "源分组");
            long target = insertGroup(owner, "目标分组");
            long duplicate = insertItem(owner, source, 601L, 0, CREATED);
            long survivor = insertItem(owner, target, 601L, 0, CREATED);
            long movable = insertItem(owner, source, 602L, 1, CREATED);

            // 602 在目标组没有同证券项 → 搬过去，序号落到目标组末尾。
            assertThat(items.moveToGroup(owner, source, movable, target, 1, 0)).isTrue();
            // 601 在目标组已经有了 → 删掉源行，目标行原样留下。
            assertThat(items.deleteIfVersion(owner, source, duplicate, 0)).isTrue();

            assertThat(countItems(source)).isZero();
            assertThat(items.findByGroup(owner, target))
                    .extracting(
                            WatchlistItem::itemId,
                            WatchlistItem::securityId,
                            WatchlistItem::sortNo,
                            WatchlistItem::version)
                    .containsExactly(
                            tuple(survivor, 601L, 0, 0),
                            tuple(movable, 602L, 1, 1));
        }

        @Test
        void nextSortNoIsZeroForAnEmptyGroupAndFollowsTheMaximum() {
            long owner = USERS.incrementAndGet();
            long groupId = insertGroup(owner, "默认分组");

            assertThat(items.nextSortNo(owner, groupId)).isZero();

            insertItem(owner, groupId, 601L, 0, CREATED);
            insertItem(owner, groupId, 602L, 1, CREATED);

            assertThat(items.nextSortNo(owner, groupId)).isEqualTo(2);
        }

        @Test
        void findByUserOrdersByGroupThenSortThenId() {
            long owner = USERS.incrementAndGet();
            long first = insertGroup(owner, "A 分组");
            long second = insertGroup(owner, "B 分组");
            insertItem(owner, second, 602L, 0, CREATED);
            insertItem(owner, first, 601L, 1, CREATED);
            insertItem(owner, first, 603L, 0, CREATED);

            assertThat(items.findByUser(owner))
                    .extracting(
                            WatchlistItem::groupId,
                            WatchlistItem::securityId,
                            WatchlistItem::sortNo)
                    .containsExactly(
                            tuple(first, 603L, 0),
                            tuple(first, 601L, 1),
                            tuple(second, 602L, 0));
        }

        @Test
        void reorderRewritesSortNumbersAndBumpsEveryVersion() {
            long owner = USERS.incrementAndGet();
            long groupId = insertGroup(owner, "默认分组");
            long a = insertItem(owner, groupId, 601L, 0, CREATED);
            long b = insertItem(owner, groupId, 602L, 1, CREATED);
            long c = insertItem(owner, groupId, 603L, 2, CREATED);

            List<Long> requested = List.of(c, a, b);
            for (int index = 0; index < requested.size(); index++) {
                assertThat(items.updateSortNo(owner, groupId, requested.get(index), index, 0))
                        .isTrue();
            }

            assertThat(items.findByGroup(owner, groupId))
                    .extracting(
                            WatchlistItem::itemId,
                            WatchlistItem::sortNo,
                            WatchlistItem::version)
                    .containsExactly(tuple(c, 0, 1), tuple(a, 1, 1), tuple(b, 2, 1));
        }

        private long insertGroup(long userId, String name) {
            long groupId = IDS.incrementAndGet();
            watchlistGroups.insert(new WatchlistGroup(groupId, userId, name, 0, false, 0, 0));
            return groupId;
        }

        private long insertItem(
                long userId, long groupId, long securityId, int sortNo, LocalDateTime createdAt) {
            long itemId = IDS.incrementAndGet();
            items.insert(new WatchlistItem(itemId, userId, groupId, securityId, sortNo, 0, createdAt));
            return itemId;
        }

        private int countItems(long groupId) {
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_watchlist_item WHERE group_id = ?",
                    Integer.class,
                    groupId);
        }
    }
    /**
     * 资讯域三张表（V4）的持久化往返。
     *
     * <p>为什么必须有真库：单测里的内存桩是**按对契约的理解手写**的，而真实列名、
     * {@code authorized_summary} 的别名、{@code ck_stock_news_canonical} 这条 CHECK 约束、
     * {@code uk_stock_news_source_content} 的唯一索引冲突行为，任何一处写错都不会让单测变红
     * （M3-03 的教训：真实数据里有没预料到的组合）。
     *
     * <p>每个用例用各自的新主键，彼此不干扰、也不依赖执行顺序。
     */
    @Nested
    class News {

        private static final AtomicLong IDS = new AtomicLong(9_400_000_000_000L);

        @Autowired NewsSourceStore newsSources;
        @Autowired NewsArticleStore newsArticles;
        @Autowired NewsRelationStore newsRelations;

        @Test
        void roundTripsASourceIncludingItsRightsWindow() {
            NewsSource source = source("SIM_IT_ROUNDTRIP");

            newsSources.ensureAll(List.of(source));

            NewsSource stored = newsSources.findByCode("SIM_IT_ROUNDTRIP").orElseThrow();
            assertThat(stored.sourceId()).isEqualTo(source.sourceId());
            assertThat(stored.sourceName()).isEqualTo("集成测试来源");
            assertThat(stored.sourceType()).isEqualTo(NewsSourceType.EXCHANGE);
            assertThat(stored.authorizationStatus())
                    .isEqualTo(NewsSource.AuthorizationStatus.AUTHORIZED);
            assertThat(stored.rightsValidFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(stored.rightsValidTo()).isEqualTo(LocalDate.of(2027, 1, 1));
            assertThat(stored.allowAiAnalysis()).isFalse();
            assertThat(stored.status()).isEqualTo(NewsSource.SourceStatus.ACTIVE);
            assertThat(stored.version()).isZero();
            assertThat(newsSources.findById(source.sourceId())).isPresent();
        }

        @Test
        void ensureAllInsertsOnlyMissingSourcesAndNeverOverwritesTheAuthorization() {
            NewsSource declared = source("SIM_IT_ENSURE");

            newsSources.ensureAll(List.of(declared));
            // 第二次登记：同一 source_code 应当被跳过，而不是改掉人工设定的授权状态
            newsSources.ensureAll(List.of(new NewsSource(
                    IDS.incrementAndGet(), "SIM_IT_ENSURE", "被改名了", NewsSourceType.MEDIA, null,
                    NewsSource.AuthorizationStatus.SUSPENDED, null, null, true,
                    NewsSource.SourceStatus.ACTIVE, null, null, 0)));

            NewsSource stored = newsSources.findByCode("SIM_IT_ENSURE").orElseThrow();
            assertThat(stored.sourceId()).isEqualTo(declared.sourceId());
            assertThat(stored.sourceName()).isEqualTo("集成测试来源");
            assertThat(stored.authorizationStatus())
                    .isEqualTo(NewsSource.AuthorizationStatus.AUTHORIZED);
        }

        /**
         * 采集健康状态写回，但 {@code DISABLED} 不能被"采集成功"复活。
         *
         * <p>{@code CASE WHEN status = 'DISABLED'} 这段 SQL 只有真库能验证：
         * 写成直接赋值不会有任何单测变红，只会让"停用某个来源"在下一次定时任务后静默失效。
         */
        @Test
        void recordsSuccessAndFailureWithoutRevivingADisabledSource() {
            NewsSource disabled = new NewsSource(
                    IDS.incrementAndGet(), "SIM_IT_DISABLED", "已停用来源", NewsSourceType.MEDIA, null,
                    NewsSource.AuthorizationStatus.AUTHORIZED, null, null, true,
                    NewsSource.SourceStatus.DISABLED, null, null, 0);
            NewsSource active = source("SIM_IT_HEALTH");
            newsSources.ensureAll(List.of(disabled, active));

            OffsetDateTime at = OffsetDateTime.now(clock).withNano(0);
            newsSources.recordSyncSuccess(List.of(disabled.sourceId(), active.sourceId()), at);

            NewsSource storedDisabled = newsSources.findById(disabled.sourceId()).orElseThrow();
            assertThat(storedDisabled.status()).isEqualTo(NewsSource.SourceStatus.DISABLED);
            assertThat(storedDisabled.lastSuccessAt()).isNotNull();

            NewsSource storedActive = newsSources.findById(active.sourceId()).orElseThrow();
            assertThat(storedActive.status()).isEqualTo(NewsSource.SourceStatus.ACTIVE);
            assertThat(storedActive.version()).isEqualTo(1);

            newsSources.recordSyncFailure(List.of(active.sourceId()), at);
            assertThat(newsSources.findById(active.sourceId()).orElseThrow().status())
                    .isEqualTo(NewsSource.SourceStatus.DEGRADED);
        }

        @Test
        void roundTripsAnArticleWithItsSourceAndConfirmedRelations() {
            NewsSource source = source("SIM_IT_ARTICLE");
            newsSources.ensureAll(List.of(source));
            NewsArticle article = article(IDS.incrementAndGet(), source.sourceId(), "content-1", null);
            newsArticles.insert(article);
            NewsRelation relation = new NewsRelation(
                    IDS.incrementAndGet(), article.newsId(), NewsTargetType.SECURITY, 600_519L,
                    NewsRelationMethod.EXPLICIT, new BigDecimal("1.00000"),
                    NewsRelationStatus.CONFIRMED, "结构化证券代码");
            newsRelations.insertAll(List.of(relation));

            NewsRecord record = newsArticles.find(article.newsId()).orElseThrow();

            // authorized_summary AS summary：别名写错时这里会读到 null
            assertThat(record.article().summary()).isEqualTo("集成测试摘要");
            assertThat(record.article().newsType()).isEqualTo(NewsType.NEWS);
            assertThat(record.article().languageCode()).isEqualTo("zh-CN");
            assertThat(record.article().originalAccessStatus())
                    .isEqualTo(NewsOriginalAccessStatus.AVAILABLE);
            assertThat(record.source().sourceCode()).isEqualTo("SIM_IT_ARTICLE");
            assertThat(record.confirmedRelations())
                    .singleElement()
                    .satisfies(stored -> {
                        assertThat(stored.targetType()).isEqualTo(NewsTargetType.SECURITY);
                        assertThat(stored.targetId()).isEqualTo(600_519L);
                        assertThat(stored.confidenceScore()).isEqualByComparingTo("1.00000");
                        assertThat(stored.reasonSummary()).isEqualTo("结构化证券代码");
                    });
        }

        /** 指纹查找只认 {@code ORIGINAL}：否则 canonical 会连成链。 */
        @Test
        void findsOnlyOriginalNewsByFingerprint() {
            NewsSource source = source("SIM_IT_FINGERPRINT");
            newsSources.ensureAll(List.of(source));
            String fingerprint = "a".repeat(64);
            long originalId = IDS.incrementAndGet();
            long duplicateId = IDS.incrementAndGet();
            newsArticles.insert(article(originalId, source.sourceId(), "fp-a", null, fingerprint));
            newsArticles.insert(article(
                    duplicateId, source.sourceId(), "fp-b", originalId, fingerprint));

            assertThat(newsArticles.findOriginalNewsIdByFingerprint(fingerprint))
                    .contains(originalId);
        }

        @Test
        void rejectsADuplicateSourceContentAndReportsItAsAlreadyStored() {
            NewsSource source = source("SIM_IT_DEDUP");
            newsSources.ensureAll(List.of(source));
            newsArticles.insert(article(IDS.incrementAndGet(), source.sourceId(), "same-content", null));

            assertThat(newsArticles.existsBySourceContent(source.sourceId(), "same-content")).isTrue();
            assertThatThrownBy(() -> newsArticles.insert(
                    article(IDS.incrementAndGet(), source.sourceId(), "same-content", null)))
                    .isInstanceOf(DuplicateKeyException.class);
        }

        /**
         * 来源缺失的稿件不出现在 {@code findAll()} 里（等价于 {@code INNER JOIN news_source}）。
         *
         * <p>没有外键约束，所以"稿件指向一个不存在的来源"在库里是允许的——
         * 这条正是靠应用层的连接语义挡住的，也只有真库能验证。
         */
        @Test
        void hidesArticlesWhoseSourceIsMissing() {
            NewsSource source = source("SIM_IT_ORPHAN");
            newsSources.ensureAll(List.of(source));
            long orphanId = IDS.incrementAndGet();
            newsArticles.insert(article(IDS.incrementAndGet(), source.sourceId(), "kept", null));
            newsArticles.insert(article(orphanId, 9_999_999_999_999L, "orphaned", null));

            assertThat(newsArticles.findAll())
                    .extracting(record -> record.article().newsId())
                    .doesNotContain(orphanId);
            // 按 id 直接取同样取不到：来源是可见性判据的一部分，不能靠列表过滤兜底
            assertThat(newsArticles.find(orphanId)).isEmpty();
        }

        private NewsSource source(String sourceCode) {
            return new NewsSource(
                    IDS.incrementAndGet(), sourceCode, "集成测试来源", NewsSourceType.EXCHANGE, null,
                    NewsSource.AuthorizationStatus.AUTHORIZED,
                    LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), false,
                    NewsSource.SourceStatus.ACTIVE, null, null, 0);
        }

        private NewsArticle article(
                long newsId, long sourceId, String sourceContentId, Long canonicalNewsId) {
            return article(newsId, sourceId, sourceContentId, canonicalNewsId, "b".repeat(64));
        }

        private NewsArticle article(
                long newsId,
                long sourceId,
                String sourceContentId,
                Long canonicalNewsId,
                String fingerprint) {
            OffsetDateTime publishedAt =
                    OffsetDateTime.now(clock).withNano(0).minusMinutes(5);
            return new NewsArticle(
                    newsId,
                    sourceId,
                    sourceContentId,
                    NewsType.NEWS,
                    "集成测试标题",
                    "集成测试摘要",
                    "记者",
                    "https://example.com/news/" + newsId,
                    "zh-CN",
                    publishedAt,
                    publishedAt.plusMinutes(1),
                    fingerprint,
                    canonicalNewsId,
                    canonicalNewsId == null ? NewsDedupStatus.ORIGINAL : NewsDedupStatus.DUPLICATE,
                    NewsContentStatus.PUBLISHED,
                    NewsOriginalAccessStatus.AVAILABLE,
                    null);
        }
    }
}
