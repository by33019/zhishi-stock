package cn.zhishi.stock.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhishi.stock.market.application.MarketIngestionService;
import cn.zhishi.stock.market.application.MarketOverviewQueryService;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketOverviewArchive;
import cn.zhishi.stock.market.domain.MarketOverviewStore;
import cn.zhishi.stock.market.domain.QuoteProvider;
import cn.zhishi.stock.system.auth.AuthErrorCode;
import cn.zhishi.stock.system.auth.AuthException;
import cn.zhishi.stock.system.auth.RefreshSessionService;
import cn.zhishi.stock.system.auth.UserAccount;
import cn.zhishi.stock.system.auth.UserAccountRepository;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
@Testcontainers(disabledWithoutDocker = true)
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
}
