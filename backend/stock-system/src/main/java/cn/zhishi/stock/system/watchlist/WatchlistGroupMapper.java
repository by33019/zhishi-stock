package cn.zhishi.stock.system.watchlist;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code user_watchlist_group} / {@code user_watchlist_item} 的 SQL。
 *
 * <p>两点刻意的写法：
 *
 * <ul>
 *   <li><b>乐观锁写在 {@code WHERE} 里</b>，而不是"先查版本再写"。MySQL 在 REPEATABLE READ 下
 *       UPDATE 走当前读，因此并发时 {@code WHERE version = ?} 才是唯一权威；
 *       先查后比会读到快照里的旧版本，写出"检查通过、覆盖别人修改"的结果。
 *   <li><b>每个条件更新都带 {@code user_id}</b>：契约 §12.3 要求所有路径资源校验归属，
 *       把归属写进 WHERE 就再也漏不掉。
 * </ul>
 */
@Mapper
public interface WatchlistGroupMapper {

    @Select("""
            SELECT g.id          AS groupId,
                   g.user_id     AS userId,
                   g.group_name  AS groupName,
                   g.sort_no     AS sortNo,
                   g.is_default  AS isDefault,
                   g.version     AS version,
                   (SELECT COUNT(*) FROM user_watchlist_item item
                     WHERE item.user_id = g.user_id AND item.group_id = g.id) AS itemCount
            FROM user_watchlist_group g
            WHERE g.user_id = #{userId} AND g.deleted_at IS NULL
            ORDER BY g.sort_no, g.id
            """)
    List<WatchlistGroupRow> findActiveByUser(@Param("userId") long userId);

    @Select("""
            SELECT g.id          AS groupId,
                   g.user_id     AS userId,
                   g.group_name  AS groupName,
                   g.sort_no     AS sortNo,
                   g.is_default  AS isDefault,
                   g.version     AS version,
                   (SELECT COUNT(*) FROM user_watchlist_item item
                     WHERE item.user_id = g.user_id AND item.group_id = g.id) AS itemCount
            FROM user_watchlist_group g
            WHERE g.user_id = #{userId} AND g.id = #{groupId} AND g.deleted_at IS NULL
            """)
    WatchlistGroupRow findActive(
            @Param("userId") long userId, @Param("groupId") long groupId);

    @Select("""
            SELECT COALESCE(MAX(sort_no), -1) + 1
            FROM user_watchlist_group
            WHERE user_id = #{userId} AND deleted_at IS NULL
            """)
    int nextSortNo(@Param("userId") long userId);

    /** {@code created_at} / {@code updated_at} 交给列默认值，避免应用侧做时区换算。 */
    @Insert("""
            INSERT INTO user_watchlist_group
              (id, user_id, group_name, sort_no, is_default, version)
            VALUES (#{groupId}, #{userId}, #{groupName}, #{sortNo}, #{isDefault}, 0)
            """)
    void insert(
            @Param("groupId") long groupId,
            @Param("userId") long userId,
            @Param("groupName") String groupName,
            @Param("sortNo") int sortNo,
            @Param("isDefault") boolean isDefault);

    @Update("""
            UPDATE user_watchlist_group
               SET group_name = #{groupName}, version = version + 1
             WHERE id = #{groupId} AND user_id = #{userId}
               AND deleted_at IS NULL AND version = #{expectedVersion}
            """)
    int rename(
            @Param("userId") long userId,
            @Param("groupId") long groupId,
            @Param("groupName") String groupName,
            @Param("expectedVersion") int expectedVersion);

    @Update("""
            UPDATE user_watchlist_group
               SET deleted_at = NOW(3), version = version + 1
             WHERE id = #{groupId} AND user_id = #{userId}
               AND deleted_at IS NULL AND version = #{expectedVersion}
            """)
    int softDelete(
            @Param("userId") long userId,
            @Param("groupId") long groupId,
            @Param("expectedVersion") int expectedVersion);

    @Update("""
            UPDATE user_watchlist_group
               SET sort_no = #{sortNo}, version = version + 1
             WHERE id = #{groupId} AND user_id = #{userId} AND deleted_at IS NULL
            """)
    int updateSortNo(
            @Param("userId") long userId,
            @Param("groupId") long groupId,
            @Param("sortNo") int sortNo);

    /**
     * 目标组已有的同证券：把源组里的重复项删掉（等价于合并）。
     *
     * <p>必须先删再搬，否则第 2 步的 UPDATE 会撞上
     * {@code uk_watchlist_item_group_security(group_id, security_id)}。
     * 用 {@code JOIN} 而不是 {@code WHERE security_id IN (SELECT … FROM 同一张表)}：
     * MySQL 不允许在 DELETE 的子查询里引用被删的表（ERROR 1093）。
     */
    @Delete("""
            DELETE src FROM user_watchlist_item src
              JOIN user_watchlist_item dst
                ON dst.user_id = src.user_id
               AND dst.group_id = #{targetGroupId}
               AND dst.security_id = src.security_id
             WHERE src.user_id = #{userId} AND src.group_id = #{sourceGroupId}
            """)
    int deleteItemsAlreadyInTarget(
            @Param("userId") long userId,
            @Param("sourceGroupId") long sourceGroupId,
            @Param("targetGroupId") long targetGroupId);

    @Update("""
            UPDATE user_watchlist_item
               SET group_id = #{targetGroupId}, version = version + 1, updated_at = NOW(3)
             WHERE user_id = #{userId} AND group_id = #{sourceGroupId}
            """)
    int moveItems(
            @Param("userId") long userId,
            @Param("sourceGroupId") long sourceGroupId,
            @Param("targetGroupId") long targetGroupId);
}
