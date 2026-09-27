package cn.zhishi.stock.system.auth;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SysUserMapper {

    @Select("""
            SELECT id,
                   username,
                   password AS passwordHash,
                   COALESCE(NULLIF(nick_name, ''), NULLIF(real_name, ''), username) AS displayName,
                   status,
                   token_version AS tokenVersion
            FROM sys_user
            WHERE username = #{username} AND deleted = 1
            LIMIT 1
            """)
    SysUserRecord findByUsername(@Param("username") String username);

    @Select("""
            SELECT id,
                   username,
                   password AS passwordHash,
                   COALESCE(NULLIF(nick_name, ''), NULLIF(real_name, ''), username) AS displayName,
                   status,
                   token_version AS tokenVersion
            FROM sys_user
            WHERE id = #{userId} AND deleted = 1
            LIMIT 1
            """)
    SysUserRecord findById(@Param("userId") long userId);

    /** active_email 是 V2 的生成列（由 email 归一而来），用它找人才不会漏掉大小写变体。 */
    @Select("""
            SELECT id,
                   username,
                   password AS passwordHash,
                   COALESCE(NULLIF(nick_name, ''), NULLIF(real_name, ''), username) AS displayName,
                   status,
                   token_version AS tokenVersion
            FROM sys_user
            WHERE active_email = #{email} AND deleted = 1
            LIMIT 1
            """)
    SysUserRecord findByEmail(@Param("email") String email);

    @Update("""
            UPDATE sys_user
            SET password = #{passwordHash},
                token_version = token_version + 1,
                version = version + 1,
                update_time = NOW()
            WHERE id = #{userId} AND deleted = 1
            """)
    int updatePasswordHashBumpingTokenVersion(
            @Param("userId") long userId,
            @Param("passwordHash") String passwordHash);

    @Select("""
            SELECT DISTINCT COALESCE(NULLIF(permission.perms, ''), permission.code)
            FROM sys_user_role user_role
            JOIN sys_role role
              ON role.id = user_role.role_id AND role.status = 1 AND role.deleted = 1
            JOIN sys_role_permission role_permission
              ON role_permission.role_id = role.id
            JOIN sys_permission permission
              ON permission.id = role_permission.permission_id
             AND permission.status = 1 AND permission.deleted = 1
            WHERE user_role.user_id = #{userId}
            ORDER BY COALESCE(NULLIF(permission.perms, ''), permission.code)
            """)
    List<String> findPermissions(@Param("userId") long userId);
}
