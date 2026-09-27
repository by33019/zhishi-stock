package cn.zhishi.stock.admin.domain;

import java.util.List;
import java.util.Optional;

/**
 * 操作日志的读取端口（{@code sys_log}）。
 *
 * <h2>只读，没有写入方法</h2>
 * 写入走 {@code cn.zhishi.stock.common.audit.AuditLog}（P1 已铺好）。
 * 在这里再开一个 {@code append}，会让同一个写操作有两条路径，
 * 而"审计到底记在哪"这个问题的答案就取决于调用方记得用哪个。
 *
 * <h2>为什么 count 与 page 分开</h2>
 * 与其它列表一致：总数要展示在分页器上，而它不该被迫把整页数据也读一遍。
 * 调用方在 {@code total == 0} 时可以完全跳过 {@code page}。
 */
public interface OperationLogStore {

    /** 一页日志，按 {@code create_time} 倒序；{@code query} 的时间范围必须是已解析的。 */
    List<OperationLogEntry> page(OperationLogQuery query);

    /** 同一组过滤条件下的总条数（不受分页限制）。 */
    long count(OperationLogQuery query);

    /** 单条日志详情；不存在或已被清理时为空。 */
    Optional<OperationLogDetail> find(long logId);
}
