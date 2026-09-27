package cn.zhishi.stock.backend.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.admin.application.AdminException;
import cn.zhishi.stock.admin.application.AdminUserService;
import cn.zhishi.stock.admin.application.CreatedAdminUser;
import cn.zhishi.stock.admin.application.PasswordResetIssued;
import cn.zhishi.stock.admin.application.SessionRevocation;
import cn.zhishi.stock.admin.application.UserDeletion;
import cn.zhishi.stock.admin.application.UserRoleReplacement;
import cn.zhishi.stock.admin.application.UserStatusChange;
import cn.zhishi.stock.admin.domain.AdminUserDetail;
import cn.zhishi.stock.admin.domain.AdminUserRole;
import cn.zhishi.stock.admin.domain.AdminUserStatus;
import cn.zhishi.stock.admin.domain.AdminUserSummary;
import cn.zhishi.stock.backend.web.AuditRecorder;
import cn.zhishi.stock.backend.web.GlobalExceptionHandler;
import cn.zhishi.stock.backend.web.TraceIdFilter;
import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.common.audit.AuditEvent;
import cn.zhishi.stock.common.audit.AuditLog;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.idempotency.IdempotencyGuard;
import cn.zhishi.stock.system.idempotency.IdempotencyRecord;
import cn.zhishi.stock.system.idempotency.IdempotencyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 后台用户接口契约（{@code RESTful-API.md} §16.1 ADM-USR-01~09、§3.7 幂等、§22.3 审计）。
 *
 * <h2>这一层钉的是 HTTP 面上的事实</h2>
 * <ul>
 *   <li>缺 {@code Idempotency-Key} 是 400，而不是"当成没有幂等保护"；</li>
 *   <li>同一个键 + 同一个请求体只真正执行一次；</li>
 *   <li>缺 {@code If-Match} 是 400；带引号的写法要能解析；</li>
 *   <li>业务码到 HTTP 状态的映射（404 / 409 / 422）与统一返回壳；</li>
 *   <li><b>响应与审计里都不出现临时密码</b>——密码只在请求体里出现一次。</li>
 * </ul>
 *
 * <h2>权限码不在这里测</h2>
 * {@code standaloneSetup} 没有 AOP 基础设施，{@code @PreAuthorize} 不会生效。
 * 端点到权限码的对应关系由 {@code AdminAuthorizationTest} 逐行核对，
 * 方法级鉴权本身由 {@code SecurityConfigurationTest} 用真实容器验证。
 * 在这里假装测了，只会得到"测过了但没测到"。
 */
class AdminUserControllerContractTest {

  private static final long USER_ID = 7001L;
  private static final long OPERATOR_ID = 9001L;
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-23T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  private static final String CREATE_BODY = """
      {"username":"newbie","password":"Temp@12345","nickName":"小新",
       "email":"newbie@example.com","status":"ACTIVE","roleIds":[101]}
      """;

  private final AdminUserService users = mock(AdminUserService.class);
  private final RecordingAuditLog auditLog = new RecordingAuditLog();
  private final InMemoryIdempotencyStore idempotencyStore = new InMemoryIdempotencyStore();
  private final ObjectMapper objectMapper = mapper();

  @BeforeEach
  void defaultStubs() {
    when(users.create(
            anyString(), anyString(), any(), any(), any(), any(), any(), any(), anyLong()))
        .thenReturn(CreatedAdminUser.of(detail(0)));
    when(users.detail(anyLong())).thenReturn(detail(0));
    when(users.requestPasswordReset(anyLong(), anyString()))
        .thenReturn(new PasswordResetIssued(
            true, "a***@example.com", 1800, false, "凭证已生成，但项目尚未接入邮件通道，未实际送达用户。"));
  }

  // ---------- ADM-USR-01 ----------

  @Test
  void returnsTheStandardPaginationEnvelope() throws Exception {
    when(users.list(any())).thenReturn(new PageData<>(
        List.of(summary()), 1, 20, 42, 3, true));

    mvc().perform(get("/api/v1/admin/users").param("page", "1").param("size", "20")
            .principal(authentication(OPERATOR_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.items[0].userId").value(USER_ID))
        .andExpect(jsonPath("$.data.items[0].status").value("ACTIVE"))
        .andExpect(jsonPath("$.data.items[0].roles[0].name").value("USER"))
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(20))
        .andExpect(jsonPath("$.data.total").value(42))
        .andExpect(jsonPath("$.data.totalPages").value(3))
        .andExpect(jsonPath("$.data.hasNext").value(true));
  }

  // ---------- ADM-USR-03 ----------

  @Test
  void requiresIdempotencyKeyOnCreate() throws Exception {
    mvc().perform(post("/api/v1/admin/users")
            .contentType(MediaType.APPLICATION_JSON)
            .content(CREATE_BODY)
            .principal(authentication(OPERATOR_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void replaysTheCreateWithoutRunningItTwice() throws Exception {
    mvc().perform(createRequest("key-1")).andExpect(status().isOk());
    mvc().perform(createRequest("key-1")).andExpect(status().isOk());

    verify(users, times(1)).create(
        anyString(), anyString(), any(), any(), any(), any(), any(), any(), anyLong());
  }

  /** 契约 §16.1 ADM-USR-03：临时密码不出现在响应里。 */
  @Test
  void neverEchoesTheTemporaryPassword() throws Exception {
    mvc().perform(createRequest("key-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.mustChangePassword").value(true))
        .andExpect(jsonPath("$.data.password").doesNotExist())
        .andExpect(jsonPath("$.data.user.password").doesNotExist())
        .andExpect(jsonPath("$.data.user.tokenVersion").value(0));
  }

  /** 契约 §22.2：请求参数先脱敏。密码绝不出现在审计摘要里。 */
  @Test
  void keepsTheTemporaryPasswordOutOfTheAuditSummary() throws Exception {
    mvc().perform(createRequest("key-1")).andExpect(status().isOk());

    assertThat(auditLog.events).singleElement().satisfies(event -> {
      assertThat(event.operation()).isEqualTo("ADMIN_USER_CREATE");
      assertThat(event.paramsSummary()).contains("username=newbie");
      assertThat(event.paramsSummary()).doesNotContain("Temp@12345");
      assertThat(event.resultStatus()).isEqualTo(AuditEvent.SUCCESS);
      assertThat(event.traceId()).isNotBlank();
    });
  }

  // ---------- ADM-USR-04 ----------

  @Test
  void requiresIfMatchToUpdateAProfile() throws Exception {
    mvc().perform(patch("/api/v1/admin/users/{id}", USER_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nickName\":\"分析师\"}")
            .principal(authentication(OPERATOR_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void acceptsBothQuotedAndBareIfMatchValues() throws Exception {
    when(users.updateProfile(anyLong(), any(), anyInt(), anyLong())).thenReturn(detail(1));

    mvc().perform(patch("/api/v1/admin/users/{id}", USER_ID)
            .header("If-Match", "\"3\"")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nickName\":\"分析师\"}")
            .principal(authentication(OPERATOR_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value(1));

    verify(users).updateProfile(anyLong(), any(), org.mockito.ArgumentMatchers.eq(3), anyLong());
  }

  @Test
  void mapsAStaleVersionTo409() throws Exception {
    when(users.updateProfile(anyLong(), any(), anyInt(), anyLong()))
        .thenThrow(AdminException.versionConflict(7));

    mvc().perform(patch("/api/v1/admin/users/{id}", USER_ID)
            .header("If-Match", "3")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nickName\":\"分析师\"}")
            .principal(authentication(OPERATOR_ID)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("RESOURCE_VERSION_CONFLICT"))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("version=7")));
  }

  @Test
  void mapsUserNotFoundTo404() throws Exception {
    when(users.detail(anyLong())).thenThrow(AdminException.userNotFound());

    mvc().perform(get("/api/v1/admin/users/{id}", 4242L)
            .principal(authentication(OPERATOR_ID)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ADMIN_USER_NOT_FOUND"));
  }

  /** 非法枚举取值由消息转换器拒绝，不能落到"看成 null 于是当成全部状态"。 */
  @Test
  void rejectsAnUnknownStatusValue() throws Exception {
    mvc().perform(get("/api/v1/admin/users").param("status", "SLEEPING")
            .principal(authentication(OPERATOR_ID)))
        .andExpect(status().isBadRequest());
  }

  // ---------- ADM-USR-05 / 06 / 07 / 08 / 09 ----------

  /**
   * 没填原因时摘要里不能出现 {@code reason=null}。
   *
   * <p>{@code "reason=" + text(null)} 会拼出字面量 {@code null}——Java 的字符串拼接
   * 不做判空。于是每条日志都带一个 {@code reason} 字段，运维按它检索时分不清
   * "没填原因"与"原因就是 null 这个词"。
   */
  @Test
  void omitsTheReasonFromTheAuditWhenItWasNotGiven() throws Exception {
    when(users.changeStatus(anyLong(), any(), anyInt(), anyLong()))
        .thenReturn(new UserStatusChange(USER_ID, AdminUserStatus.LOCKED, 0, 1));

    mvc().perform(patch("/api/v1/admin/users/{id}/status", USER_ID)
            .header("If-Match", "1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"LOCKED\"}")
            .principal(authentication(OPERATOR_ID)))
        .andExpect(status().isOk());

    assertThat(auditLog.events).singleElement().satisfies(event -> {
      assertThat(event.operation()).isEqualTo("ADMIN_USER_STATUS_CHANGE");
      assertThat(event.paramsSummary()).isEqualTo("userId=7001;status=LOCKED");
    });
  }

  @Test
  void mapsLastSuperAdminProtectionTo409AndRecordsItAsDenied() throws Exception {
    when(users.changeStatus(anyLong(), any(), anyInt(), anyLong()))
        .thenThrow(AdminException.lastSuperAdminProtected());

    mvc().perform(patch("/api/v1/admin/users/{id}/status", USER_ID)
            .header("If-Match", "1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"LOCKED\",\"reason\":\"离职\"}")
            .principal(authentication(OPERATOR_ID)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("LAST_SUPER_ADMIN_PROTECTED"));

    // 被规则拒绝记 DENIED 而不是 FAILURE：运维要看的是"谁在越权"，不是"哪里坏了"。
    assertThat(auditLog.events).singleElement()
        .extracting(AuditEvent::resultStatus)
        .isEqualTo(AuditEvent.DENIED);
  }

  @Test
  void reportsTheRevokedSessionCountWhenLocking() throws Exception {
    when(users.changeStatus(anyLong(), any(), anyInt(), anyLong()))
        .thenReturn(new UserStatusChange(USER_ID, AdminUserStatus.LOCKED, 2, 3));

    mvc().perform(patch("/api/v1/admin/users/{id}/status", USER_ID)
            .header("If-Match", "1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"LOCKED\"}")
            .principal(authentication(OPERATOR_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("LOCKED"))
        .andExpect(jsonPath("$.data.revokedSessionCount").value(2))
        .andExpect(jsonPath("$.data.version").value(3));
  }

  @Test
  void replacesRolesAndReportsTheNewVersion() throws Exception {
    when(users.replaceRoles(anyLong(), any(), anyInt(), anyLong()))
        .thenReturn(new UserRoleReplacement(
            List.of(new AdminUserRole(101L, "USER")), 1, 2));

    mvc().perform(put("/api/v1/admin/users/{id}/roles", USER_ID)
            .header("If-Match", "1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"roleIds\":[101]}")
            .principal(authentication(OPERATOR_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.roles[0].roleId").value(101))
        .andExpect(jsonPath("$.data.revokedSessionCount").value(1))
        .andExpect(jsonPath("$.data.version").value(2));
  }

  /**
   * ADM-USR-07 的响应必须自己说清楚"没送达"。
   *
   * <p>只看 {@code accepted=true} 的调用方会以为用户马上会收到邮件——项目里没有邮件通道。
   */
  @Test
  void tellsTheCallerThatAResetCredentialWasNotDelivered() throws Exception {
    mvc().perform(post("/api/v1/admin/users/{id}/password-reset", USER_ID)
            .header("Idempotency-Key", "key-pr")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"delivery\":\"EMAIL\",\"reason\":\"用户忘记密码\"}")
            .principal(authentication(OPERATOR_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accepted").value(true))
        .andExpect(jsonPath("$.data.delivered").value(false))
        .andExpect(jsonPath("$.data.maskedDestination").value("a***@example.com"))
        .andExpect(jsonPath("$.data.deliveryNote").value(
            org.hamcrest.Matchers.containsString("未实际送达")));
  }

  @Test
  void mapsAMissingDeliveryTargetTo422() throws Exception {
    when(users.requestPasswordReset(anyLong(), anyString()))
        .thenThrow(AdminException.passwordResetNoDeliveryTarget());

    mvc().perform(post("/api/v1/admin/users/{id}/password-reset", USER_ID)
            .header("Idempotency-Key", "key-pr")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"delivery\":\"EMAIL\"}")
            .principal(authentication(OPERATOR_ID)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ADMIN_PASSWORD_RESET_NO_DELIVERY_TARGET"));
  }

  @Test
  void requiresIdempotencyKeyToRevokeSessions() throws Exception {
    mvc().perform(post("/api/v1/admin/users/{id}/sessions/revoke", USER_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"疑似被盗\"}")
            .principal(authentication(OPERATOR_ID)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void reportsTheRevokedSessionCountAndTokenVersion() throws Exception {
    when(users.revokeSessions(anyLong(), anyLong()))
        .thenReturn(new SessionRevocation(3, 5));

    mvc().perform(post("/api/v1/admin/users/{id}/sessions/revoke", USER_ID)
            .header("Idempotency-Key", "key-rs")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"疑似被盗\"}")
            .principal(authentication(OPERATOR_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.revokedSessionCount").value(3))
        .andExpect(jsonPath("$.data.tokenVersion").value(5));

    assertThat(auditLog.events).singleElement()
        .extracting(AuditEvent::operation)
        .isEqualTo("ADMIN_USER_SESSION_REVOKE");
  }

  @Test
  void refusesToDeleteYourselfWithAClearCode() throws Exception {
    when(users.delete(anyLong(), anyInt(), anyLong()))
        .thenThrow(AdminException.selfOperationForbidden("删除"));

    mvc().perform(delete("/api/v1/admin/users/{id}", OPERATOR_ID)
            .header("If-Match", "1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"误建\"}")
            .principal(authentication(OPERATOR_ID)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ADMIN_SELF_OPERATION_FORBIDDEN"));
  }

  @Test
  void requiresIfMatchToDelete() throws Exception {
    mvc().perform(delete("/api/v1/admin/users/{id}", USER_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"误建\"}")
            .principal(authentication(OPERATOR_ID)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void reportsTheDeletedFlagAndRevokedSessionCount() throws Exception {
    when(users.delete(anyLong(), anyInt(), anyLong())).thenReturn(new UserDeletion(true, 1));

    mvc().perform(delete("/api/v1/admin/users/{id}", USER_ID)
            .header("If-Match", "1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"误建\"}")
            .principal(authentication(OPERATOR_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.deleted").value(true))
        .andExpect(jsonPath("$.data.revokedSessionCount").value(1));
  }

  /** 每个写操作都要留下一条审计，否则"谁在什么时候动了这个用户"会有空洞。 */
  @Test
  void recordsEveryWriteOperation() throws Exception {
    when(users.updateProfile(anyLong(), any(), anyInt(), anyLong())).thenReturn(detail(1));
    when(users.changeStatus(anyLong(), any(), anyInt(), anyLong()))
        .thenReturn(new UserStatusChange(USER_ID, AdminUserStatus.LOCKED, 0, 2));
    when(users.replaceRoles(anyLong(), any(), anyInt(), anyLong()))
        .thenReturn(new UserRoleReplacement(List.of(), 0, 2));
    when(users.revokeSessions(anyLong(), anyLong())).thenReturn(new SessionRevocation(0, 1));
    when(users.delete(anyLong(), anyInt(), anyLong())).thenReturn(new UserDeletion(true, 0));

    mvc().perform(createRequest("k1")).andExpect(status().isOk());
    mvc().perform(patch("/api/v1/admin/users/{id}", USER_ID).header("If-Match", "1")
            .contentType(MediaType.APPLICATION_JSON).content("{\"nickName\":\"x\"}")
            .principal(authentication(OPERATOR_ID))).andExpect(status().isOk());
    mvc().perform(patch("/api/v1/admin/users/{id}/status", USER_ID).header("If-Match", "1")
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LOCKED\"}")
            .principal(authentication(OPERATOR_ID))).andExpect(status().isOk());
    mvc().perform(put("/api/v1/admin/users/{id}/roles", USER_ID).header("If-Match", "1")
            .contentType(MediaType.APPLICATION_JSON).content("{\"roleIds\":[]}")
            .principal(authentication(OPERATOR_ID))).andExpect(status().isOk());
    mvc().perform(post("/api/v1/admin/users/{id}/password-reset", USER_ID)
            .header("Idempotency-Key", "k2").contentType(MediaType.APPLICATION_JSON)
            .content("{\"delivery\":\"EMAIL\"}")
            .principal(authentication(OPERATOR_ID))).andExpect(status().isOk());
    mvc().perform(post("/api/v1/admin/users/{id}/sessions/revoke", USER_ID)
            .header("Idempotency-Key", "k3").contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"r\"}")
            .principal(authentication(OPERATOR_ID))).andExpect(status().isOk());
    mvc().perform(delete("/api/v1/admin/users/{id}", USER_ID).header("If-Match", "1")
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"r\"}")
            .principal(authentication(OPERATOR_ID))).andExpect(status().isOk());

    assertThat(auditLog.events)
        .extracting(AuditEvent::operation)
        .containsExactly(
            "ADMIN_USER_CREATE",
            "ADMIN_USER_UPDATE",
            "ADMIN_USER_STATUS_CHANGE",
            "ADMIN_USER_ROLE_REPLACE",
            "ADMIN_USER_PASSWORD_RESET",
            "ADMIN_USER_SESSION_REVOKE",
            "ADMIN_USER_DELETE");
    assertThat(auditLog.events).allSatisfy(event -> {
      assertThat(event.userId()).isEqualTo(OPERATOR_ID);
      assertThat(event.resultStatus()).isEqualTo(AuditEvent.SUCCESS);
    });
  }

  // ---------- 装配 ----------

  private MockHttpServletRequestBuilder createRequest(String key) {
    return post("/api/v1/admin/users")
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(CREATE_BODY)
        .principal(authentication(OPERATOR_ID));
  }

  private MockMvc mvc() {
    return MockMvcBuilders.standaloneSetup(new AdminUserController(
            users,
            new IdempotencyGuard(idempotencyStore, objectMapper),
            new AuditRecorder(auditLog),
            CLOCK))
        .setControllerAdvice(new GlobalExceptionHandler(CLOCK))
        .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
        .addFilters(new TraceIdFilter())
        .build();
  }

  private static ObjectMapper mapper() {
    return Jackson2ObjectMapperBuilder.json()
        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
  }

  private static UsernamePasswordAuthenticationToken authentication(long userId) {
    AccessTokenPrincipal principal = new AccessTokenPrincipal(
        userId, "admin", Set.of("sys:user:list"), "jti-1",
        Instant.parse("2030-01-01T00:00:00Z"), 0);
    return new UsernamePasswordAuthenticationToken(principal, null, List.of());
  }

  private static AdminUserDetail detail(int version) {
    return new AdminUserDetail(
        USER_ID, "newbie", "n***@example.com", null, "小新", "张新", AdminUserStatus.ACTIVE,
        List.of(new AdminUserRole(101L, "USER")), 1,
        OffsetDateTime.parse("2026-09-01T00:00:00Z"),
        OffsetDateTime.parse("2026-09-02T00:00:00Z"), null, 0, version);
  }

  private static AdminUserSummary summary() {
    return new AdminUserSummary(
        USER_ID, "analyst", "a***@example.com", "138****1111", "分析师",
        AdminUserStatus.ACTIVE, List.of(new AdminUserRole(101L, "USER")),
        OffsetDateTime.parse("2026-09-01T00:00:00Z"), null, 0);
  }

  private static final class RecordingAuditLog implements AuditLog {

    private final List<AuditEvent> events = new ArrayList<>();

    @Override
    public void record(AuditEvent event) {
      events.add(event);
    }
  }

  private static final class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, IdempotencyRecord> records = new HashMap<>();

    @Override
    public Optional<IdempotencyRecord> find(String scope, long userId, String key) {
      return Optional.ofNullable(records.get(scope + '|' + userId + '|' + key));
    }

    @Override
    public void save(String scope, long userId, String key, IdempotencyRecord record) {
      records.put(scope + '|' + userId + '|' + key, record);
    }
  }
}
