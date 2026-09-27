package cn.zhishi.stock.admin.application;

/**
 * 人工触发任务的请求体（契约 §16.3 ADM-JOB-02）。
 *
 * <h2>四个字段全部可空，且都有明确后果</h2>
 * <ul>
 *   <li>{@code scopeKey}：只对声明了作用范围的任务有意义。对不接受它的任务传了值
 *       是**错误**而不是忽略——静默忽略会让调用方以为自己缩小了范围；</li>
 *   <li>{@code providerId}：本平台尚无 Provider 元数据（{@code external_provider} 零行），
 *       传了也只是记进执行记录，不改变行为；</li>
 *   <li>{@code shardTotal}：目前所有任务都是单分片。默认 1；传大于 1 的值会被记录，
 *       但执行体仍按单分片跑，因为分片需要每个分片各自被触发一次，
 *       而本接口一次只表示"一次执行"；</li>
 *   <li>{@code reason}：会进审计摘要。可空。</li>
 * </ul>
 *
 * <p>用包装类型而不是 {@code int}：{@code shardTotal} 缺失与"传了 0"必须能区分，
 * 前者落到默认值 1，后者是非法输入。
 */
public record TriggerJobCommand(
        String scopeKey,
        Long providerId,
        Integer shardTotal,
        String reason) {

    public int shardTotalOrDefault() {
        return shardTotal == null ? 1 : shardTotal;
    }
}
