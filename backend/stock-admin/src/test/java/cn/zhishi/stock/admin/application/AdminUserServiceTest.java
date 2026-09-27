package cn.zhishi.stock.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import cn.zhishi.stock.admin.domain.AdminRoleStore;
import cn.zhishi.stock.admin.domain.AdminUserDetail;
import cn.zhishi.stock.admin.domain.AdminUserPage;
import cn.zhishi.stock.admin.domain.AdminUserPatch;
import cn.zhishi.stock.admin.domain.AdminUserQuery;
import cn.zhishi.stock.admin.domain.AdminUserRole;
import cn.zhishi.stock.admin.domain.AdminUserStatus;
import cn.zhishi.stock.admin.domain.AdminUserStore;
import cn.zhishi.stock.admin.domain.AdminUserSummary;
import cn.zhishi.stock.admin.domain.NewAdminUser;
import cn.zhishi.stock.admin.domain.RolePage;
import cn.zhishi.stock.admin.domain.RoleQuery;
import cn.zhishi.stock.admin.domain.PasswordResetCredentialStore;
import cn.zhishi.stock.system.auth.RefreshSessionStore;
import cn.zhishi.stock.system.watchlist.WatchlistGroupService;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 后台用户管理用例（契约 §16.1 ADM-USR-01~09）。
 *
 * <h2>要钉住的三件事</h2>
 * <ol>
 *   <li><b>撤权三件套</b>：锁定 / 换角色 / 强制下线 / 删除都必须递增 {@code tokenVersion}
 *       并撤销刷新会话；解锁不递增。少一样就是一个 15 分钟的越权窗口，而且不会报错。</li>
 *   <li><b>版本链</b>：每次写库推进一格，返回的新 {@code version} 必须等于
 *       "读到的版本 + 本次写入次数"。算错会让前端拿着一个过期的版本号去做下一次 If-Match，
 *       于是"改一次要刷新两次"。</li>
 *   <li><b>超管保护</b>的三个分支与"不许删自己"。</li>
 * </ol>
 */
class AdminUserServiceTest {

  private static final long TARGET_ID = 1001L;
  private static final long OPERATOR_ID = 9001L;
  private static final long SUPER_ROLE_ID = 100L;
  private static final long USER_ROLE_ID = 101L;
  private static final Instant NOW = Instant.parse("2026-09-23T02:00:00Z");

  private final FakeUserStore store = new FakeUserStore();
  private final FakeRoleStore roleStore = new FakeRoleStore();
  private final FakeSessionStore sessions = new FakeSessionStore();
  private final RecordingCredentialStore credentials = new RecordingCredentialStore();
  private final WatchlistGroupService watchlist = mock(WatchlistGroupService.class);
  private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

  @BeforeEach
  void seedTarget() {
    store.put(TARGET_ID, "analyst", "analyst@example.com", "13800001111",
        AdminUserStatus.ACTIVE, Set.of(USER_ROLE_ID));
    store.put(OPERATOR_ID, "admin", "admin@example.com", null,
        AdminUserStatus.ACTIVE, Set.of(SUPER_ROLE_ID));
    roleStore.validRoleIds.add(SUPER_ROLE_ID);
    roleStore.validRoleIds.add(USER_ROLE_ID);
    roleStore.superRoleId = SUPER_ROLE_ID;
  }

  // ---------- ADM-USR-01 / 02 ----------

  @Test
  void listsUsersWithMaskedContactsOnly() {
    var page = service().list(new AdminUserQuery(null, null, null, null, null, 1, 20));

    assertThat(page.items()).hasSize(2);
    assertThat(page.items())
        .allSatisfy(item -> assertThat(item.maskedEmail()).contains("***"))
        .allSatisfy(item -> assertThat(item.maskedEmail()).doesNotContain("analyst@"));
    assertThat(page.total()).isEqualTo(2);
  }

  @Test
  void reportsUserNotFoundForUnknownIds() {
    assertThatThrownBy(() -> service().detail(4242L))
        .isInstanceOf(AdminException.class)
        .extracting(error -> ((AdminException) error).code())
        .isEqualTo(AdminErrorCode.USER_NOT_FOUND);
  }

  @Test
  void givesTheDeliverabilitySignalFromTheMaskedValue() {
    assertThat(service().detail(TARGET_ID).emailConfigured()).isTrue();
    assertThat(service().detail(OPERATOR_ID).emailConfigured()).isTrue();
    long noEmailId = store.put(5005L, "noemail", null, null, AdminUserStatus.ACTIVE, Set.of());
    assertThat(service().detail(noEmailId).emailConfigured()).isFalse();
  }

  // ---------- ADM-USR-03 ----------

  @Test
  void storesAPasswordHashAndNeverThePlaintext() {
    CreatedAdminUser created = service().create(
        "newbie", "Temp@12345", "小新", "张新", "newbie@example.com", null,
        AdminUserStatus.ACTIVE, List.of(USER_ROLE_ID, USER_ROLE_ID), OPERATOR_ID);

    String stored = store.rawPassword(created.user().userId());
    assertThat(stored).isNotEqualTo("Temp@12345");
    assertThat(passwordEncoder.matches("Temp@12345", stored)).isTrue();
    // 重复的 roleIds 去掉，且响应里的角色来自回读
    assertThat(created.user().roles()).containsExactly(new AdminUserRole(USER_ROLE_ID, "role-101"));
    assertThat(created.mustChangePassword()).isTrue();
    verify(watchlist).createDefaultGroup(created.user().userId());
  }

  @Test
  void rejectsDuplicateUsernamesAndEmails() {
    assertThatThrownBy(() -> service().create(
        "analyst", "Temp@12345", null, null, null, null,
        AdminUserStatus.ACTIVE, List.of(), OPERATOR_ID))
        .isInstanceOf(AdminException.class)
        .extracting(error -> ((AdminException) error).code())
        .isEqualTo(AdminErrorCode.USERNAME_EXISTS);

    assertThatThrownBy(() -> service().create(
        "other", "Temp@12345", null, null, "analyst@example.com", null,
        AdminUserStatus.ACTIVE, List.of(), OPERATOR_ID))
        .isInstanceOf(AdminException.class)
        .extracting(error -> ((AdminException) error).code())
        .isEqualTo(AdminErrorCode.EMAIL_EXISTS);
  }

  @Test
  void rejectsUnknownRoles() {
    assertThatThrownBy(() -> service().create(
        "other", "Temp@12345", null, null, null, null,
        AdminUserStatus.ACTIVE, List.of(777L), OPERATOR_ID))
        .isInstanceOf(AdminException.class)
        .extracting(error -> ((AdminException) error).code())
        .isEqualTo(AdminErrorCode.ROLE_NOT_FOUND);
  }

  // ---------- ADM-USR-04 ----------

  @Test
  void updatesProfileAndAdvancesTheVersionByOne() {
    AdminUserDetail before = service().detail(TARGET_ID);

    AdminUserDetail updated = service().updateProfile(
        TARGET_ID, new AdminUserPatch("分析师", null, null, null), before.version(), OPERATOR_ID);

    assertThat(updated.nickName()).isEqualTo("分析师");
    assertThat(updated.version()).isEqualTo(before.version() + 1);
    assertThat(updated.tokenVersion()).isEqualTo(before.tokenVersion());
  }

  /** 只传了空白字段时间，等价于"没有可改的字段"，不该白白推进版本。 */
  @Test
  void rejectsAnUpdateWithoutAnyEffectiveField() {
    int version = service().detail(TARGET_ID).version();

    assertThatThrownBy(() -> service().updateProfile(
        TARGET_ID, new AdminUserPatch("   ", null, null, null), version, OPERATOR_ID))
        .isInstanceOf(AdminException.class)
        .extracting(error -> ((AdminException) error).code())
        .isEqualTo(AdminErrorCode.INVALID_REQUEST);

    assertThat(service().detail(TARGET_ID).version()).isEqualTo(version);
  }

  @Test
  void rejectsAStaleIfMatchVersion() {
    assertThatThrownBy(() -> service().updateProfile(
        TARGET_ID, new AdminUserPatch("分析师", null, null, null), 999, OPERATOR_ID))
        .isInstanceOf(AdminException.class)
        .extracting(error -> ((AdminException) error).code())
        .isEqualTo(AdminErrorCode.RESOURCE_VERSION_CONFLICT);
  }

  // ---------- ADM-USR-05 ----------

  @Test
  void lockingBumpsTokenVersionAndRevokesSessions() {
    sessions.activeTokens = 3;
    int version = service().detail(TARGET_ID).version();

    UserStatusChange change = service().changeStatus(
        TARGET_ID, AdminUserStatus.LOCKED, version, OPERATOR_ID);

    assertThat(change.status()).isEqualTo(AdminUserStatus.LOCKED);
    assertThat(change.revokedSessionCount()).isEqualTo(3);
    // 状态 +1、令牌版本 +1：两次写各自的版本推进都要算进去。
    assertThat(change.version()).isEqualTo(version + 2);
    AdminUserDetail after = service().detail(TARGET_ID);
    assertThat(after.tokenVersion()).isEqualTo(1);
    assertThat(sessions.revokedUsers).containsExactly(TARGET_ID);
  }

  /** 解锁只改状态：刚被解锁的人本来就该重新登录，再递增一次版本没有意义。 */
  @Test
  void unlockingDoesNotBumpTokenVersionNorRevokeSessions() {
    store.setStatus(TARGET_ID, AdminUserStatus.LOCKED);
    int version = service().detail(TARGET_ID).version();

    UserStatusChange change = service().changeStatus(
        TARGET_ID, AdminUserStatus.ACTIVE, version, OPERATOR_ID);

    assertThat(change.revokedSessionCount()).isZero();
    assertThat(change.version()).isEqualTo(version + 1);
    assertThat(service().detail(TARGET_ID).tokenVersion()).isZero();
    assertThat(sessions.revokedUsers).isEmpty();
  }

  @Test
  void refusesToLockTheLastActiveSuperAdmin() {
    roleStore.activeSuperAdmins = new LinkedHashSet<>(Set.of(OPERATOR_ID));
    int version = service().detail(OPERATOR_ID).version();

    assertThatThrownBy(() -> service().changeStatus(
        OPERATOR_ID, AdminUserStatus.LOCKED, version, OPERATOR_ID))
        .isInstanceOf(AdminException.class)
        .extracting(error -> ((AdminException) error).code())
        .isEqualTo(AdminErrorCode.LAST_SUPER_ADMIN_PROTECTED);

    assertThat(service().detail(OPERATOR_ID).status()).isEqualTo(AdminUserStatus.ACTIVE);
  }

  @Test
  void allowsLockingASuperAdminWhenAnotherOneRemains() {
    roleStore.activeSuperAdmins = new LinkedHashSet<>(Set.of(OPERATOR_ID, TARGET_ID));
    int version = service().detail(TARGET_ID).version();

    assertThat(service().changeStatus(
        TARGET_ID, AdminUserStatus.LOCKED, version, OPERATOR_ID).status())
        .isEqualTo(AdminUserStatus.LOCKED);
  }

  // ---------- ADM-USR-06 ----------

  @Test
  void replacingRolesBumpsTokenVersionAndRevokesSessions() {
    sessions.activeTokens = 2;
    int version = service().detail(TARGET_ID).version();

    UserRoleReplacement replaced = service().replaceRoles(
        TARGET_ID, List.of(USER_ROLE_ID), version, OPERATOR_ID);

    assertThat(replaced.roles()).containsExactly(new AdminUserRole(USER_ROLE_ID, "role-101"));
    assertThat(replaced.revokedSessionCount()).isEqualTo(2);
    assertThat(replaced.version()).isEqualTo(version + 1);
    AdminUserDetail after = service().detail(TARGET_ID);
    assertThat(after.tokenVersion()).isEqualTo(1);
  }

  @Test
  void refusesToStripTheLastSuperAdminOfTheirRole() {
    roleStore.activeSuperAdmins = new LinkedHashSet<>(Set.of(OPERATOR_ID));
    int version = service().detail(OPERATOR_ID).version();

    assertThatThrownBy(() -> service().replaceRoles(
        OPERATOR_ID, List.of(USER_ROLE_ID), version, OPERATOR_ID))
        .isInstanceOf(AdminException.class)
        .extracting(error -> ((AdminException) error).code())
        .isEqualTo(AdminErrorCode.LAST_SUPER_ADMIN_PROTECTED);
  }

  /** 给最后一个超管**加**一个普通角色不收回超管身份，不该被拒。 */
  @Test
  void allowsAddingARoleToTheLastSuperAdmin() {
    roleStore.activeSuperAdmins = new LinkedHashSet<>(Set.of(OPERATOR_ID));
    int version = service().detail(OPERATOR_ID).version();

    assertThat(service().replaceRoles(
        OPERATOR_ID, List.of(SUPER_ROLE_ID, USER_ROLE_ID), version, OPERATOR_ID).roles())
        .hasSize(2);
  }

  // ---------- ADM-USR-07 ----------

  @Test
  void issuesAResetCredentialWithoutClaimingItWasDelivered() {
    PasswordResetIssued issued = service().requestPasswordReset(TARGET_ID, "EMAIL");

    assertThat(issued.accepted()).isTrue();
    assertThat(issued.maskedDestination()).isEqualTo("a***@example.com");
    assertThat(issued.expiresInSeconds())
        .isEqualTo(PasswordResetCredentialStore.TTL_SECONDS);
    // 项目没有邮件通道：这两项必须如实反映，而不是只回一个 accepted=true。
    assertThat(issued.delivered()).isFalse();
    assertThat(issued.deliveryNote()).contains("未实际送达");
    assertThat(credentials.saved).hasSize(1);
    assertThat(credentials.saved.get(0).userId()).isEqualTo(TARGET_ID);
    assertThat(credentials.saved.get(0).expiresAt())
        .isEqualTo(NOW.plusSeconds(PasswordResetCredentialStore.TTL_SECONDS));
  }

  @Test
  void refusesToResetAPasswordWhenTheAccountHasNoEmail() {
    long noEmailId = store.put(5005L, "noemail", null, null, AdminUserStatus.ACTIVE, Set.of());

    assertThatThrownBy(() -> service().requestPasswordReset(noEmailId, "EMAIL"))
        .isInstanceOf(AdminException.class)
        .extracting(error -> ((AdminException) error).code())
        .isEqualTo(AdminErrorCode.PASSWORD_RESET_NO_DELIVERY_TARGET);

    assertThat(credentials.saved).isEmpty();
  }

  // ---------- ADM-USR-08 / 09 ----------

  @Test
  void revokingSessionsBumpsTokenVersionAndReportsTheCount() {
    sessions.activeTokens = 4;
    int version = service().detail(TARGET_ID).version();

    SessionRevocation revoked = service().revokeSessions(TARGET_ID, OPERATOR_ID);

    assertThat(revoked.revokedSessionCount()).isEqualTo(4);
    assertThat(revoked.tokenVersion()).isEqualTo(1);
    assertThat(service().detail(TARGET_ID).version()).isEqualTo(version + 1);
  }

  /**
   * 删除必须先撤权再软删。
   *
   * <p>反过来的话 {@code deleted = 0} 会让递增令牌版本的 SQL 匹配不到行
   * （它的条件里有 {@code deleted = 1}），于是被删的人还能拿旧令牌跑满 15 分钟。
   */
  @Test
  void deletingRevokesSessionsBeforeSoftDeleting() {
    sessions.activeTokens = 2;
    int version = service().detail(TARGET_ID).version();

    UserDeletion deleted = service().delete(TARGET_ID, version, OPERATOR_ID);

    assertThat(deleted.deleted()).isTrue();
    assertThat(deleted.revokedSessionCount()).isEqualTo(2);
    assertThat(store.deletedAtOrder()).contains(TARGET_ID);
    // 软删之后查不到，但库里的 token_version 已经被推进过。
    assertThat(store.rawTokenVersion(TARGET_ID)).isEqualTo(1);
    assertThat(service().list(new AdminUserQuery(null, null, null, null, null, 1, 20)).total())
        .isEqualTo(1);
  }

  @Test
  void refusesToDeleteYourself() {
    int version = service().detail(OPERATOR_ID).version();

    assertThatThrownBy(() -> service().delete(OPERATOR_ID, version, OPERATOR_ID))
        .isInstanceOf(AdminException.class)
        .extracting(error -> ((AdminException) error).code())
        .isEqualTo(AdminErrorCode.SELF_OPERATION_FORBIDDEN);
  }

  @Test
  void refusesToDeleteTheLastActiveSuperAdmin() {
    roleStore.activeSuperAdmins = new LinkedHashSet<>(Set.of(TARGET_ID));
    int version = service().detail(TARGET_ID).version();

    assertThatThrownBy(() -> service().delete(TARGET_ID, version, OPERATOR_ID))
        .isInstanceOf(AdminException.class)
        .extracting(error -> ((AdminException) error).code())
        .isEqualTo(AdminErrorCode.LAST_SUPER_ADMIN_PROTECTED);

    assertThat(store.deletedAtOrder()).isEmpty();
  }

  /** 建号失败时不该留下默认分组——账号不存在却有分组，是别人看不到的脏数据。 */
  @Test
  void doesNotCreateADefaultGroupWhenCreationFails() {
    assertThatThrownBy(() -> service().create(
        "analyst", "Temp@12345", null, null, null, null,
        AdminUserStatus.ACTIVE, List.of(), OPERATOR_ID))
        .isInstanceOf(AdminException.class);
    verify(watchlist, never()).createDefaultGroup(anyLong());
  }

  private AdminUserService service() {
    Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Shanghai"));
    return new AdminUserService(
        store,
        roleStore,
        sessions,
        credentials,
        watchlist,
        passwordEncoder,
        () -> "credential-plaintext",
        clock);
  }

  // ---------- 测试替身 ----------

  /** 内存用户表：实现乐观锁与版本推进，好让"版本链算错了"真的变成测试失败。 */
  private static final class FakeUserStore implements AdminUserStore {

    private final Map<Long, Row> rows = new LinkedHashMap<>();
    private final List<Long> deletions = new ArrayList<>();

    long put(
        long id,
        String username,
        String email,
        String phone,
        AdminUserStatus status,
        Set<Long> roleIds) {
      rows.put(id, new Row(id, username, "hash", email, phone, status, roleIds));
      return id;
    }

    void setStatus(long id, AdminUserStatus status) {
      rows.get(id).status = status;
    }

    String rawPassword(long id) {
      return rows.get(id).password;
    }

    int rawTokenVersion(long id) {
      return rows.get(id).tokenVersion;
    }

    List<Long> deletedAtOrder() {
      return deletions;
    }

    @Override
    public AdminUserPage page(AdminUserQuery query) {
      List<AdminUserSummary> items = rows.values().stream()
          .filter(row -> !row.deleted)
          .filter(row -> query.status() == null || row.status == query.status())
          .filter(row -> !query.hasKeyword() || row.username.contains(query.keyword()))
          .map(row -> new AdminUserSummary(
              row.id, row.username, MaskedContact.email(row.email),
              MaskedContact.phone(row.phone), row.nickName, row.status, rolesOf(row),
              OffsetDateTime.parse("2026-09-01T00:00:00Z"), null, row.version))
          .toList();
      return new AdminUserPage(items, items.size());
    }

    @Override
    public Optional<AdminUserDetail> find(long userId) {
      Row row = rows.get(userId);
      return row == null || row.deleted ? Optional.empty() : Optional.of(detail(row));
    }

    @Override
    public boolean usernameExists(String username) {
      return rows.values().stream()
          .anyMatch(row -> !row.deleted && row.username.equals(username));
    }

    @Override
    public boolean emailExists(String email, long excludingUserId) {
      return rows.values().stream().anyMatch(row ->
          !row.deleted && row.id != excludingUserId && email.equals(row.email));
    }

    @Override
    public long insert(NewAdminUser user) {
      long id = 7000L + rows.size();
      rows.put(id, new Row(id, user.username(), user.passwordHash(), user.email(),
          user.phone(), user.status(), new LinkedHashSet<>(user.roleIds())));
      return id;
    }

    @Override
    public int updateProfile(
        long userId, AdminUserPatch patch, int expectedVersion, long operatorId) {
      Row row = guard(userId, expectedVersion);
      if (row == null) {
        return 0;
      }
      if (patch.nickName() != null) {
        row.nickName = patch.nickName();
      }
      if (patch.realName() != null) {
        row.realName = patch.realName();
      }
      if (patch.email() != null) {
        row.email = patch.email();
      }
      if (patch.phone() != null) {
        row.phone = patch.phone();
      }
      row.version++;
      return 1;
    }

    @Override
    public int updateStatus(
        long userId, AdminUserStatus status, int expectedVersion, long operatorId) {
      Row row = guard(userId, expectedVersion);
      if (row == null) {
        return 0;
      }
      row.status = status;
      row.version++;
      return 1;
    }

    @Override
    public int bumpTokenVersion(long userId, int expectedVersion, long operatorId) {
      Row row = guard(userId, expectedVersion);
      if (row == null) {
        return 0;
      }
      row.tokenVersion++;
      row.version++;
      return 1;
    }

    @Override
    public int replaceRoles(
        long userId, List<Long> roleIds, int expectedVersion, long operatorId) {
      Row row = guard(userId, expectedVersion);
      if (row == null) {
        return 0;
      }
      row.tokenVersion++;
      row.version++;
      row.roleIds = new LinkedHashSet<>(roleIds);
      return 1;
    }

    @Override
    public int softDelete(long userId, int expectedVersion, long operatorId) {
      Row row = guard(userId, expectedVersion);
      if (row == null) {
        return 0;
      }
      row.deleted = true;
      row.version++;
      deletions.add(userId);
      return 1;
    }

    @Override
    public List<Long> invalidRoleIds(Collection<Long> roleIds) {
      return roleIds.stream().filter(id -> id != SUPER_ROLE_ID && id != USER_ROLE_ID).toList();
    }

    private Row guard(long userId, int expectedVersion) {
      Row row = rows.get(userId);
      if (row == null || row.deleted || row.version != expectedVersion) {
        return null;
      }
      return row;
    }

    private List<AdminUserRole> rolesOf(Row row) {
      return row.roleIds.stream()
          .map(id -> new AdminUserRole(id, "role-" + id))
          .toList();
    }

    private AdminUserDetail detail(Row row) {
      return new AdminUserDetail(
          row.id, row.username, MaskedContact.email(row.email),
          MaskedContact.phone(row.phone), row.nickName, row.realName, row.status, rolesOf(row),
          1, OffsetDateTime.parse("2026-09-01T00:00:00Z"),
          OffsetDateTime.parse("2026-09-02T00:00:00Z"), null, row.tokenVersion, row.version);
    }

    private static final class Row {
      private final long id;
      private final String username;
      private final String password;
      private String email;
      private String phone;
      private String nickName;
      private String realName;
      private AdminUserStatus status;
      private Set<Long> roleIds;
      private int tokenVersion;
      private int version;
      private boolean deleted;

      Row(long id, String username, String password, String email, String phone,
          AdminUserStatus status, Set<Long> roleIds) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.email = email;
        this.phone = phone;
        this.status = status;
        this.roleIds = roleIds;
      }
    }
  }

  private static final class FakeRoleStore implements AdminRoleStore {

    private final Set<Long> validRoleIds = new LinkedHashSet<>();
    private long superRoleId;
    private Set<Long> activeSuperAdmins = new LinkedHashSet<>();

    @Override
    public RolePage page(RoleQuery query) {
      throw new UnsupportedOperationException();
    }

    @Override
    public OptionalLong superAdminRoleId() {
      return superRoleId == 0 ? OptionalLong.empty() : OptionalLong.of(superRoleId);
    }

    @Override
    public boolean isActiveSuperAdmin(long userId) {
      return activeSuperAdmins.contains(userId);
    }

    @Override
    public long countActiveSuperAdmins() {
      return activeSuperAdmins.size();
    }
  }

  private static final class FakeSessionStore implements RefreshSessionStore {

    private int activeTokens;
    private final List<Long> revokedUsers = new ArrayList<>();

    @Override
    public Optional<cn.zhishi.stock.system.auth.RefreshTokenRecord> findByTokenHash(
        String tokenHash) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void save(cn.zhishi.stock.system.auth.RefreshTokenRecord record) {
      throw new UnsupportedOperationException();
    }

    @Override
    public RotationOutcome rotate(
        cn.zhishi.stock.system.auth.RefreshTokenRecord previous,
        cn.zhishi.stock.system.auth.RefreshTokenRecord next) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void revokeFamily(String familyId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int revokeAllForUser(long userId) {
      revokedUsers.add(userId);
      int revoked = activeTokens;
      activeTokens = 0;
      return revoked;
    }
  }

  private static final class RecordingCredentialStore implements PasswordResetCredentialStore {

    private final List<Saved> saved = new ArrayList<>();

    @Override
    public void save(long userId, String credentialHash, Instant expiresAt) {
      saved.add(new Saved(userId, credentialHash, expiresAt));
    }

    record Saved(long userId, String credentialHash, Instant expiresAt) {
    }
  }
}
