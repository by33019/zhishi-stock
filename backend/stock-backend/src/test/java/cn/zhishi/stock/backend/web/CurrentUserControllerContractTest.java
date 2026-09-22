package cn.zhishi.stock.backend.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.ai.application.AiQuotaQueryService;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.auth.UserAccount;
import cn.zhishi.stock.system.auth.UserAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CurrentUserControllerContractTest {

  private static final long USER_ID = 9_900_000_000_003L;

  /** 固定为 {@code Asia/Shanghai} 的 2026-09-11 10:00——自然日边界靠它可判。 */
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-11T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  @Test
  void returnsCurrentUserAndPermissionsFromAuthenticatedPrincipal() throws Exception {
    UserAccountRepository accounts = mock(UserAccountRepository.class);
    when(accounts.findById(USER_ID)).thenReturn(Optional.of(new UserAccount(
        USER_ID, "demo", "hash", UserAccount.Status.ACTIVE, "演示用户", 7)));
    when(accounts.findPermissions(USER_ID)).thenReturn(Set.of("market:read", "watchlist:read"));
    MockMvc mvc = mvc(accounts, mock(AiTaskStore.class));
    var authentication = authentication();

    mvc.perform(get("/api/v1/users/me").principal(authentication))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.userId").isString())
        .andExpect(jsonPath("$.data.userId").value("9900000000003"))
        .andExpect(jsonPath("$.data.username").value("demo"))
        .andExpect(jsonPath("$.data.displayName").value("演示用户"));

    mvc.perform(get("/api/v1/users/me/permissions").principal(authentication))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.permissionCodes.length()").value(2))
        .andExpect(jsonPath("$.data.tokenVersion").value(7));
  }

  /**
   * USER-07。
   *
   * <h2>这里钉的是"额度按任务数、并发按活跃数"这两个口径，以及日期边界</h2>
   * {@code date} 与 {@code resetsAt} 必须落在同一天的两端（9-11 零点 → 9-12 零点）：
   * 它们若各自取一次"现在"，跨零点的那一次请求会给出
   * "date=9-11、resetsAt=9-13 零点"这种自相矛盾的组合，而界面只会照单显示。
   */
  @Test
  void returnsAiQuotaForCurrentUser() throws Exception {
    AiTaskStore tasks = mock(AiTaskStore.class);
    when(tasks.countCreatedSince(anyLong(), any())).thenReturn(3);
    when(tasks.countByUserAndStatuses(anyLong(), any())).thenReturn(1);
    MockMvc mvc = mvc(mock(UserAccountRepository.class), tasks);

    mvc.perform(get("/api/v1/users/me/ai-quota").principal(authentication()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.date").value("2026-09-11"))
        .andExpect(jsonPath("$.data.dailyLimit").value(20))
        .andExpect(jsonPath("$.data.usedCount").value(3))
        .andExpect(jsonPath("$.data.remainingCount").value(17))
        .andExpect(jsonPath("$.data.runningCount").value(1))
        .andExpect(jsonPath("$.data.concurrentLimit").value(2))
        .andExpect(jsonPath("$.data.resetsAt").value("2026-09-12T00:00:00+08:00"));
  }

  /** 额度用尽时 {@code remainingCount} 不得为负——页面上的"还剩 -1 次"是可见的故障。 */
  @Test
  void neverReportsNegativeRemainingCount() throws Exception {
    AiTaskStore tasks = mock(AiTaskStore.class);
    when(tasks.countCreatedSince(anyLong(), any())).thenReturn(25);
    when(tasks.countByUserAndStatuses(anyLong(), any())).thenReturn(0);
    MockMvc mvc = mvc(mock(UserAccountRepository.class), tasks);

    mvc.perform(get("/api/v1/users/me/ai-quota").principal(authentication()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.usedCount").value(25))
        .andExpect(jsonPath("$.data.remainingCount").value(0));
  }

  private static MockMvc mvc(UserAccountRepository accounts, AiTaskStore tasks) {
    // 独立 MockMvc 的默认 ObjectMapper 未注册 JavaTimeModule，会把 LocalDate 序列化成数组
    // （如 [2026,9,11]）；Spring 的 Jackson2ObjectMapperBuilder 默认也不关闭
    // WRITE_DATES_AS_TIMESTAMPS——这个开关是 Spring Boot 自动配置打开的。
    // 这里显式对齐线上配置，否则 "{@code date} 是不是 ISO 字符串" 这条契约根本没被验到。
    ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
    // 装真的 AiQuotaQueryService 而不是 mock 它：配额口径（自然日边界、remaining 的钳制）
    // 正是这里要验的东西，把它 mock 掉等于把被测对象换成了自己的预期。
    return MockMvcBuilders.standaloneSetup(new CurrentUserController(
            accounts, new AiQuotaQueryService(tasks, CLOCK, 20, 2), CLOCK))
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
        .addFilters(new TraceIdFilter())
        .build();
  }

  private static UsernamePasswordAuthenticationToken authentication() {
    var principal = new AccessTokenPrincipal(
        USER_ID,
        "demo",
        Set.of("market:read", "watchlist:read"),
        "jti-1",
        Instant.parse("2026-09-11T02:15:00Z"));
    return new UsernamePasswordAuthenticationToken(principal, "token", Set.of());
  }
}
