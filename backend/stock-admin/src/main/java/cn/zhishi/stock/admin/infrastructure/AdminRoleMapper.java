package cn.zhishi.stock.admin.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code sys_role} 及与它相关的计数的 SQL（契约 §16.2 ADM-ROL-01 + §16.1 的超管判定）。
 *
 * <h2>为什么超管判定在 SQL 里而不是 Java 里过滤</h2>
 * "启用中的超级管理员有几个"如果先把用户与角色关系拉进内存再数，就会随用户量线性变慢，
 * 而且与"目标是不是超管"分成两次读，中间可能有人刚被删。这里两次查询都直接问数据库，
 * 并且 {@code SuperAdminRule} 用 {@code ≤ 1} 兜住"已经一个都不剩"的夹缝。
 *
 * <h2>计数只数未删除的</h2>
 * 逻辑删除的用户与角色都不算：{@code deleted} 是遗留反向语义（1 = 未删除），
 * 漏掉它会让"已删除的超管"继续顶住保护判定，于是最后一个真超管反而删不掉。
 */
@Mapper
public interface AdminRoleMapper {

    String COLUMNS = """
            r.id          AS roleId,
            r.name        AS name,
            r.description AS description,
            r.status      AS status,
            (SELECT COUNT(*) FROM sys_user_role ur
               JOIN sys_user u ON u.id = ur.user_id AND u.deleted = 1
              WHERE ur.role_id = r.id)                                AS userCount,
            (SELECT COUNT(*) FROM sys_role_permission rp
              WHERE rp.role_id = r.id)                                AS permissionCount,
            r.version     AS version
            """;

    String FILTER = """
            <where>
              r.deleted = 1
              <if test="keyword != null and keyword != ''">
                AND (r.name LIKE CONCAT('%', #{keyword}, '%')
                     OR r.description LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null">
                AND r.status = #{status}
              </if>
            </where>
            """;

    @Select("<script>SELECT " + COLUMNS + " FROM sys_role r" + FILTER
            + " ORDER BY r.id LIMIT #{limit} OFFSET #{offset}</script>")
    List<RoleRow> pageRows(
            @Param("keyword") String keyword,
            @Param("status") Integer status,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("<script>SELECT COUNT(*) FROM sys_role r" + FILTER + "</script>")
    long countRows(@Param("keyword") String keyword, @Param("status") Integer status);

    /** 按名称取启用中的角色 id；不存在时返回 {@code null}（MyBatis 把单列可空结果映射成 null）。 */
    @Select("""
            SELECT id FROM sys_role
            WHERE name = #{name} AND deleted = 1 AND status = 1
            LIMIT 1
            """)
    Long findRoleIdByName(@Param("name") String name);

    /**
     * 启用中的超级管理员总数。
     *
     * <p>角色本身也要启用：一个被停用的 ADMIN 角色不该继续让某个用户"算超管"，
     * 否则会出现"角色停了、保护还在"的僵局。
     */
    @Select("""
            SELECT COUNT(*)
            FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id = u.id AND ur.role_id = #{roleId}
            JOIN sys_role r ON r.id = ur.role_id AND r.deleted = 1 AND r.status = 1
            WHERE u.deleted = 1 AND u.status = 1
            """)
    long countActiveSuperAdmins(@Param("roleId") long roleId);

    @Select("""
            SELECT COUNT(*)
            FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id = u.id AND ur.role_id = #{roleId}
            JOIN sys_role r ON r.id = ur.role_id AND r.deleted = 1 AND r.status = 1
            WHERE u.id = #{userId} AND u.deleted = 1 AND u.status = 1
            """)
    int isActiveSuperAdmin(
            @Param("userId") long userId, @Param("roleId") long roleId);
}
