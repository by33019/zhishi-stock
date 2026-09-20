package cn.zhishi.stock.system.watchlist;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code user_watchlist_item} 的 SQL。
 *
 * <p>与 {@link WatchlistGroupMapper} 的分工：本类拥有自选项的**增删改查**；
 * 分组删除时的"整组搬迁 / 合并"仍留在那边（那是分组生命周期的一部分）。
 * 两边都写 {@code user_watchlist_item}，但语句集合不相交，没有第二份事实。
 *
 * <p>三点刻意的写法：
 *
 * <ul>
 *   <li><b>乐观锁写在 {@code WHERE} 里</b>（同 M3-01）：MySQL 在 REPEATABLE READ 下
 *       UPDATE 走当前读，{@code WHERE version = ?} 才是并发下的唯一权威。
 *   <li><b>每条语句都带 {@code user_id}</b>：契约 §12.3 要求校验归属，
 *       写进 WHERE 就再也漏不掉——尤其是"删别人的自选项"这类越权。
 *   <li><b>{@code created_at} 由应用显式写入</b>：WAT-06 / WAT-07 要把它回显给前端，
 *       而列默认值 {@code CURRENT_TIMESTAMP(3)} 的墙上时间取决于 MySQL 会话时区，
 *       读回来就必须猜一个时区。写入与回读因此都由应用的 {@code Clock} 掌握（见 {@link WatchlistItem}）。
 * </ul>
 */
@Mapper
public interface WatchlistItemMapper {

    @Select("""
            SELECT id          AS itemId,
                   user_id     AS userId,
                   group_id    AS groupId,
                   security_id AS securityId,
                   sort_no     AS sortNo,
                   version     AS version,
                   created_at  AS createdAt
            FROM user_watchlist_item
            WHERE user_id = #{userId} AND group_id = #{groupId}
            ORDER BY sort_no, id
            """)
    List<WatchlistItemRow> findByGroup(
            @Param("userId") long userId, @Param("groupId") long groupId);

    @Select("""
            SELECT id          AS itemId,
                   user_id     AS userId,
                   group_id    AS groupId,
                   security_id AS securityId,
                   sort_no     AS sortNo,
                   version     AS version,
                   created_at  AS createdAt
            FROM user_watchlist_item
            WHERE user_id = #{userId}
            ORDER BY group_id, sort_no, id
            """)
    List<WatchlistItemRow> findByUser(@Param("userId") long userId);

    @Select("""
            SELECT id          AS itemId,
                   user_id     AS userId,
                   group_id    AS groupId,
                   security_id AS securityId,
                   sort_no     AS sortNo,
                   version     AS version,
                   created_at  AS createdAt
            FROM user_watchlist_item
            WHERE user_id = #{userId} AND group_id = #{groupId} AND id = #{itemId}
            """)
    WatchlistItemRow find(
            @Param("userId") long userId,
            @Param("groupId") long groupId,
            @Param("itemId") long itemId);

    @Select("""
            SELECT id          AS itemId,
                   user_id     AS userId,
                   group_id    AS groupId,
                   security_id AS securityId,
                   sort_no     AS sortNo,
                   version     AS version,
                   created_at  AS createdAt
            FROM user_watchlist_item
            WHERE user_id = #{userId} AND group_id = #{groupId} AND security_id = #{securityId}
            """)
    WatchlistItemRow findBySecurity(
            @Param("userId") long userId,
            @Param("groupId") long groupId,
            @Param("securityId") long securityId);

    @Select("""
            SELECT COALESCE(MAX(sort_no), -1) + 1
            FROM user_watchlist_item
            WHERE user_id = #{userId} AND group_id = #{groupId}
            """)
    int nextSortNo(@Param("userId") long userId, @Param("groupId") long groupId);

    @Insert("""
            INSERT INTO user_watchlist_item
              (id, user_id, group_id, security_id, sort_no, version, created_at)
            VALUES
              (#{itemId}, #{userId}, #{groupId}, #{securityId}, #{sortNo}, 0, #{createdAt})
            """)
    void insert(
            @Param("itemId") long itemId,
            @Param("userId") long userId,
            @Param("groupId") long groupId,
            @Param("securityId") long securityId,
            @Param("sortNo") int sortNo,
            @Param("createdAt") LocalDateTime createdAt);

    @Delete("""
            DELETE FROM user_watchlist_item
             WHERE id = #{itemId} AND user_id = #{userId} AND group_id = #{groupId}
            """)
    int delete(
            @Param("userId") long userId,
            @Param("groupId") long groupId,
            @Param("itemId") long itemId);

    @Delete("""
            DELETE FROM user_watchlist_item
             WHERE id = #{itemId} AND user_id = #{userId} AND group_id = #{groupId}
               AND version = #{expectedVersion}
            """)
    int deleteIfVersion(
            @Param("userId") long userId,
            @Param("groupId") long groupId,
            @Param("itemId") long itemId,
            @Param("expectedVersion") int expectedVersion);

    @Update("""
            UPDATE user_watchlist_item
               SET group_id = #{targetGroupId}, sort_no = #{sortNo},
                   version = version + 1, updated_at = NOW(3)
             WHERE id = #{itemId} AND user_id = #{userId} AND group_id = #{sourceGroupId}
               AND version = #{expectedVersion}
            """)
    int moveToGroup(
            @Param("userId") long userId,
            @Param("sourceGroupId") long sourceGroupId,
            @Param("itemId") long itemId,
            @Param("targetGroupId") long targetGroupId,
            @Param("sortNo") int sortNo,
            @Param("expectedVersion") int expectedVersion);

    @Update("""
            UPDATE user_watchlist_item
               SET sort_no = #{sortNo}, version = version + 1, updated_at = NOW(3)
             WHERE id = #{itemId} AND user_id = #{userId} AND group_id = #{groupId}
               AND version = #{expectedVersion}
            """)
    int updateSortNo(
            @Param("userId") long userId,
            @Param("groupId") long groupId,
            @Param("itemId") long itemId,
            @Param("sortNo") int sortNo,
            @Param("expectedVersion") int expectedVersion);
}
