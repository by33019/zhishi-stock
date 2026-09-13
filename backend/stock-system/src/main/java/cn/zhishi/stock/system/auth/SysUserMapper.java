package cn.zhishi.stock.system.auth;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysUserMapper {

    @Select("""
            SELECT id,
                   username,
                   password AS passwordHash,
                   COALESCE(NULLIF(nick_name, ''), NULLIF(real_name, ''), username) AS displayName,
                   status
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
                   status
            FROM sys_user
            WHERE id = #{userId} AND deleted = 1
            LIMIT 1
            """)
    SysUserRecord findById(@Param("userId") long userId);

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
