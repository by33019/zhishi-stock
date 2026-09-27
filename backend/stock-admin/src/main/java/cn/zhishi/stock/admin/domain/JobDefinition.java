package cn.zhishi.stock.admin.domain;

import java.util.List;

/**
 * 白名单任务定义（契约 §16.3 ADM-JOB-01 的返回字段）。
 *
 * <h2>为什么是白名单，而不是"列出所有 @Scheduled"</h2>
 * 契约原文写着"不直接暴露任意 Handler 调用能力"。反射列出所有定时方法意味着
 * 任何人在某个类上加一个 {@code @Scheduled} 都会自动变成一个**管理员可以通过 HTTP 触发**的能力——
 * 一次不小心（比如为一次性数据修复临时加的方法忘了删）就变成线上后门。
 * 白名单把"能被人工触发的任务"与"存在的任务"分成两件事，只有前者需要人工写进去。
 *
 * <h2>三个契约外字段</h2>
 * {@code scopeKeyLabel} / {@code allowedScopeKeys} / {@code defaultScopeKey} 描述该任务
 * 接受什么样的 {@code scopeKey}。契约把 {@code scopeKey} 列为可选请求参数却没规定取值范围，
 * 于是"合法取值"只能由任务自己回答：行情采集的作用范围是市场代码，资讯采集没有这个概念。
 * 不带这三个字段，后台就只能把任意字符串透传下去，然后写出一个不存在的市场的快照。
 *
 * @param jobName             稳定业务任务名（{@code JobNames}）
 * @param displayName         界面名称
 * @param handlerName         承载逻辑的用例方法
 * @param scheduleDescription 调度描述（人类可读；本项目用 fixedDelay 而非 cron）
 * @param supportsManualTrigger 是否允许人工触发
 * @param supportsShard       是否支持分片
 * @param enabled             是否启用（停用后不接受人工触发）
 * @param scopeKeyLabel       作用范围参数在界面上的名称；{@code null} 表示该任务不接受它
 * @param allowedScopeKeys    可接受的作用范围取值；空表示不接受该参数
 * @param defaultScopeKey     未传时的默认作用范围；不接受该参数时为 {@code null}
 */
public record JobDefinition(
        String jobName,
        String displayName,
        String handlerName,
        String scheduleDescription,
        boolean supportsManualTrigger,
        boolean supportsShard,
        boolean enabled,
        String scopeKeyLabel,
        List<String> allowedScopeKeys,
        String defaultScopeKey) {

    public JobDefinition {
        allowedScopeKeys = List.copyOf(allowedScopeKeys);
        if (defaultScopeKey != null && !allowedScopeKeys.contains(defaultScopeKey)) {
            throw new IllegalArgumentException(
                    jobName + " 的默认作用范围不在允许集合内：" + defaultScopeKey);
        }
        if (allowedScopeKeys.isEmpty() && scopeKeyLabel != null) {
            throw new IllegalArgumentException(
                    jobName + " 声明了作用范围名称却不接受任何取值");
        }
    }

    /** 是否接受 {@code scopeKey} 参数。 */
    public boolean acceptsScopeKey() {
        return !allowedScopeKeys.isEmpty();
    }

    /** 是否可以现在被人工触发。 */
    public boolean manuallyTriggerable() {
        return enabled && supportsManualTrigger;
    }
}
