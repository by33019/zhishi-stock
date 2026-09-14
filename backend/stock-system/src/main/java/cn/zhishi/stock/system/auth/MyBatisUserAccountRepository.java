package cn.zhishi.stock.system.auth;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public class MyBatisUserAccountRepository implements UserAccountRepository {

    private final SysUserMapper mapper;

    public MyBatisUserAccountRepository(SysUserMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return Optional.ofNullable(mapper.findByUsername(username)).map(this::toDomain);
    }

    @Override
    public Optional<UserAccount> findById(long userId) {
        return Optional.ofNullable(mapper.findById(userId)).map(this::toDomain);
    }

    @Override
    public Set<String> findPermissions(long userId) {
        return new LinkedHashSet<>(mapper.findPermissions(userId));
    }

    private UserAccount toDomain(SysUserRecord record) {
        UserAccount.Status status = switch (record.status()) {
            case 1 -> UserAccount.Status.ACTIVE;
            case 2 -> UserAccount.Status.LOCKED;
            default -> UserAccount.Status.DISABLED;
        };
        return new UserAccount(
                record.id(),
                record.username(),
                record.passwordHash(),
                status,
                record.displayName(),
                record.tokenVersion());
    }
}
