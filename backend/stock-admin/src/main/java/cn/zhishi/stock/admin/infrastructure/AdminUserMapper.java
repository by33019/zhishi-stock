package cn.zhishi.stock.admin.infrastructure;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code sys_user} / {@code sys_user_role} 的 SQL（契约 §16.1）。
 *
 * <h2>所有查询都带 {@code deleted = 1}</h2>
 * 遗留语义是 {@code 1 = 未删除}。这个条件不是可选的：漏掉一次，被删除的用户
 * 就会重新出现在后台列表里，而它不会有任何报错。因此它写在 {@link #FILTER} 一处，
 * 分页与计数共用同一个片段——两处各写一遍时，其中一个漏了会表现为"总数对、列表不对"。
 *
 * <h2>所有更新都要求 {@code version}</h2>
 * {@code WHERE id = ? AND deleted = 1 AND version = ?}，并把 {@code version = version + 1}。
 * 这既是契约 §3.7 的乐观锁，也是用例层推演版本链的依据。
 * 写回 0 行只有两种可能：行没了、或版本不匹配，由用例层回读后区分。
 *
 * <h2>枚举不传给 Mapper</h2>
 * {@code sys_user.status} 是 {@code tinyint}（1/2），而 MyBatis 默认的
 * {@code EnumTypeHandler} 按 {@code name()} 转换——直接传枚举会写入 'ACTIVE'。
 * 因此参数一律是 {@code Integer}/{@code String}，枚举仅在仓储里转换。
 */
@Mapper
public interface AdminUserMapper {

    String COLUMNS = """
            id             AS userId,
            username       AS username,
            password       AS password,
            email          AS email,
            phone          AS phone,
            real_name      AS realName,
            nick_name      AS nickName,
            status         AS status,
            token_version  AS tokenVersion,
            create_where   AS createWhere,
            create_time    AS createTime,
            update_time    AS updateTime,
            last_login_time AS lastLoginTime,
            version        AS version
            """;

    /** 分页与计数共用的过滤条件。 */
    String FILTER = """
            <where>
              deleted = 1
              <if test="keyword != null and keyword != ''">
                AND (username LIKE CONCAT('%', #{keyword}, '%')
                     OR nick_name LIKE CONCAT('%', #{keyword}, '%')
                     OR real_name LIKE CONCAT('%', #{keyword}, '%')
                     OR email LIKE CONCAT('%', #{keyword}, '%')
                     OR phone LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null">
                AND status = #{status}
              </if>
              <if test="roleId != null">
                AND id IN (SELECT user_id FROM sys_user_role WHERE role_id = #{roleId})
              </if>
              <if test="createdStartAt != null">
                AND create_time &gt;= #{createdStartAt}
              </if>
              <if test="createdEndAt != null">
                AND create_time &lt;= #{createdEndAt}
              </if>
            </where>
            """;

    @Select("<script>SELECT " + COLUMNS + " FROM sys_user" + FILTER
            + " ORDER BY create_time DESC, id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<AdminUserRow> pageRows(
            @Param("keyword") String keyword,
            @Param("status") Integer status,
            @Param("roleId") Long roleId,
            @Param("createdStartAt") LocalDateTime createdStartAt,
            @Param("createdEndAt") LocalDateTime createdEndAt,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("<script>SELECT COUNT(*) FROM sys_user" + FILTER + "</script>")
    long countRows(
            @Param("keyword") String keyword,
            @Param("status") Integer status,
            @Param("roleId") Long roleId,
            @Param("createdStartAt") LocalDateTime createdStartAt,
            @Param("createdEndAt") LocalDateTime createdEndAt);

    /**
     * 一次取回整页用户的角色。
     *
     * <p>不是"每人查一次"：一页 20 人就是 21 次往返，而这类 N+1 在本地库上
     * 快得看不出来，只在真实网络里才暴露。
     */
    @Select("""
            <script>
            SELECT ur.user_id AS userId, r.id AS roleId, r.name AS name
            FROM sys_user_role ur
            JOIN sys_role r ON r.id = ur.role_id AND r.deleted = 1
            WHERE ur.user_id IN
            <foreach item="id" collection="userIds" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY ur.user_id, r.id
            </script>
            """)
    List<AdminUserRoleRow> findRoles(@Param("userIds") Collection<Long> userIds);

    @Select("SELECT " + COLUMNS + " FROM sys_user WHERE id = #{userId} AND deleted = 1")
    AdminUserRow find(@Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM sys_user WHERE username = #{username} AND deleted = 1")
    int countByUsername(@Param("username") String username);

    /**
     * 邮箱是否已被占用。
     *
     * <p>比较用的是 V2 生成的 {@code active_email}（未删除行才有值），
     * 与唯一索引 {@code uk_sys_user_email} 完全同口径——用 {@code email} 会比索引更宽，
     * 于是"这里说没占用、插进去却撞唯一键"。
     */
    @Select("""
            SELECT COUNT(*) FROM sys_user
            WHERE active_email = #{email} AND deleted = 1 AND id <> #{excludingUserId}
            """)
    int countByEmail(@Param("email") String email, @Param("excludingUserId") long excludingUserId);

    @Insert("""
            INSERT INTO sys_user
              (id, username, password, nick_name, real_name, email, phone, status,
               token_version, create_id, update_id, create_where, create_time, update_time, version)
            VALUES
              (#{userId}, #{username}, #{password}, #{nickName}, #{realName}, #{email}, #{phone},
               #{status}, 0, #{operatorId}, #{operatorId}, #{createWhere}, NOW(), NOW(), 0)
            """)
    void insertUser(
            @Param("userId") long userId,
            @Param("username") String username,
            @Param("password") String passwordHash,
            @Param("nickName") String nickName,
            @Param("realName") String realName,
            @Param("email") String email,
            @Param("phone") String phone,
            @Param("status") int status,
            @Param("createWhere") int createWhere,
            @Param("operatorId") long operatorId);

    @Insert("""
            INSERT INTO sys_user_role (id, user_id, role_id, create_time)
            VALUES (#{id}, #{userId}, #{roleId}, NOW())
            """)
    void insertUserRole(
            @Param("id") long id, @Param("userId") long userId, @Param("roleId") long roleId);

    /**
     * 改资料。
     *
     * <p>{@code COALESCE(#{x}, 列)} 实现"不传就不改"：PATCH 语义下
     * {@code null} 表示"本次不动这一项"，而 {@code COALESCE} 让这个判断留在 SQL 一处，
     * 不必在 Java 侧拼两套 UPDATE。
     */
    @Update("""
            UPDATE sys_user SET
              nick_name   = COALESCE(#{nickName}, nick_name),
              real_name   = COALESCE(#{realName}, real_name),
              email       = COALESCE(#{email}, email),
              phone       = COALESCE(#{phone}, phone),
              update_id   = #{operatorId},
              update_time = NOW(),
              version     = version + 1
            WHERE id = #{userId} AND deleted = 1 AND version = #{expectedVersion}
            """)
    int updateProfile(
            @Param("userId") long userId,
            @Param("nickName") String nickName,
            @Param("realName") String realName,
            @Param("email") String email,
            @Param("phone") String phone,
            @Param("expectedVersion") int expectedVersion,
            @Param("operatorId") long operatorId);

    @Update("""
            UPDATE sys_user SET
              status      = #{status},
              update_id   = #{operatorId},
              update_time = NOW(),
              version     = version + 1
            WHERE id = #{userId} AND deleted = 1 AND version = #{expectedVersion}
            """)
    int updateStatus(
            @Param("userId") long userId,
            @Param("status") int status,
            @Param("expectedVersion") int expectedVersion,
            @Param("operatorId") long operatorId);

    /**
     * 递增令牌版本。
     *
     * <p>同时递增 {@code version}：它是 If-Match 的依据，而"会话被撤销"本身就是
     * 资源状态的一次变化，不递增会让别人拿着旧版本号再改一次。
     */
    @Update("""
            UPDATE sys_user SET
              token_version = token_version + 1,
              update_id     = #{operatorId},
              update_time   = NOW(),
              version       = version + 1
            WHERE id = #{userId} AND deleted = 1 AND version = #{expectedVersion}
            """)
    int bumpTokenVersion(
            @Param("userId") long userId,
            @Param("expectedVersion") int expectedVersion,
            @Param("operatorId") long operatorId);

    @Update("DELETE FROM sys_user_role WHERE user_id = #{userId}")
    int deleteUserRoles(@Param("userId") long userId);

    /** 逻辑删除（契约 §16.1 ADM-USR-09 的反向语义：{@code deleted} 置为 0）。 */
    @Update("""
            UPDATE sys_user SET
              deleted     = 0,
              update_id   = #{operatorId},
              update_time = NOW(),
              version     = version + 1
            WHERE id = #{userId} AND deleted = 1 AND version = #{expectedVersion}
            """)
    int softDelete(
            @Param("userId") long userId,
            @Param("expectedVersion") int expectedVersion,
            @Param("operatorId") long operatorId);

    /**
     * 过滤出**有效**的角色 id（未删除且启用）。
     *
     * <p>返回有效的那些、而不是无效的那些，是为了让空列表（"全部有效"）与
     * "请求里一个角色都没有"在调用方看起来一样——两者都无需报错。
     */
    @Select("""
            <script>
            SELECT id FROM sys_role
            WHERE deleted = 1 AND status = 1 AND id IN
            <foreach item="id" collection="roleIds" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Long> findValidRoleIds(@Param("roleIds") Collection<Long> roleIds);
}
