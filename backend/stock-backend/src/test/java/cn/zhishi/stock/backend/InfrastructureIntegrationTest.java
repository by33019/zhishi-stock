package cn.zhishi.stock.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import cn.zhishi.stock.ai.domain.AiContextSnapshot;
import cn.zhishi.stock.ai.domain.AiContextSnapshotStore;
import cn.zhishi.stock.ai.domain.AiContextTarget;
import cn.zhishi.stock.ai.domain.AiContextType;
import cn.zhishi.stock.ai.domain.AiMessage;
import cn.zhishi.stock.ai.domain.AiMessageRole;
import cn.zhishi.stock.ai.domain.AiMessageStore;
import cn.zhishi.stock.ai.domain.AiReport;
import cn.zhishi.stock.ai.domain.AiReportQuality;
import cn.zhishi.stock.ai.domain.AiReportStore;
import cn.zhishi.stock.ai.domain.AiScene;
import cn.zhishi.stock.ai.domain.AiSession;
import cn.zhishi.stock.ai.domain.AiSessionStore;
import cn.zhishi.stock.ai.domain.AiTargetRole;
import cn.zhishi.stock.ai.domain.AiTargetType;
import cn.zhishi.stock.ai.domain.AiTask;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.ai.domain.AiTaskStore;
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
import java.util.Map;
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

    /**
     * AI 域 {@code ai_task} / {@code ai_task_target}（V6）的持久化往返。
     *
     * <p>为什么必须有真库：本轮 AI 的单元测试全部跑在**手写内存桩**上，而内存桩是按
     * 对契约的理解写的。真实列名与别名、{@code ck_ai_task_attempts} 这条 CHECK 约束、
     * {@code uk_ai_task_request_id} 的唯一索引冲突行为、以及
     * {@code UPDATE ... WHERE status IN (...) AND attempt_no < max_attempts} 里
     * 那几个条件的实际效果——任何一处写错都不会让单测变红。
     *
     * <p>M3-07 的 Worker 整个建在这两张表上，所以这一层必须先被真库验证过。
     */
    @Nested
    class AiTaskPersistence {

        private static final AtomicLong IDS = new AtomicLong(9_600_000_000_000L);
        private static final AtomicLong USERS = new AtomicLong(9_700_000_000_000L);

        @Autowired AiTaskStore aiTasks;

        @Test
        void roundTripsATaskWithItsTargetsInOrder() {
            long owner = USERS.incrementAndGet();
            AiTask created = task(owner, AiTaskStatus.CREATED, 0, now(), false);

            aiTasks.insert(created);
            AiTask stored = aiTasks.find(created.taskId()).orElseThrow();

            assertThat(stored.requestId()).isEqualTo(created.requestId());
            assertThat(stored.status()).isEqualTo(AiTaskStatus.CREATED);
            assertThat(stored.attemptNo()).isZero();
            assertThat(stored.maxAttempts()).isEqualTo(2);
            assertThat(stored.cancelRequested()).isFalse();
            assertThat(stored.version()).isZero();
            assertThat(stored.createdAt()).isEqualTo(created.createdAt());
            assertThat(stored.deadlineAt()).isEqualTo(created.deadlineAt());

            // 目标按 sort_no 回来，且代理键被原样还原（对外标识由用例层补，存储层不拼）
            assertThat(stored.targets())
                    .extracting(
                            AiContextTarget::targetRole,
                            AiContextTarget::targetCode,
                            AiContextTarget::storageId)
                    .containsExactly(
                            tuple(AiTargetRole.PRIMARY, "600519", 600_519L),
                            tuple(AiTargetRole.COMPARISON, "600520", 600_520L));
            assertThat(stored.targets().get(0).targetId())
                    .describedAs("对外标识不在库里：表只存代理键与代码快照")
                    .isNull();
        }

        /**
         * 唯一索引让「同一个幂等键创建两次」在**数据库层**不可能成功。
         *
         * <p>用例层据此把冲突翻译成"返回已有任务"（契约 §3.7 要的是不重复消耗额度，
         * 不是必须失败）。没有真库，这条翻译规则无从验证。
         */
        @Test
        void theUniqueRequestIdMakesCreationIdempotentAtTheDatabaseLevel() {
            long owner = USERS.incrementAndGet();
            String requestId = java.util.UUID.randomUUID().toString();
            aiTasks.insert(task(owner, requestId, AiTaskStatus.CREATED, 0, now(), false));

            assertThatThrownBy(() -> aiTasks.insert(
                    task(owner, requestId, AiTaskStatus.CREATED, 0, now(), false)))
                    .isInstanceOf(DuplicateKeyException.class);

            assertThat(aiTasks.findByRequestId(requestId)).isPresent();
            // 探测串必须是 ASCII：request_id 是 char(36) ascii_bin，传非 ASCII 会让
            // MySQL 报 collation 冲突而不是返回空。生产路径上这个参数永远是由
            // (userId, Idempotency-Key) 派生出的 UUID，所以那不是一个可达输入。
            assertThat(aiTasks.findByRequestId("no-such-request-id")).isEmpty();
        }

        /**
         * {@code save} 是乐观锁：拿着过期的 {@code version} 写回去必须影响 0 行。
         *
         * <p>这是「至少一次消费」不退化成「至少一次计费」的**唯一**防线——
         * 两个执行者同时看到同一个任务时，只有写成功的那个该继续调模型。
         */
        @Test
        void saveIsVersionGuarded() {
            long owner = USERS.incrementAndGet();
            AiTask created = task(owner, AiTaskStatus.CREATED, 0, now(), false);
            aiTasks.insert(created);

            assertThat(aiTasks.claimForExecution(created.taskId(), now())).isTrue();

            // 手里那份还是 version = 0，而库里已经被抢占推进到 1。
            // 注意这里**不能**先 advance 再 save：advance 会把手里这份也变成 1，
            // 于是 WHERE version = 1 恰好命中，测试就变成"假红"了
            // （第一版就是这么写的，断言 false 却拿到 true）。
            assertThat(aiTasks.save(created)).isEmpty();

            AiTask claimed = aiTasks.find(created.taskId()).orElseThrow();
            assertThat(claimed.status()).isEqualTo(AiTaskStatus.PREPARING);
            assertThat(claimed.attemptNo()).isEqualTo(1);
            assertThat(claimed.version()).isEqualTo(1);

            // 写入成功时返回的是**推进后**的那一份（数据库做了 version + 1）。
            // 若存储层只回一个 true，调用方就会拿着旧版本继续写，而第二次会安静地"冲突"。
            AiTask advanced = aiTasks
                    .save(claimed.advance(AiTaskStatus.RUNNING, now()))
                    .orElseThrow();
            assertThat(advanced.version()).isEqualTo(2);
            assertThat(aiTasks.find(created.taskId()).orElseThrow().version()).isEqualTo(2);
        }

        /**
         * 抢占执行权的四个条件必须同时生效。
         *
         * <p>最后一条（次数用尽）是本用例的重点：{@code attempt_no < max_attempts}
         * 若从 SQL 里漏掉，这一句会去写 {@code attempt_no = 3}，
         * 直接撞上 {@code ck_ai_task_attempts} 抛 SQL 异常——那不是"任务失败"，
         * 而是**整个恢复扫描批次崩掉**，同批次里其他任务一起陪葬。
         * 断言返回 {@code false}（而不是抛异常）才证明守卫真的在。
         */
        @Test
        void claimingIsAtomicAndRefusesCanceledOrExhaustedTasks() {
            long owner = USERS.incrementAndGet();

            AiTask claimable = task(owner, AiTaskStatus.QUEUED, 0, now(), false);
            aiTasks.insert(claimable);
            assertThat(aiTasks.claimForExecution(claimable.taskId(), now())).isTrue();
            assertThat(aiTasks.claimForExecution(claimable.taskId(), now()))
                    .describedAs("已经不是 CREATED/QUEUED 了，第二次抢不到")
                    .isFalse();

            AiTask canceled = task(owner, AiTaskStatus.QUEUED, 0, now(), true);
            aiTasks.insert(canceled);
            assertThat(aiTasks.claimForExecution(canceled.taskId(), now()))
                    .describedAs("已置取消意图的任务不该再被取走执行（那会白烧一次模型调用）")
                    .isFalse();

            AiTask exhausted = task(owner, AiTaskStatus.QUEUED, 2, now(), false);
            aiTasks.insert(exhausted);
            assertThat(aiTasks.claimForExecution(exhausted.taskId(), now()))
                    .describedAs("次数已用尽：必须被条件挡下，而不是撞 CHECK 约束")
                    .isFalse();
            assertThat(aiTasks.find(exhausted.taskId()).orElseThrow().attemptNo())
                    .describedAs("被拒的抢占不得留下任何副作用")
                    .isEqualTo(2);
        }

        @Test
        void requestCancelIsIdempotentAndLeavesTheStatusAlone() {
            long owner = USERS.incrementAndGet();
            AiTask created = task(owner, AiTaskStatus.RUNNING, 1, now(), false);
            aiTasks.insert(created);

            assertThat(aiTasks.requestCancel(created.taskId())).isTrue();
            assertThat(aiTasks.requestCancel(created.taskId()))
                    .describedAs("第二次置意图影响 0 行，不会重复推进 version")
                    .isFalse();

            AiTask stored = aiTasks.find(created.taskId()).orElseThrow();
            assertThat(stored.cancelRequested()).isTrue();
            assertThat(stored.status())
                    .describedAs("取消是意图，状态是事实：两者分开存")
                    .isEqualTo(AiTaskStatus.RUNNING);
        }

        /**
         * 恢复扫描的候选集：三类合一。
         *
         * <p>用 {@code contains} 而不是 {@code containsExactly}：这一层与同类的其他用例
         * 共用一张库，断言精确集合会让用例之间产生执行顺序依赖。
         */
        @Test
        void findRecoverablePicksLostQueueMessagesAndDeadExecutors() {
            long owner = USERS.incrementAndGet();
            OffsetDateTime fresh = now();
            OffsetDateTime stale = fresh.minusMinutes(10);

            AiTask lostMessage = task(owner, AiTaskStatus.QUEUED, 0, stale, false);
            AiTask freshQueue = task(owner, AiTaskStatus.QUEUED, 0, fresh, false);
            AiTask canceledQueue = task(owner, AiTaskStatus.QUEUED, 0, fresh, true);
            AiTask deadExecutor = task(owner, AiTaskStatus.RUNNING, 1, stale, false);
            AiTask finished = task(owner, AiTaskStatus.COMPLETED, 1, stale, false);
            for (AiTask each : List.of(
                    lostMessage, freshQueue, canceledQueue, deadExecutor, finished)) {
                aiTasks.insert(each);
            }

            List<Long> found = aiTasks
                    .findRecoverable(fresh.minusMinutes(5), fresh.minusMinutes(5), 200)
                    .stream()
                    .map(AiTask::taskId)
                    .toList();

            assertThat(found).contains(lostMessage.taskId(), deadExecutor.taskId());
            assertThat(found)
                    .describedAs("刚投出去的消息不算丢；已置取消意图的靠 cancel_requested 分支进来")
                    .doesNotContain(freshQueue.taskId(), finished.taskId());
            assertThat(found)
                    .describedAs("QUEUED 且已置取消意图：即使 created_at 很新也必须被扫到")
                    .contains(canceledQueue.taskId());
        }

        @Test
        void countsActiveTasksAndTodaysCreations() {
            long owner = USERS.incrementAndGet();
            OffsetDateTime fresh = now();
            for (AiTaskStatus status : List.of(
                    AiTaskStatus.CREATED, AiTaskStatus.QUEUED, AiTaskStatus.COMPLETED)) {
                aiTasks.insert(task(owner, status, 0, fresh, false));
            }

            assertThat(aiTasks.countByUserAndStatuses(owner, AiTaskStatus.activeStatuses()))
                    .describedAs("并发额度只算非终态")
                    .isEqualTo(2);
            assertThat(aiTasks.countByUserAndStatuses(owner, AiTaskStatus.terminalStatuses()))
                    .isEqualTo(1);
            assertThat(aiTasks.countByUserAndStatuses(owner, List.of())).isZero();

            assertThat(aiTasks.countCreatedSince(owner, fresh.minusMinutes(1))).isEqualTo(3);
            assertThat(aiTasks.countCreatedSince(owner, fresh.plusMinutes(1))).isZero();
            assertThat(aiTasks.countCreatedSince(USERS.incrementAndGet(), fresh.minusDays(1)))
                    .describedAs("额度按用户隔离")
                    .isZero();
        }

        @Test
        void listsOnlyTheOwnersTasksNewestFirst() {
            long owner = USERS.incrementAndGet();
            long intruder = USERS.incrementAndGet();
            OffsetDateTime base = now();
            AiTask older = task(owner, AiTaskStatus.CREATED, 0, base.minusMinutes(2), false);
            AiTask newer = task(owner, AiTaskStatus.QUEUED, 0, base, false);
            aiTasks.insert(older);
            aiTasks.insert(newer);
            aiTasks.insert(task(intruder, AiTaskStatus.CREATED, 0, base, false));

            assertThat(aiTasks.findByUserAndStatuses(
                            owner, List.of(AiTaskStatus.CREATED, AiTaskStatus.QUEUED)))
                    .extracting(AiTask::taskId)
                    .containsExactly(newer.taskId(), older.taskId());
        }

        // ---------- 夹具 ----------

        private OffsetDateTime now() {
            return OffsetDateTime.now(clock).withNano(0);
        }

        private AiTask task(
                long userId, AiTaskStatus status, int attemptNo, OffsetDateTime createdAt,
                boolean cancelRequested) {
            return task(
                    userId,
                    java.util.UUID.randomUUID().toString(),
                    status,
                    attemptNo,
                    createdAt,
                    cancelRequested);
        }

        private AiTask task(
                long userId,
                String requestId,
                AiTaskStatus status,
                int attemptNo,
                OffsetDateTime createdAt,
                boolean cancelRequested) {
            return new AiTask(
                    IDS.incrementAndGet(),
                    requestId,
                    9_650_000_000_001L,
                    userId,
                    null,
                    AiScene.STOCK,
                    "集成测试问题",
                    createdAt.minusDays(1),
                    createdAt,
                    status,
                    cancelRequested,
                    attemptNo,
                    2,
                    "SIMULATED",
                    "sim-analyst-v1",
                    "trace-it",
                    createdAt,
                    status == AiTaskStatus.CREATED ? null : createdAt,
                    null,
                    null,
                    null,
                    createdAt,
                    createdAt.plusMinutes(30),
                    null,
                    null,
                    null,
                    null,
                    0,
                    List.of(
                            new AiContextTarget(
                                    AiTargetType.SECURITY, "sim-600519", "600519", "集成测试证券",
                                    AiTargetRole.PRIMARY, 600_519L),
                            new AiContextTarget(
                                    AiTargetType.SECURITY, "sim-600520", "600520", "对比证券",
                                    AiTargetRole.COMPARISON, 600_520L)));
        }
    }

    /**
     * AI 域 {@code ai_session} / {@code ai_message} / {@code ai_report} /
     * {@code ai_context_snapshot}（V6）的持久化往返。
     *
     * <p>这里的重点同样是"只有真库能证伪"的部分：消息序号的现算口径、
     * {@code uk_ai_report_task}（一项任务最多一个报告）、
     * {@code ck_ai_report_limit_state}（受限报告必须带原因），
     * 以及 {@code context_data} 这个 JSON 列**到底被解析成了对象还是存成了字符串**——
     * 后者在前端读出来是一个带引号的 JSON 字符串，而两边各自看都没错。
     */
    @Nested
    class AiReportPersistence {

        private static final AtomicLong IDS = new AtomicLong(9_800_000_000_000L);

        @Autowired AiSessionStore aiSessions;
        @Autowired AiMessageStore aiMessages;
        @Autowired AiReportStore aiReports;
        @Autowired AiContextSnapshotStore aiSnapshots;

        @Test
        void roundTripsASessionAndItsActivityTouch() {
            long sessionId = IDS.incrementAndGet();
            OffsetDateTime startedAt = OffsetDateTime.now(clock).withNano(0).minusHours(1);
            aiSessions.insert(new AiSession(
                    sessionId, 9_850_000_000_001L, AiScene.STOCK, "集成测试会话", "ACTIVE",
                    false, null, startedAt, 0, startedAt));

            AiSession stored = aiSessions.find(sessionId).orElseThrow();
            assertThat(stored.scene()).isEqualTo(AiScene.STOCK);
            assertThat(stored.title()).isEqualTo("集成测试会话");
            assertThat(stored.active()).isTrue();
            assertThat(stored.favorite()).isFalse();
            assertThat(stored.lastTaskId()).isNull();
            assertThat(stored.version()).isZero();
            assertThat(stored.createdAt()).isEqualTo(startedAt);

            OffsetDateTime touchedAt = startedAt.plusMinutes(30);
            aiSessions.touch(sessionId, 9_860_000_000_002L, touchedAt);

            AiSession touched = aiSessions.find(sessionId).orElseThrow();
            assertThat(touched.lastTaskId()).isEqualTo(9_860_000_000_002L);
            assertThat(touched.lastActivityAt()).isEqualTo(touchedAt);
            assertThat(touched.version()).isEqualTo(1);
            assertThat(touched.createdAt())
                    .describedAs("活动时间推进不该动创建时间")
                    .isEqualTo(startedAt);
        }

        /**
         * 会话内序号现算：空会话从 1 开始，之后跟着最大值走。
         *
         * <p>若某处改成"维护一个计数器列"，这里仍然会过——所以真正钉住口径的是
         * {@link #rejectsASecondMessageWithTheSameSequence()}。
         */
        @Test
        void messagesGetIncreasingSequenceNumbersWithinASession() {
            long sessionId = IDS.incrementAndGet();
            assertThat(aiMessages.nextSequenceNo(sessionId)).isEqualTo(1);

            aiMessages.insert(message(IDS.incrementAndGet(), sessionId, null, 1, "用户提问"));
            aiMessages.insert(message(IDS.incrementAndGet(), sessionId, 7L, 2, "助手回答"));

            assertThat(aiMessages.nextSequenceNo(sessionId)).isEqualTo(3);
            assertThat(aiMessages.countBySession(sessionId)).isEqualTo(2);
            assertThat(aiMessages.countBySession(IDS.incrementAndGet())).isZero();
        }

        @Test
        void rejectsASecondMessageWithTheSameSequence() {
            long sessionId = IDS.incrementAndGet();
            aiMessages.insert(message(IDS.incrementAndGet(), sessionId, null, 1, "第一条"));

            assertThatThrownBy(() -> aiMessages.insert(
                    message(IDS.incrementAndGet(), sessionId, null, 1, "撞号")))
                    .describedAs("并发撞号必须报错，而不是静默产生乱序")
                    .isInstanceOf(DuplicateKeyException.class);
        }

        @Test
        void roundTripsAReportWithItsSixSectionsAndCutoffs() {
            long sessionId = IDS.incrementAndGet();
            long taskId = IDS.incrementAndGet();
            long messageId = IDS.incrementAndGet();
            AiReport created = report(IDS.incrementAndGet(), taskId, sessionId, messageId);

            aiReports.insert(created);

            AiReport stored = aiReports.findByTask(taskId).orElseThrow();
            assertThat(stored.reportId()).isEqualTo(created.reportId());
            assertThat(stored.assistantMessageId()).isEqualTo(messageId);
            assertThat(stored.coreConclusion()).isEqualTo("核心结论：量价齐升");
            assertThat(stored.quoteEvidence()).isEqualTo("行情依据：换手率抬升");
            assertThat(stored.comparisonAnalysis()).isEqualTo("对比分析：强于板块");
            assertThat(stored.eventClues()).isEqualTo("事件线索：无重大公告");
            assertThat(stored.riskAndUncertainty()).isEqualTo("风险：样本区间偏短");
            assertThat(stored.disclaimer()).isEqualTo("本内容仅供参考，不构成投资建议。");
            assertThat(stored.renderedMarkdown()).contains("## 核心结论");
            assertThat(stored.quality()).isEqualTo(AiReportQuality.VALID);
            assertThat(stored.limited()).isFalse();
            assertThat(stored.limitedReason()).isNull();
            assertThat(stored.contentSchemaVersion()).isEqualTo("ai-report-v1");
            assertThat(stored.promptVersion()).isEqualTo("sim-analyst-v1");
            assertThat(stored.marketDataCutoffAt()).isEqualTo(created.marketDataCutoffAt());
            assertThat(stored.newsDataCutoffAt()).isEqualTo(created.newsDataCutoffAt());
            assertThat(stored.contentHash()).isEqualTo("c".repeat(64));
            assertThat(stored.generatedAt()).isEqualTo(created.generatedAt());
            assertThat(aiReports.find(created.reportId())).isPresent();
        }

        @Test
        void roundTripsALimitedReportWithItsReason() {
            long sessionId = IDS.incrementAndGet();
            long taskId = IDS.incrementAndGet();
            OffsetDateTime cutoff = OffsetDateTime.now(clock).withNano(0);
            aiReports.insert(new AiReport(
                    IDS.incrementAndGet(), taskId, sessionId, null,
                    "核心结论：数据受限", "行情依据：仅有收盘快照", null, null,
                    "风险：缺少分时数据", "本内容仅供参考，不构成投资建议。",
                    "## 核心结论", AiReportQuality.LIMITED, "分时行情快照缺失",
                    "ai-report-v1", "sim-analyst-v1", "SIMULATED", "sim-analyst-v1",
                    cutoff, null, "d".repeat(64), cutoff));

            AiReport stored = aiReports.findByTask(taskId).orElseThrow();
            assertThat(stored.limited()).isTrue();
            assertThat(stored.qualityStatus()).isEqualTo("LIMITED");
            assertThat(stored.limitedReason()).isEqualTo("分时行情快照缺失");
            assertThat(stored.comparisonAnalysis()).isNull();
            assertThat(stored.newsDataCutoffAt()).isNull();
        }

        @Test
        void rejectsASecondReportForTheSameTask() {
            long sessionId = IDS.incrementAndGet();
            long taskId = IDS.incrementAndGet();
            aiReports.insert(report(IDS.incrementAndGet(), taskId, sessionId, null));

            assertThatThrownBy(() -> aiReports.insert(
                    report(IDS.incrementAndGet(), taskId, sessionId, null)))
                    .describedAs("uk_ai_report_task：一项任务最多一个最终报告")
                    .isInstanceOf(DuplicateKeyException.class);
        }

        /**
         * 受限状态的一致性由**数据库**兜底，不只靠领域校验。
         *
         * <p>绕过领域对象直接写库（模拟将来某个批量脚本或人工订正），
         * 库必须拒绝 {@code is_limited = 1} 却没有原因的行。
         */
        @Test
        void theDatabaseItselfRejectsALimitedReportWithoutAReason() {
            OffsetDateTime cutoff = OffsetDateTime.now(clock).withNano(0);
            long reportId = IDS.incrementAndGet();

            assertThatThrownBy(() -> jdbc.update(
                    """
                    INSERT INTO ai_report
                      (id, task_id, session_id, core_conclusion, quote_evidence,
                       risk_and_uncertainty, disclaimer, rendered_markdown, is_limited,
                       limited_reason, quality_status, content_schema_version, prompt_version,
                       provider_code, model_code, market_data_cutoff_at, content_hash, generated_at)
                    VALUES
                      (?, ?, ?, '结论', '依据', '风险', '免责', '渲染', 1,
                       NULL, 'LIMITED', 'ai-report-v1', 'sim-analyst-v1',
                       'SIMULATED', 'sim-analyst-v1', ?, ?, ?)
                    """,
                    reportId,
                    IDS.incrementAndGet(),
                    IDS.incrementAndGet(),
                    cutoff,
                    "e".repeat(64),
                    cutoff))
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        void contextSnapshotsAreStoredAsRealJsonObjects() {
            long taskId = IDS.incrementAndGet();
            OffsetDateTime cutoff = OffsetDateTime.now(clock).withNano(0);
            aiSnapshots.insertAll(taskId, List.of(
                    snapshot(1, AiContextType.QUOTE, "sim-600519", 600_519L, cutoff),
                    snapshot(2, AiContextType.NEWS, "news-1001", null, cutoff.minusMinutes(1))));

            assertThat(aiSnapshots.countByTask(taskId)).isEqualTo(2);

            // 关键断言：MySQL 必须把它当成对象存进去。
            // 若写成把 JSON 字符串再引号一次，这里会返回 STRING 而不是 OBJECT，
            // 而两边各自看都没错——直到前端读出一个带引号的 JSON 字符串。
            assertThat(jdbc.queryForObject(
                            "SELECT JSON_TYPE(context_data) FROM ai_context_snapshot"
                                    + " WHERE task_id = ? AND snapshot_no = 1",
                            String.class,
                            taskId))
                    .isEqualTo("OBJECT");
            assertThat(jdbc.queryForObject(
                            "SELECT JSON_UNQUOTE(JSON_EXTRACT(context_data, '$.label'))"
                                    + " FROM ai_context_snapshot WHERE task_id = ? AND snapshot_no = 1",
                            String.class,
                            taskId))
                    .isEqualTo("集成测试上下文");

            assertThatThrownBy(() -> aiSnapshots.insertAll(
                    taskId, List.of(snapshot(1, AiContextType.KLINE, "dup", null, cutoff))))
                    .describedAs("uk_ai_context_task_no：任务内序号必须唯一")
                    .isInstanceOf(DuplicateKeyException.class);
        }

        // ---------- 夹具 ----------

        private AiMessage message(
                long messageId, long sessionId, Long taskId, int sequenceNo, String content) {
            OffsetDateTime at = OffsetDateTime.now(clock).withNano(0);
            return new AiMessage(
                    messageId, sessionId, taskId, AiMessageRole.ASSISTANT, sequenceNo, content, at, at);
        }

        private AiContextSnapshot snapshot(
                int snapshotNo,
                AiContextType type,
                String sourceKey,
                Long sourceObjectId,
                OffsetDateTime cutoff) {
            return new AiContextSnapshot(
                    snapshotNo,
                    type,
                    "IT_SOURCE",
                    sourceObjectId,
                    sourceKey,
                    cutoff,
                    cutoff,
                    "f".repeat(64),
                    Map.of("label", "集成测试上下文", "value", 12.34),
                    true);
        }

        private AiReport report(long reportId, long taskId, long sessionId, Long messageId) {
            OffsetDateTime cutoff = OffsetDateTime.now(clock).withNano(0);
            return new AiReport(
                    reportId,
                    taskId,
                    sessionId,
                    messageId,
                    "核心结论：量价齐升",
                    "行情依据：换手率抬升",
                    "对比分析：强于板块",
                    "事件线索：无重大公告",
                    "风险：样本区间偏短",
                    "本内容仅供参考，不构成投资建议。",
                    "## 核心结论\n量价齐升",
                    AiReportQuality.VALID,
                    null,
                    "ai-report-v1",
                    "sim-analyst-v1",
                    "SIMULATED",
                    "sim-analyst-v1",
                    cutoff,
                    cutoff.minusMinutes(1),
                    "c".repeat(64),
                    cutoff);
        }
    }
}
