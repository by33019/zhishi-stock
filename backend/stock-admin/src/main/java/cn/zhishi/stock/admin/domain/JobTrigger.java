package cn.zhishi.stock.admin.domain;

/**
 * 交给执行体的任务参数（契约 §16.3 ADM-JOB-02 的请求体，加上操作者）。
 *
 * <h2>为什么把"操作者"也传下去</h2>
 * 执行体本身不需要知道是谁点的按钮——审计由 Web 层写。但采集类任务经常需要在
 * 失败摘要里说清"这是人工触发的"，而失败摘要是在执行体里生成的。
 * 传一个 {@code operatorId} 比让执行体反过来去问"我这次是不是人工跑的"要直接。
 * 定时触发时它是 0（没有操作者），而不是某个占位的真实用户 ID。
 *
 * @param jobName    任务名（已在白名单内）
 * @param scopeKey   作用范围；任务不接受该参数时为 {@code null}，接受了但未传时是默认值
 * @param providerId 关联的外部 Provider；本平台暂无 Provider 元数据，通常为 {@code null}
 * @param shardTotal 分片总数；目前所有任务都是单分片
 * @param reason     触发/重试原因（人工填写，可空）
 * @param operatorId 触发者用户 ID；定时触发为 0
 */
public record JobTrigger(
        String jobName,
        String scopeKey,
        Long providerId,
        int shardTotal,
        String reason,
        long operatorId) {

    public JobTrigger {
        if (shardTotal < 1) {
            throw new IllegalArgumentException("shardTotal 必须 >= 1");
        }
        if (operatorId < 0) {
            throw new IllegalArgumentException("operatorId 不能为负");
        }
    }

    /** 是否是人工触发的（定时任务的操作者为 0）。 */
    public boolean byOperator() {
        return operatorId > 0;
    }
}
