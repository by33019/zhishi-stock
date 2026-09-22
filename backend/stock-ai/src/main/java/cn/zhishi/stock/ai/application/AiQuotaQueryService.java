package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * AI 配额查询（契约 USER-07；AI-03 / AI-05 的 {@code quota} 字段也走这里）。
 *
 * <h2>为什么把配额从 {@code AiTaskService} 里抽出来</h2>
 * 它有两个调用方，分属两侧：写侧的 {@code AiTaskService}（创建前拦额度、创建后回填响应）
 * 与读侧的 {@code CurrentUserController}（USER-07）。留在 {@code AiTaskService} 里，
 * 读接口就得依赖整个"创建任务"用例——那是十二个依赖，其中大半与"读一个数字"无关。
 *
 * <p>抽出来还有一个更重要的收益：**口径只有这一处**。这个文件之前，
 * "当日额度怎么算"散在守卫、响应组装与私有重载三处；三处各自改一次是分叉的经典成因，
 * 而分叉的表现是"前端显示的剩余次数与实际能不能提交对不上"，不会报错。
 *
 * <h2>它是纯读的</h2>
 * 不做任何写、不加锁、不占额度。创建时真正的扣减发生在
 * {@code AiTaskService.create} 里——额度是"任务行数"，插入任务行本身就是扣减，
 * 不需要额外的计数表（多一张表就多一份可能与事实不一致的状态）。
 */
public class AiQuotaQueryService {

    private final AiTaskStore tasks;
    private final Clock clock;
    private final int dailyTaskLimit;
    private final int maxConcurrentTasks;

    public AiQuotaQueryService(
            AiTaskStore tasks, Clock clock, int dailyTaskLimit, int maxConcurrentTasks) {
        this.tasks = tasks;
        this.clock = clock;
        this.dailyTaskLimit = dailyTaskLimit;
        this.maxConcurrentTasks = maxConcurrentTasks;
    }

    /** 契约 USER-07：当前用户当日的配额与并发占用。 */
    public AiTaskQuota quotaOf(long userId) {
        return quotaOf(userId, OffsetDateTime.now(clock));
    }

    /**
     * 以指定时刻为准的配额。
     *
     * <p>同一次请求里多处要读配额时（如创建任务：先拦额度、再回填响应）用这个重载，
     * 保证 {@code usedCount} 与 {@code resetsAt} 出自同一时刻——用两次
     * {@code now()} 会让它们在跨零点的那一次请求里各自算出不同的日期。
     */
    public AiTaskQuota quotaOf(long userId, OffsetDateTime now) {
        OffsetDateTime dayStart = dayStartOf(now);
        int used = tasks.countCreatedSince(userId, dayStart);
        int running = tasks.countByUserAndStatuses(userId, AiTaskStatus.activeStatuses());
        return AiTaskQuota.of(
                dayStart.toLocalDate(), dailyTaskLimit, used, running, maxConcurrentTasks,
                dayStart.plusDays(1));
    }

    /** 单用户并发上限（创建前的并发闸门用它，不必为此读一次计数）。 */
    public int concurrentLimit() {
        return maxConcurrentTasks;
    }

    /** 当日额度起点：{@code Asia/Shanghai} 自然日零点。 */
    public OffsetDateTime dayStartOf(OffsetDateTime now) {
        ZoneId zone = clock.getZone();
        return now.atZoneSameInstant(zone).toLocalDate().atStartOfDay(zone).toOffsetDateTime();
    }
}
