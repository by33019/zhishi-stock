package cn.zhishi.stock.ai.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 一次大模型调用（{@code ai_usage}）。
 *
 * <h2>与 {@code usedCount} 是两个口径，各自只有一处</h2>
 * {@link #taskId()} + {@link #attemptNo()} 决定"一条调用"，而配额里的 {@code usedCount}
 * 数的是**当日创建的任务行数**。两者刻意不等：
 * <ul>
 *   <li>额度在**创建时**扣减（{@code guardQuota} 拦在创建之前）。若改成"按成功计"，
 *       用户可以让任务反复失败来绕过额度，而每一次失败都真实消耗了供应商调用。</li>
 *   <li>同一任务内的自动重试（{@code attempt_no + 1}）**不**额外扣额度——
 *       它是系统重试，不是新的用户意图；但它产生的每一次调用都记一行 {@code ai_usage}。</li>
 *   <li>用户主动重试（AI-07）与追问（AI-08）会创建**新任务行**，各自扣一次额度。</li>
 * </ul>
 * 契约 USER-07 说"实际 Provider 失败不计成功次数"——那句话说明 {@code usedCount}
 * 不是"调用成功次数"，而调用成功次数正是本表 {@link #resultStatus()} 的职责。
 * 两条口径都保留，且各自只由一处产出。
 *
 * <h2>{@code estimatedCost} 写 0</h2>
 * 工程里**没有**任何单价配置（{@code stock.ai.*} 下一项与价格有关的都没有）。
 * 没有价格表就填一个"看起来合理"的单价，会让成本看板上出现一个精确而错误的数字，
 * 而它无从被证伪。写 0 是数据库该列的默认值，含义明确是"未计价"；
 * 接入价格表之后应在这里按 {@code provider_code + model_code} 查表。
 */
public record AiUsage(
        long usageId,
        long taskId,
        int attemptNo,
        long userId,
        String providerCode,
        String modelCode,
        String providerRequestId,
        LlmUsage usage,
        BigDecimal estimatedCost,
        String currencyCode,
        Integer firstChunkLatencyMs,
        Integer totalLatencyMs,
        AiUsageResultStatus resultStatus,
        LlmErrorCategory errorCategory,
        OffsetDateTime callStartedAt,
        OffsetDateTime callCompletedAt,
        OffsetDateTime createdAt) {

    /** 未计价时的成本占位值，与 {@code ai_usage.estimated_cost} 的列默认值一致。 */
    public static final BigDecimal UNPRICED = BigDecimal.ZERO;

    public static final String DEFAULT_CURRENCY = "CNY";

    public AiUsage {
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo 从 1 开始：" + attemptNo);
        }
        if (usage == null) {
            usage = LlmUsage.EMPTY;
        }
        if (estimatedCost == null) {
            estimatedCost = UNPRICED;
        }
        if (currencyCode == null || currencyCode.isBlank()) {
            currencyCode = DEFAULT_CURRENCY;
        }
        if (resultStatus == null) {
            throw new IllegalArgumentException("resultStatus 不得为空");
        }
        if (callStartedAt == null) {
            throw new IllegalArgumentException("callStartedAt 不得为空");
        }
        if (callCompletedAt != null && callCompletedAt.isBefore(callStartedAt)) {
            // 与 ck_ai_usage_period 同口径。这里先拦，是为了让违反它的调用在单测里报错，
            // 而不是等到真库集成测试才撞 CHECK 约束。
            throw new IllegalArgumentException(
                    "callCompletedAt 不得早于 callStartedAt：" + callCompletedAt + " < " + callStartedAt);
        }
        if (resultStatus == AiUsageResultStatus.SUCCESS && errorCategory != null) {
            throw new IllegalArgumentException("成功的调用不该有 errorCategory：" + errorCategory);
        }
    }

    /**
     * 一次**成功**的调用的用量行。
     *
     * @param callStartedAt 调用发起前取的时刻。不能用 {@code completion} 的延迟反推起点——
     *                      反推会丢掉"从发起到供应商开始响应"的那段，而那段正是限流与
     *                      网络问题最先表现出来的地方。
     */
    public static AiUsage success(
            long usageId,
            AiTask task,
            LlmCompletion completion,
            OffsetDateTime callStartedAt,
            OffsetDateTime now) {
        return new AiUsage(
                usageId,
                task.taskId(),
                task.attemptNo(),
                task.userId(),
                task.providerCode(),
                completion.modelCode(),
                completion.providerRequestId(),
                completion.usage(),
                UNPRICED,
                DEFAULT_CURRENCY,
                millisOf(completion.firstChunkLatency()),
                millisOf(completion.totalLatency()),
                AiUsageResultStatus.SUCCESS,
                null,
                callStartedAt,
                callStartedAt.plus(completion.totalLatency()),
                now);
    }

    /**
     * 一次**失败**的调用的用量行。
     *
     * <p>{@code firstChunkLatencyMs} 留空而不是填 {@code totalLatencyMs}：首段从未到达，
     * "首段延迟"没有取值。把它和总延迟填成同一个数，会让看板上"首段延迟"看起来正常，
     * 而实际是每次失败都被记成"首段慢"。
     *
     * <p>{@code totalLatencyMs} 记录到失败为止的实际耗时——这是真实信息，
     * 也是判断"是秒失败还是耗到超时"的唯一依据。
     */
    public static AiUsage failure(
            long usageId,
            AiTask task,
            LlmErrorCategory category,
            OffsetDateTime callStartedAt,
            OffsetDateTime callCompletedAt,
            OffsetDateTime now) {
        return new AiUsage(
                usageId,
                task.taskId(),
                task.attemptNo(),
                task.userId(),
                task.providerCode(),
                task.modelCode(),
                null,
                LlmUsage.EMPTY,
                UNPRICED,
                DEFAULT_CURRENCY,
                null,
                (int) Math.max(0, Duration.between(callStartedAt, callCompletedAt).toMillis()),
                AiUsageResultStatus.FAILURE,
                category,
                callStartedAt,
                callCompletedAt,
                now);
    }

    private static Integer millisOf(Duration duration) {
        return duration == null ? null : (int) Math.max(0, duration.toMillis());
    }
}
