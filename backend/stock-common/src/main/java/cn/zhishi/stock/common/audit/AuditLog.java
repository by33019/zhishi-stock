package cn.zhishi.stock.common.audit;

/**
 * 审计写入端口。
 *
 * <p>**审计失败不能让业务失败**：契约 §22.3 要求写 {@code sys_log}，
 * 但没有哪一条要求"日志表不可用时拒绝业务操作"。所以实现方应当吞掉写库异常并告警
 * （这与认证、幂等键的处置不同——那两者的失败会改变业务事实）。
 *
 * <p>放在 common 而不是各业务域：`sys_log` 是一张表、一种行格式，
 * 多个域各声明一个同名端口只会在组合根里出现 N 个内容相同的 Bean。
 */
public interface AuditLog {

    void record(AuditEvent event);
}
