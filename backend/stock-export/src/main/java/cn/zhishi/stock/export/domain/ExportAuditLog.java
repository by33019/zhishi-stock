package cn.zhishi.stock.export.domain;

/**
 * 审计端口。
 *
 * <p>**审计失败不能让业务失败**：契约 §22.3 要求写 {@code sys_log}，
 * 但没有哪一条要求"日志表不可用时拒绝导出"。所以实现方应当吞掉写日志的异常并告警
 * （这与认证、幂等键的处置不同——那两者的失败会改变业务事实）。
 */
public interface ExportAuditLog {

    void record(ExportAuditEvent event);
}
