package cn.zhishi.stock.system.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IdempotencyGuardTest {

  private static final long USER_ID = 9_900_000_000_003L;
  private static final String SCOPE = "watchlist-group:create";

  private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
  private final IdempotencyGuard guard =
      new IdempotencyGuard(store, new ObjectMapper().findAndRegisterModules());

  @Test
  void firstSubmissionRunsTheActionAndRemembersTheResult() {
    AtomicInteger executions = new AtomicInteger();

    CreatedValue first = guard.execute(
        SCOPE, USER_ID, "key-1", new Request("我的自选"), CreatedValue.class, () -> {
          executions.incrementAndGet();
          return new CreatedValue("7001", "我的自选");
        });

    assertThat(executions).hasValue(1);
    assertThat(first).isEqualTo(new CreatedValue("7001", "我的自选"));
    assertThat(store.saved()).hasSize(1);
  }

  @Test
  void sameKeyAndSameBodyReplaysTheFirstResultWithoutRunningTheAction() {
    AtomicInteger executions = new AtomicInteger();
    Request body = new Request("我的自选");

    guard.execute(SCOPE, USER_ID, "key-1", body, CreatedValue.class, () -> {
      executions.incrementAndGet();
      return new CreatedValue("7001", "我的自选");
    });
    CreatedValue replayed = guard.execute(SCOPE, USER_ID, "key-1", body, CreatedValue.class, () -> {
      executions.incrementAndGet();
      return new CreatedValue("9999", "不该被创建");
    });

    assertThat(executions)
        .describedAs("重复提交不得再执行一次业务动作")
        .hasValue(1);
    assertThat(replayed).isEqualTo(new CreatedValue("7001", "我的自选"));
  }

  @Test
  void sameKeyWithADifferentBodyIsRejected() {
    guard.execute(SCOPE, USER_ID, "key-1", new Request("我的自选"), CreatedValue.class,
        () -> new CreatedValue("7001", "我的自选"));
    AtomicInteger executions = new AtomicInteger();

    assertThatThrownBy(() -> guard.execute(
        SCOPE, USER_ID, "key-1", new Request("另一个名字"), CreatedValue.class, () -> {
          executions.incrementAndGet();
          return new CreatedValue("7002", "另一个名字");
        }))
        .isInstanceOf(IdempotencyKeyConflictException.class);
    assertThat(executions).hasValue(0);
  }

  @Test
  void differentKeysAreIndependent() {
    AtomicInteger executions = new AtomicInteger();

    guard.execute(SCOPE, USER_ID, "key-1", new Request("A"), CreatedValue.class, () -> {
      executions.incrementAndGet();
      return new CreatedValue("1", "A");
    });
    guard.execute(SCOPE, USER_ID, "key-2", new Request("B"), CreatedValue.class, () -> {
      executions.incrementAndGet();
      return new CreatedValue("2", "B");
    });

    assertThat(executions).hasValue(2);
  }

  @Test
  void sameKeyInADifferentScopeOrForADifferentUserIsIndependent() {
    AtomicInteger executions = new AtomicInteger();

    guard.execute(SCOPE, USER_ID, "key-1", new Request("A"), CreatedValue.class, () -> {
      executions.incrementAndGet();
      return new CreatedValue("1", "A");
    });
    guard.execute("ai-task:create", USER_ID, "key-1", new Request("A"), CreatedValue.class, () -> {
      executions.incrementAndGet();
      return new CreatedValue("2", "A");
    });
    guard.execute(SCOPE, 42L, "key-1", new Request("A"), CreatedValue.class, () -> {
      executions.incrementAndGet();
      return new CreatedValue("3", "A");
    });

    assertThat(executions).hasValue(3);
  }

  record Request(String groupName) {
  }

  record CreatedValue(String groupId, String groupName) {
  }

  private static final class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, IdempotencyRecord> records = new HashMap<>();

    @Override
    public Optional<IdempotencyRecord> find(String scope, long userId, String key) {
      return Optional.ofNullable(records.get(scope + ":" + userId + ":" + key));
    }

    @Override
    public void save(String scope, long userId, String key, IdempotencyRecord record) {
      records.put(scope + ":" + userId + ":" + key, record);
    }

    Map<String, IdempotencyRecord> saved() {
      return records;
    }
  }
}
