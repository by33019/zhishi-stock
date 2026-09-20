package cn.zhishi.stock.backend.config;

import cn.zhishi.stock.system.watchlist.WatchlistGroupService;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile({"dev", "test"})
public class DevelopmentAccountSeeder implements CommandLineRunner {

    private static final long ROLE_ID = 9_900_000_000_001L;
    private static final long PERMISSION_ID = 9_900_000_000_002L;
    private static final long USER_ID = 9_900_000_000_003L;
    private static final long USER_ROLE_ID = 9_900_000_000_004L;
    private static final long ROLE_PERMISSION_ID = 9_900_000_000_005L;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final WatchlistGroupService watchlistGroupService;
    private final String username;
    private final String password;

    public DevelopmentAccountSeeder(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            WatchlistGroupService watchlistGroupService,
            @Value("${stock.seed.demo-username:demo}") String username,
            @Value("${stock.seed.demo-password:Stock@123}") String password) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.watchlistGroupService = watchlistGroupService;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(String... args) {
        jdbc.update("""
                INSERT INTO sys_user
                  (id, username, password, nick_name, status, deleted, create_where, create_time, update_time)
                VALUES (?, ?, ?, '开发测试用户', 1, 1, 1, NOW(), NOW())
                ON DUPLICATE KEY UPDATE
                  password = VALUES(password), nick_name = VALUES(nick_name),
                  status = 1, deleted = 1, update_time = NOW()
                """, USER_ID, username, passwordEncoder.encode(password));
        Long actualUserId = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, username));

        jdbc.update("""
                INSERT INTO sys_role (id, name, description, status, deleted, create_time, update_time)
                VALUES (?, 'USER', '开发测试用户角色', 1, 1, NOW(), NOW())
                ON DUPLICATE KEY UPDATE status = 1, deleted = 1, update_time = NOW()
                """, ROLE_ID);
        jdbc.update("""
                INSERT INTO sys_permission
                  (id, code, title, perms, type, status, deleted, create_time, update_time)
                VALUES (?, 'user:self:read', '读取本人信息', 'user:self:read', 3, 1, 1, NOW(), NOW())
                ON DUPLICATE KEY UPDATE status = 1, deleted = 1, update_time = NOW()
                """, PERMISSION_ID);
        jdbc.update("""
                INSERT INTO sys_user_role (id, user_id, role_id, create_time)
                VALUES (?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE user_id = VALUES(user_id), role_id = VALUES(role_id)
                """, USER_ROLE_ID, actualUserId, ROLE_ID);
        jdbc.update("""
                INSERT INTO sys_role_permission (id, role_id, permission_id, create_time)
                VALUES (?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE role_id = VALUES(role_id), permission_id = VALUES(permission_id)
                """, ROLE_PERMISSION_ID, ROLE_ID, PERMISSION_ID);

        // 契约 §12.3：注册完成后创建且仅创建一个默认分组。
        // AUTH-03 注册流程尚未实现，这里让 dev / test 环境的数据与"注册之后"一致；
        // AUTH-03 落地时调用同一个方法即可，不需要另写一份。
        watchlistGroupService.createDefaultGroup(actualUserId);
    }
}
