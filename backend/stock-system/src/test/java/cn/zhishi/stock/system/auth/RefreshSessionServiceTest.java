package cn.zhishi.stock.system.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
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
        oldHash, familyId, user.id(), NOW.plus(Duration.ofDays(7)), RefreshTokenRecord.Status.ACTIVE));

    RefreshSessionStore store = inMemoryStore(records);
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
    var service = new RefreshSessionService(
        store,
        accounts,
        accessTokens,
        () -> "refresh-2",
        Clock.fixed(NOW, ZoneOffset.UTC),
        Duration.ofDays(7));

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
    };
  }
}
