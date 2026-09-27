package cn.zhishi.stock.backend.config;

import cn.zhishi.stock.admin.application.AdminAiService;
import cn.zhishi.stock.admin.application.AdminNewsRelationService;
import cn.zhishi.stock.admin.application.AdminNewsSourceService;
import cn.zhishi.stock.admin.application.AdminRoleService;
import cn.zhishi.stock.admin.application.AdminUserService;
import cn.zhishi.stock.admin.application.JobAdminService;
import cn.zhishi.stock.admin.application.OperationLogService;
import cn.zhishi.stock.admin.application.SensitiveParamsRedactor;
import cn.zhishi.stock.admin.domain.AdminAiStatsStore;
import cn.zhishi.stock.admin.domain.AdminAiTaskStore;
import cn.zhishi.stock.admin.domain.AdminNewsRelationStore;
import cn.zhishi.stock.admin.domain.AdminNewsSourceStore;
import cn.zhishi.stock.admin.domain.AdminRoleStore;
import cn.zhishi.stock.admin.domain.AdminUserStore;
import cn.zhishi.stock.admin.domain.JobDefinitionCatalog;
import cn.zhishi.stock.admin.domain.JobExecutionDispatcher;
import cn.zhishi.stock.admin.domain.JobTaskExecutor;
import cn.zhishi.stock.admin.domain.OperationLogStore;
import cn.zhishi.stock.system.auth.PasswordResetCredentialStore;
import cn.zhishi.stock.system.auth.PasswordResetRedemptionService;
import cn.zhishi.stock.system.auth.UserAccountRepository;
import cn.zhishi.stock.admin.infrastructure.AdminAiStatsMapper;
import cn.zhishi.stock.admin.infrastructure.AdminAiTaskMapper;
import cn.zhishi.stock.admin.infrastructure.AdminNewsRelationMapper;
import cn.zhishi.stock.admin.infrastructure.AdminNewsSourceMapper;
import cn.zhishi.stock.admin.infrastructure.AdminRoleMapper;
import cn.zhishi.stock.admin.infrastructure.AdminUserMapper;
import cn.zhishi.stock.admin.infrastructure.MyBatisAdminAiStatsStore;
import cn.zhishi.stock.admin.infrastructure.MyBatisAdminAiTaskStore;
import cn.zhishi.stock.admin.infrastructure.MyBatisAdminNewsRelationStore;
import cn.zhishi.stock.admin.infrastructure.MyBatisAdminNewsSourceStore;
import cn.zhishi.stock.admin.infrastructure.MyBatisAdminRoleStore;
import cn.zhishi.stock.admin.infrastructure.MyBatisAdminUserStore;
import cn.zhishi.stock.admin.infrastructure.MyBatisOperationLogStore;
import cn.zhishi.stock.admin.infrastructure.OperationLogMapper;
import cn.zhishi.stock.admin.infrastructure.RedisPasswordResetCredentialStore;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import cn.zhishi.stock.backend.jobs.InProcessJobDispatcher;
import cn.zhishi.stock.backend.jobs.InProcessJobRunner;
import cn.zhishi.stock.export.application.ExportRetentionSweeper;
import cn.zhishi.stock.export.domain.ExportFileStore;
import cn.zhishi.stock.export.domain.ExportJobStore;
import cn.zhishi.stock.market.application.MarketIngestionService;
import cn.zhishi.stock.market.domain.MarketOverviewArchive;
import cn.zhishi.stock.market.domain.MarketOverviewStore;
import cn.zhishi.stock.market.domain.QuoteProvider;
import cn.zhishi.stock.market.domain.SectorIdentityProvider;
import cn.zhishi.stock.market.domain.SecurityIdentityProvider;
import cn.zhishi.stock.news.application.NewsIngestionService;
import cn.zhishi.stock.system.auth.RefreshSessionStore;
import cn.zhishi.stock.system.auth.SecureOpaqueTokenGenerator;
import cn.zhishi.stock.system.job.JobExecutionMapper;
import cn.zhishi.stock.system.job.JobExecutionRecorder;
import cn.zhishi.stock.system.job.JobExecutionStore;
import cn.zhishi.stock.system.job.MyBatisJobExecutionStore;
import cn.zhishi.stock.system.watchlist.WatchlistGroupService;
import java.time.Clock;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 后台管理面（M3-11）的装配。
 *
 * <h2>为什么单独一个配置类</h2>
 * {@code BackendConfiguration} 已经承载了全站的组合根。后台这一组 Bean 只被
 * {@code /api/v1/admin} 下的控制器使用，放在这里能让"后台需要什么"一眼可见，
 * 也不必在千行文件里找它们。扫描范围不变（{@code cn.zhishi.stock}），
 * 因此控制器仍然是自动发现的，不需要在这里逐个声明。
 *
 * <h2>控制器不在这里声明</h2>
 * {@code AdminUserController} 等是 {@code @RestController}，由组件扫描注册。
 * 手动声明会让同一个类有两个注册来源，其中一个改了构造参数就会启动失败，
 * 而报错信息指向的是配置类而不是控制器。
 */
@Configuration
public class AdminConfiguration {

    @Bean
    AdminUserStore adminUserStore(
            AdminUserMapper mapper, LongSupplier databaseIdGenerator, Clock clock) {
        return new MyBatisAdminUserStore(mapper, databaseIdGenerator, clock);
    }

    /**
     * 超管角色名从配置读。
     *
     * <p>默认 {@code ADMIN} 必须与 {@code V9__seed_admin_rbac.sql} 里播种的角色名一致：
     * 改名会让"最后一个超级管理员保护"静默失效（找不到角色 = 没人算超管），
     * 而那种失效没有任何报错，只会表现为"保护规则不生效"。
     */
    @Bean
    AdminRoleStore adminRoleStore(
            AdminRoleMapper mapper,
            @Value("${stock.admin.super-role-name:ADMIN}") String superRoleName) {
        return new MyBatisAdminRoleStore(mapper, superRoleName);
    }

    @Bean
    PasswordResetCredentialStore passwordResetCredentialStore(
            StringRedisTemplate redis, Clock clock) {
        return new RedisPasswordResetCredentialStore(redis, clock);
    }

    /**
     * AUTH-07 的兑换服务跟着凭证存储走（bean 在同一个配置类里，"签发"与"兑换"
     * 用的必须是同一份存储）——虽然它是认证域的服务，端点也是公开的。
     */
    @Bean
    PasswordResetRedemptionService passwordResetRedemptionService(
            UserAccountRepository userAccountRepository,
            PasswordResetCredentialStore passwordResetCredentialStore,
            RefreshSessionStore refreshSessionStore,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        return new PasswordResetRedemptionService(
                userAccountRepository,
                passwordResetCredentialStore,
                refreshSessionStore,
                passwordEncoder,
                clock);
    }

    @Bean
    AdminUserService adminUserService(
            AdminUserStore adminUserStore,
            AdminRoleStore adminRoleStore,
            RefreshSessionStore refreshSessionStore,
            PasswordResetCredentialStore passwordResetCredentialStore,
            WatchlistGroupService watchlistGroupService,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        return new AdminUserService(
                adminUserStore,
                adminRoleStore,
                refreshSessionStore,
                passwordResetCredentialStore,
                watchlistGroupService,
                passwordEncoder,
                // 与刷新令牌同一种生成器（256 位随机 + URL 安全编码），但**不共用实例**：
                // 凭证是短时的一次性秘密，与刷新令牌是两类东西，
                // 共用一个实例会让将来给其中一类换算法时被动影响另一类。
                new SecureOpaqueTokenGenerator(),
                clock);
    }

    @Bean
    AdminRoleService adminRoleService(AdminRoleStore adminRoleStore) {
        return new AdminRoleService(adminRoleStore);
    }

    @Bean
    OperationLogStore operationLogStore(OperationLogMapper mapper, Clock clock) {
        return new MyBatisOperationLogStore(mapper, clock);
    }

    /**
     * 脱敏器无状态，声明成 Bean 只是为了让用例层与测试拿到同一个实例。
     *
     * <p>它不依赖 {@code Clock}、不读配置，因此测试里可以直接 {@code new}——
     * 这也说明它为什么能有一个只测脱敏规则、不测分页与时间范围的独立测试类。
     */
    @Bean
    SensitiveParamsRedactor sensitiveParamsRedactor() {
        return new SensitiveParamsRedactor();
    }

    @Bean
    OperationLogService operationLogService(
            OperationLogStore operationLogStore,
            SensitiveParamsRedactor sensitiveParamsRedactor,
            Clock clock) {
        return new OperationLogService(operationLogStore, sensitiveParamsRedactor, clock);
    }

    // ---------------------------------------------------------------- 定时任务（ADM-JOB）

    @Bean
    JobExecutionStore jobExecutionStore(JobExecutionMapper mapper, Clock clock) {
        return new MyBatisJobExecutionStore(mapper, clock);
    }

    /**
     * 调度描述从三个 {@code @Scheduled} 用的**同一组配置键**读出来。
     *
     * <p>默认值必须与 {@code stock-job} 的 {@code ScheduledMarketCollector} /
     * {@code ScheduledNewsCollector} / {@code ScheduledExportSweeper} 保持一致，
     * 否则后台页面会长期显示一个与真实调度不同的周期。
     */
    @Bean
    JobDefinitionCatalog jobDefinitionCatalog(
            @Value("${stock.market.collect-delay-ms:60000}") long marketDelayMs,
            @Value("${stock.news.collect-delay-ms:120000}") long newsDelayMs,
            @Value("${stock.export.sweep-delay-ms:600000}") long exportDelayMs) {
        return new JobDefinitionCatalog(marketDelayMs, newsDelayMs, exportDelayMs);
    }

    @Bean
    JobExecutionRecorder jobExecutionRecorder(
            JobExecutionStore jobExecutionStore, LongSupplier databaseIdGenerator, Clock clock) {
        return new JobExecutionRecorder(jobExecutionStore, databaseIdGenerator, clock);
    }

    @Bean
    JobExecutionDispatcher jobExecutionDispatcher() {
        return new InProcessJobDispatcher();
    }

    /**
     * 行情采集与导出清理在 API 进程里原本没有装配——它们此前只在 {@code stock-job} 里跑。
     * 人工触发（ADM-JOB-02）要求 API 也能执行它们，因此这里补上两条装配链。
     *
     * <p>用的都是 {@code BackendConfiguration} 已经声明的端口（{@code QuoteProvider} /
     * {@code MarketOverviewStore} / {@code MarketOverviewArchive} / {@code ExportJobStore} /
     * {@code ExportFileStore}），**不新建第二份实现**：同一个 Redis 键、
     * 同一个导出目录，因此人工触发的采集与定时采集落到同一个地方。
     */
    @Bean
    MarketIngestionService marketIngestionService(
            QuoteProvider quoteProvider,
            MarketOverviewStore marketOverviewStore,
            MarketOverviewArchive marketOverviewArchive,
            Clock clock) {
        return new MarketIngestionService(
                quoteProvider, marketOverviewStore, marketOverviewArchive, clock);
    }

    @Bean
    ExportRetentionSweeper exportRetentionSweeper(
            ExportJobStore exportJobStore, ExportFileStore exportFileStore) {
        return new ExportRetentionSweeper(exportJobStore, exportFileStore);
    }

    @Bean
    JobTaskExecutor jobTaskExecutor(
            MarketIngestionService marketIngestionService,
            NewsIngestionService newsIngestionService,
            ExportRetentionSweeper exportRetentionSweeper,
            @Value("${stock.export.sweep-batch-size:200}") int sweepBatchSize) {
        return new InProcessJobRunner(
                marketIngestionService, newsIngestionService, exportRetentionSweeper, sweepBatchSize);
    }

    @Bean
    JobAdminService jobAdminService(
            JobDefinitionCatalog jobDefinitionCatalog,
            JobTaskExecutor jobTaskExecutor,
            JobExecutionStore jobExecutionStore,
            JobExecutionRecorder jobExecutionRecorder,
            JobExecutionDispatcher jobExecutionDispatcher,
            Clock clock) {
        return new JobAdminService(
                jobDefinitionCatalog,
                jobTaskExecutor,
                jobExecutionStore,
                jobExecutionRecorder,
                jobExecutionDispatcher,
                clock);
    }

    // ---------------------------------------------------------------- 资讯治理（ADM-NEWS）

    @Bean
    AdminNewsSourceStore adminNewsSourceStore(
            AdminNewsSourceMapper mapper, LongSupplier databaseIdGenerator, Clock clock) {
        return new MyBatisAdminNewsSourceStore(mapper, databaseIdGenerator, clock);
    }

    @Bean
    AdminNewsRelationStore adminNewsRelationStore(
            AdminNewsRelationMapper mapper, LongSupplier databaseIdGenerator, Clock clock) {
        return new MyBatisAdminNewsRelationStore(mapper, databaseIdGenerator, clock);
    }

    @Bean
    AdminNewsSourceService adminNewsSourceService(
            AdminNewsSourceStore adminNewsSourceStore, Clock clock) {
        return new AdminNewsSourceService(adminNewsSourceStore, clock);
    }

    /**
     * 关联目标的对外标识桥接复用行情域的身份端口（{@code BackendConfiguration} 已装配）：
     * 后台与前台资讯查询看到的是同一个 {@code sim-600519} 语义，不复述构词规则。
     */
    @Bean
    AdminNewsRelationService adminNewsRelationService(
            AdminNewsRelationStore adminNewsRelationStore,
            SecurityIdentityProvider securityIdentityProvider,
            SectorIdentityProvider sectorIdentityProvider,
            Clock clock) {
        return new AdminNewsRelationService(
                adminNewsRelationStore,
                securityIdentityProvider,
                sectorIdentityProvider,
                clock);
    }

    // ---------------------------------------------------------------- AI 运营（ADM-AI）

    @Bean
    AdminAiTaskStore adminAiTaskStore(AdminAiTaskMapper mapper, Clock clock) {
        return new MyBatisAdminAiTaskStore(mapper, clock);
    }

    @Bean
    AdminAiStatsStore adminAiStatsStore(AdminAiStatsMapper mapper, Clock clock) {
        return new MyBatisAdminAiStatsStore(mapper, clock);
    }

    /**
     * {@code AiTaskStore} 由 {@code BackendConfiguration} 装配（任务编排的主端口），
     * 后台的取消直接复用它的 {@code requestCancel}——原子置意图的语义只有一处。
     */
    @Bean
    AdminAiService adminAiService(
            AdminAiTaskStore adminAiTaskStore,
            AdminAiStatsStore adminAiStatsStore,
            AiTaskStore aiTaskStore,
            Clock clock) {
        return new AdminAiService(adminAiTaskStore, adminAiStatsStore, aiTaskStore, clock);
    }
}
