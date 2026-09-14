package cn.zhishi.stock.system.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class MyBatisUserAccountRepositoryTest {

  @Test
  void mapsLegacyUserStatusAndRbacPermissions() {
    SysUserMapper mapper = mock(SysUserMapper.class);
    when(mapper.findByUsername("demo"))
        .thenReturn(new SysUserRecord(1001L, "demo", "bcrypt", "演示用户", 1, 7));
    when(mapper.findPermissions(1001L))
        .thenReturn(List.of("market:read", "watchlist:read", "market:read"));
    var repository = new MyBatisUserAccountRepository(mapper);

    var account = repository.findByUsername("demo").orElseThrow();

    assertThat(account.status()).isEqualTo(UserAccount.Status.ACTIVE);
    assertThat(account.tokenVersion()).isEqualTo(7);
    assertThat(repository.findPermissions(1001L))
        .containsExactly("market:read", "watchlist:read");
  }
}
