package cn.zhishi.stock.system.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RefreshSessionServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-11T01:30:00Z");

  @Test
  void rotatesRefreshTokenAndRevokesFamilyWhenOldTokenIsReplayed() {
    var user = new UserAccount(1001L, "demo", "hash", UserAccount.Status.ACTIVE, "演示用户");
    var records = new HashMap<String, RefreshTokenRecord>();
    var familyId = "family-1";
    var oldHash = RefreshTokenHashing.sha256("refresh-1");
    records.put(oldHash, new RefreshTokenRecord(
        oldHash, familyId, user.id(), 0, NOW.plus(Duration.ofDays(7)),
        RefreshTokenRecord.Status.ACTIVE));

    RefreshSessionStore store = inMemoryStore(records);
    var service = service(store, user);

    var result = service.rotate("refresh-1");

    assertThat(result.refreshToken()).isEqualTo("refresh-2");
    assertThat(result.accessToken()).isEqualTo("access-2");
    assertThat(records.get(oldHash).status()).isEqualTo(RefreshTokenRecord.Status.ROTATED);

    assertThatThrownBy(() -> service.rotate("refresh-1"))
        .isInstanceOf(AuthException.class)
        .extracting(error -> ((AuthException) error).code())
        .isEqualTo(AuthErrorCode.REFRESH_TOKEN_REUSED);
    assertThat(records.values())
        .allMatch(record -> record.status() == RefreshTokenRecord.Status.REVOKED);
  }

  /** 新签发的刷新令牌必须带上当时的版本号，否则"轮换时比对"没有可比的东西。 */
  @Test
  void stampsTheUserTokenVersionOntoIssuedAndRotatedSessions() {
    var user = new UserAccount(1001L, "demo", "hash", UserAccount.Status.ACTIVE, "演示用户", 7);
    var records = new HashMap<String, RefreshTokenRecord>();
    var oldHash = RefreshTokenHashing.sha256("refresh-1");
    records.put(oldHash, new RefreshTokenRecord(
        oldHash, "family-1", user.id(), 7, NOW.plus(Duration.ofDays(7)),
        RefreshTokenRecord.Status.ACTIVE));
    var service = service(inMemoryStore(records), user);

    var started = service.start(user, Set.of("market:read"));
    assertThat(records.get(RefreshTokenHashing.sha256(started.refreshToken())).tokenVersion())
        .isEqualTo(7);

    service.rotate("refresh-1");
    assertThat(records.get(RefreshTokenHashing.sha256("refresh-2")).tokenVersion()).isEqualTo(7);
  }

  /**
   * 管理员强制下线后，刷新路径必须也走不通——不然被下线者能用刷新令牌换到一张
   * "版本已经是新的"的 access token，撤销只挡住了旧令牌、挡不住那次刷新。
   */
  @Test
  void rejectsRotationWhenTheTokenVersionIsStale() {
    var user = new UserAccount(1001L, "demo", "hash", UserAccount.Status.ACTIVE, "演示用户", 4);
    var records = new HashMap<String, RefreshTokenRecord>();
    var oldHash = RefreshTokenHashing.sha256("refresh-1");
    records.put(oldHash, new RefreshTokenRecord(
        oldHash, "family-1", user.id(), 3, NOW.plus(Duration.ofDays(7)),
        RefreshTokenRecord.Status.ACTIVE));
    var service = service(inMemoryStore(records), user);

    assertThatThrownBy(() -> service.rotate("refresh-1"))
        .isInstanceOf(AuthException.class)
        .extracting(error -> ((AuthException) error).code())
        .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);

    // 同族的其他令牌也不能留着"再来一次"的机会。
    assertThat(records.values())
        .allMatch(record -> record.status() == RefreshTokenRecord.Status.REVOKED);
  }

  private static RefreshSessionService service(RefreshSessionStore store, UserAccount user) {
    UserAccountRepository accounts = new UserAccountRepository() {
      @Override
      public Optional<UserAccount> findByUsername(String username) {
        return Optional.of(user);
      }

      @Override
      public Optional<UserAccount> findById(long userId) {
        return userId == user.id() ? Optional.of(user) : Optional.empty();
      }

      @Override
      public Set<String> findPermissions(long userId) {
        return Set.of("market:read");
      }
    };
    AccessTokenFactory accessTokens = (account, permissions) ->
        new AccessToken("access-2", "jti-2", 900);
    return new RefreshSessionService(
        store, accounts, accessTokens, () -> "refresh-2",
        Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(7));
  }

  private static RefreshSessionStore inMemoryStore(Map<String, RefreshTokenRecord> records) {
    return new RefreshSessionStore() {
      @Override
      public Optional<RefreshTokenRecord> findByTokenHash(String tokenHash) {
        return Optional.ofNullable(records.get(tokenHash));
      }

      @Override
      public void save(RefreshTokenRecord record) {
        records.put(record.tokenHash(), record);
      }

      @Override
      public RotationOutcome rotate(RefreshTokenRecord previous, RefreshTokenRecord next) {
        RefreshTokenRecord current = records.get(previous.tokenHash());
        if (current == null || current.status() == RefreshTokenRecord.Status.REVOKED) {
          return RotationOutcome.INVALID;
        }
        if (current.status() == RefreshTokenRecord.Status.ROTATED) {
          return RotationOutcome.REUSED;
        }
        if (current.tokenVersion() != next.tokenVersion()) {
          return RotationOutcome.STALE_VERSION;
        }
        records.put(previous.tokenHash(), previous.withStatus(RefreshTokenRecord.Status.ROTATED));
        records.put(next.tokenHash(), next);
        return RotationOutcome.SUCCESS;
      }

      @Override
      public void revokeFamily(String targetFamilyId) {
        records.replaceAll((hash, record) -> record.familyId().equals(targetFamilyId)
            ? record.withStatus(RefreshTokenRecord.Status.REVOKED)
            : record);
      }

      @Override
      public int revokeAllForUser(long userId) {
        Set<String> families = new HashSet<>();
        for (RefreshTokenRecord record : records.values()) {
          if (record.userId() == userId) {
            families.add(record.familyId());
          }
        }
        int revoked = 0;
        for (String familyId : families) {
          for (RefreshTokenRecord record : new ArrayList<>(records.values())) {
            if (record.familyId().equals(familyId)
                && record.status() != RefreshTokenRecord.Status.REVOKED) {
              records.put(
                  record.tokenHash(), record.withStatus(RefreshTokenRecord.Status.REVOKED));
              revoked++;
            }
          }
        }
        return revoked;
      }
    };
  }
}
