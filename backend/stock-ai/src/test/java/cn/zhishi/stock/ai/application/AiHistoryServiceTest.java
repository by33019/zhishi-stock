package cn.zhishi.stock.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zhishi.stock.ai.domain.AiContextTarget;
import cn.zhishi.stock.ai.domain.AiMessage;
import cn.zhishi.stock.ai.domain.AiMessageRole;
import cn.zhishi.stock.ai.domain.AiMessageStore;
import cn.zhishi.stock.ai.domain.AiReport;
import cn.zhishi.stock.ai.domain.AiReportQuality;
import cn.zhishi.stock.ai.domain.AiReportStore;
import cn.zhishi.stock.ai.domain.AiScene;
import cn.zhishi.stock.ai.domain.AiSession;
import cn.zhishi.stock.ai.domain.AiSessionQuery;
import cn.zhishi.stock.ai.domain.AiSessionStore;
import cn.zhishi.stock.ai.domain.AiSessionSummary;
import cn.zhishi.stock.ai.domain.AiTargetRole;
import cn.zhishi.stock.ai.domain.AiTargetType;
import cn.zhishi.stock.ai.domain.AiTask;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * HIS-01 / HIS-05 的业务规则。
 *
 * <h2>这里钉的是"查询条件真的传下去了"</h2>
 * 分页与筛选的参数在这一层被解析、校验、换算成 offset，然后交给仓储。
 * 若只断言"返回了列表"，那么把 offset 算错（例如漏了 -1）或把关键字丢掉
 * 都不会被发现——而这两种错误的表象都是"列表看起来正常，只是内容不对"。
 */
class AiHistoryServiceTest {

    private static final long USER_ID = 1001L;
    private static final long OTHER_USER = 1002L;
    private static final long SESSION_ID = 6001L;
    private static final long TASK_ID = 7001L;

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-22T15:00:00+08:00");

    private final AiSessionStore sessions = mock(AiSessionStore.class);
    private final AiMessageStore messages = mock(AiMessageStore.class);
    private final AiTaskStore tasks = mock(AiTaskStore.class);
    private final AiReportStore reports = mock(AiReportStore.class);
    private final AiTargetHydrator targetHydrator = mock(AiTargetHydrator.class);

    private final AiHistoryService service =
            new AiHistoryService(sessions, messages, tasks, reports, targetHydrator);

    // ---------- HIS-01：会话历史列表 ----------

    @Test
    @DisplayName("列表映射字段，lastTaskId 为空时 lastTask 也是 null（而不是空对象）")
    void mapsSummaryFieldsAndLeavesLastTaskNullWhenNeverRan() {
        when(sessions.countByUser(eq(USER_ID), any())).thenReturn(2);
        when(sessions.listByUser(eq(USER_ID), any(), eq(0), eq(20)))
                .thenReturn(List.of(ranSession(), freshSession()));

        var page = service.listSessions(USER_ID, null, null, null, null, null, null, null);

        assertThat(page.items()).hasSize(2);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(page.hasNext()).isFalse();

        var ran = page.items().get(0);
        assertThat(ran.sessionId()).isEqualTo("6001");
        assertThat(ran.scene()).isEqualTo(AiScene.STOCK);
        assertThat(ran.favorite()).isTrue();
        assertThat(ran.lastTask()).isNotNull();
        assertThat(ran.lastTask().taskId()).isEqualTo("7001");
        assertThat(ran.lastTask().status()).isEqualTo("COMPLETED");

        // 从未跑过任务：lastTask 必须是 null，前端据此渲染"还没有分析"
        assertThat(page.items().get(1).lastTask()).isNull();
    }

    @Test
    @DisplayName("页码换算成 offset：第 3 页每页 10 条 → offset 20")
    void convertsPageToOffset() {
        when(sessions.countByUser(anyLong(), any())).thenReturn(100);
        when(sessions.listByUser(anyLong(), any(), anyInt(), anyInt())).thenReturn(List.of());

        var page = service.listSessions(USER_ID, null, null, null, null, null, 3, 10);

        verify(sessions).listByUser(USER_ID, AiSessionQuery.unfiltered(), 20, 10);
        assertThat(page.page()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(10);
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    @DisplayName("筛选条件原样传给仓储：场景解析、关键字裁剪、时间与收藏保留")
    void passesFiltersThrough() {
        when(sessions.countByUser(anyLong(), any())).thenReturn(0);

        service.listSessions(USER_ID, "stock", "  银行  ", Boolean.TRUE, NOW, NOW, null, null);

        ArgumentCaptor<AiSessionQuery> captor = ArgumentCaptor.forClass(AiSessionQuery.class);
        verify(sessions).countByUser(eq(USER_ID), captor.capture());
        AiSessionQuery query = captor.getValue();
        assertThat(query.scene()).isEqualTo(AiScene.STOCK);
        assertThat(query.keyword()).isEqualTo("银行");
        assertThat(query.favorite()).isTrue();
        assertThat(query.startAt()).isEqualTo(NOW);
        assertThat(query.endAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("空白关键字按「不筛选」处理，而不是匹配空串")
    void blankKeywordBecomesNoFilter() {
        when(sessions.countByUser(anyLong(), any())).thenReturn(0);

        service.listSessions(USER_ID, null, "   ", null, null, null, null, null);

        verify(sessions).countByUser(USER_ID, AiSessionQuery.unfiltered());
    }

    @Test
    @DisplayName("页码越界 / 每页条数越界 / 场景码未知 / 关键字过长 → 400，且不查仓储")
    void rejectsInvalidParameters() {
        assertThatThrownBy(() -> service.listSessions(USER_ID, null, null, null, null, null, 0, null))
                .isInstanceOf(InvalidAiHistoryQueryException.class).hasMessageContaining("页码从 1 开始");
        assertThatThrownBy(() -> service.listSessions(USER_ID, null, null, null, null, null, null, 101))
                .isInstanceOf(InvalidAiHistoryQueryException.class).hasMessageContaining("每页条数");
        assertThatThrownBy(() -> service.listSessions(USER_ID, "NOT_A_SCENE", null, null, null, null, null, null))
                .isInstanceOf(InvalidAiHistoryQueryException.class).hasMessageContaining("不支持的场景码");
        assertThatThrownBy(() -> service.listSessions(USER_ID, null, "字".repeat(51), null, null, null, null, null))
                .isInstanceOf(InvalidAiHistoryQueryException.class).hasMessageContaining("关键字不得超过");

        verify(sessions, never()).listByUser(anyLong(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("页码超出总页数 → 400；但第 1 页在没有任何数据时返回空页而不是报错")
    void rejectsPageBeyondTotalButAllowsEmptyFirstPage() {
        when(sessions.countByUser(anyLong(), any())).thenReturn(5);
        assertThatThrownBy(() -> service.listSessions(USER_ID, null, null, null, null, null, 2, 20))
                .isInstanceOf(InvalidAiHistoryQueryException.class).hasMessageContaining("页码超出范围");

        when(sessions.countByUser(anyLong(), any())).thenReturn(0);
        assertThat(service.listSessions(USER_ID, null, null, null, null, null, 1, 20).items()).isEmpty();
    }

    // ---------- HIS-02：会话详情 ----------

    @Test
    @DisplayName("详情带出目标、最近任务与报告摘要，且目标经 hydrator 还原对外标识")
    void detailCarriesTargetsTaskAndReport() {
        when(sessions.find(SESSION_ID)).thenReturn(Optional.of(session("ACTIVE", USER_ID)));
        when(tasks.find(TASK_ID)).thenReturn(Optional.of(completedTask()));
        when(targetHydrator.hydrate(any())).thenReturn(List.of(hydratedTarget()));
        when(reports.findByTask(TASK_ID)).thenReturn(Optional.of(validReport()));

        AiSessionDetail detail = service.getSession(SESSION_ID, USER_ID);

        assertThat(detail.sessionId()).isEqualTo("6001");
        assertThat(detail.favorite()).isFalse();
        assertThat(detail.version()).isEqualTo(1);
        // 目标必须过 hydrator：库里只有 bigint 代理键，直出会让前端跳转主键解析不了
        verify(targetHydrator).hydrate(any());
        assertThat(detail.targets()).hasSize(1);
        assertThat(detail.targets().get(0).targetId()).isEqualTo("sim-600519");
        assertThat(detail.lastTask()).isNotNull();
        assertThat(detail.lastTask().status()).isEqualTo(AiTaskStatus.COMPLETED);
        assertThat(detail.lastTask().reportId()).isEqualTo("8001");
        assertThat(detail.lastReport()).isNotNull();
        assertThat(detail.lastReport().reportId()).isEqualTo("8001");
        assertThat(detail.lastReport().qualityStatus()).isEqualTo("VALID");
    }

    @Test
    @DisplayName("从未跑过任务：目标为空数组、两个摘要都是 null，且不去查任务表")
    void detailWithoutAnyTask() {
        when(sessions.find(SESSION_ID)).thenReturn(Optional.of(session("ACTIVE", USER_ID, null)));

        AiSessionDetail detail = service.getSession(SESSION_ID, USER_ID);

        assertThat(detail.targets()).isEmpty();
        assertThat(detail.lastTask()).isNull();
        assertThat(detail.lastReport()).isNull();
        verify(tasks, never()).find(anyLong());
    }

    @Test
    @DisplayName("last_task_id 悬空（数据不一致）：按「没有任务」处理，不抛错也不报 500")
    void detailToleratesDanglingLastTask() {
        when(sessions.find(SESSION_ID)).thenReturn(Optional.of(session("ACTIVE", USER_ID)));
        when(tasks.find(TASK_ID)).thenReturn(Optional.empty());

        AiSessionDetail detail = service.getSession(SESSION_ID, USER_ID);

        assertThat(detail.lastTask()).isNull();
        assertThat(detail.lastReport()).isNull();
    }

    @Test
    @DisplayName("任务完成但报告缺失（失败/取消/超时）：lastReport 为 null，lastTask 仍带出")
    void detailWithoutReportStillCarriesTask() {
        when(sessions.find(SESSION_ID)).thenReturn(Optional.of(session("ACTIVE", USER_ID)));
        when(tasks.find(TASK_ID)).thenReturn(Optional.of(completedTask()));
        when(targetHydrator.hydrate(any())).thenReturn(List.of());
        when(reports.findByTask(TASK_ID)).thenReturn(Optional.empty());

        AiSessionDetail detail = service.getSession(SESSION_ID, USER_ID);

        assertThat(detail.lastTask()).isNotNull();
        assertThat(detail.lastTask().reportId()).isNull();
        assertThat(detail.lastReport()).isNull();
    }

    @Test
    @DisplayName("不属于本人的会话：404，且不查任务与报告")
    void detailRequiresOwnership() {
        when(sessions.find(SESSION_ID)).thenReturn(Optional.of(session("ACTIVE", OTHER_USER)));

        assertThatThrownBy(() -> service.getSession(SESSION_ID, USER_ID))
                .isInstanceOf(AiTaskException.class);

        verify(tasks, never()).find(anyLong());
        verify(reports, never()).findByTask(anyLong());
    }

    // ---------- HIS-05：会话消息 ----------

    @Test
    @DisplayName("消息按仓储返回顺序投影，ID 为字符串")
    void mapsMessages() {
        when(sessions.find(SESSION_ID)).thenReturn(Optional.of(session("ACTIVE", USER_ID)));
        when(messages.countVisible(SESSION_ID)).thenReturn(2);
        when(messages.listVisible(SESSION_ID, 0, 20))
                .thenReturn(List.of(userMessage(), assistantMessage()));

        var page = service.messages(SESSION_ID, USER_ID, null, null);

        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).roleType()).isEqualTo(AiMessageRole.USER);
        assertThat(page.items().get(0).messageId()).isEqualTo("5001");
        assertThat(page.items().get(0).taskId()).isEqualTo("7001");
        assertThat(page.items().get(1).roleType()).isEqualTo(AiMessageRole.ASSISTANT);
        assertThat(page.items().get(1).dataCutoffAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("会话不存在 / 属于别人 / 已软删 → 同一句 404，且不查消息表")
    void unreadableSessionsAreIndistinguishableAndNotQueried() {
        when(sessions.find(SESSION_ID)).thenReturn(Optional.empty());
        AiTaskException missing = catchException(() -> service.messages(SESSION_ID, USER_ID, null, null));

        when(sessions.find(SESSION_ID)).thenReturn(Optional.of(session("ACTIVE", OTHER_USER)));
        AiTaskException notMine = catchException(() -> service.messages(SESSION_ID, USER_ID, null, null));

        // 软删按"不存在"处理：列表里看不到、详情却拿得到，等于把删除做成后门
        when(sessions.find(SESSION_ID)).thenReturn(Optional.of(session("DELETED", USER_ID)));
        AiTaskException deleted = catchException(() -> service.messages(SESSION_ID, USER_ID, null, null));

        assertThat(notMine.code()).isEqualTo(missing.code()).isEqualTo(deleted.code());
        assertThat(notMine.getMessage()).isEqualTo(missing.getMessage());
        assertThat(deleted.getMessage()).isEqualTo(missing.getMessage());
        assertThat(missing.code().externalCode()).isEqualTo("AI_SESSION_NOT_FOUND");
        verify(messages, never()).listVisible(anyLong(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("消息分页同样拒绝越界")
    void messagesRejectPageBeyondRange() {
        when(sessions.find(SESSION_ID)).thenReturn(Optional.of(session("ACTIVE", USER_ID)));
        when(messages.countVisible(SESSION_ID)).thenReturn(1);

        assertThatThrownBy(() -> service.messages(SESSION_ID, USER_ID, 5, 20))
                .isInstanceOf(InvalidAiHistoryQueryException.class).hasMessageContaining("页码超出范围");
    }

    private static AiTaskException catchException(Runnable call) {
        try {
            call.run();
        } catch (AiTaskException exception) {
            return exception;
        }
        throw new AssertionError("期望抛出 AiTaskException，但没有抛出");
    }

    private static AiSessionSummary ranSession() {
        return new AiSessionSummary(
                SESSION_ID, USER_ID, AiScene.STOCK, "贵州茅台分析", "ACTIVE", true,
                TASK_ID, "COMPLETED", NOW, NOW, 3);
    }

    private static AiSessionSummary freshSession() {
        return new AiSessionSummary(
                6002L, USER_ID, AiScene.MARKET, "市场解读", "ACTIVE", false,
                null, null, NOW, NOW, 0);
    }

    private static AiSession session(String status, long userId) {
        return session(status, userId, TASK_ID);
    }

    /** {@code lastTaskId} 为 {@code null} 表示"从未跑过任务"。 */
    private static AiSession session(String status, long userId, Long lastTaskId) {
        return new AiSession(
                SESSION_ID, userId, AiScene.STOCK, "贵州茅台分析", status, false,
                lastTaskId, NOW, 1, NOW);
    }

    /**
     * 一个终态任务。
     *
     * <p>库里的目标只有 bigint 代理键（{@code 600519L}），对外标识由 hydrator 还原——
     * 这里刻意存代理键，才能证明服务层真的调了 hydrator。
     */
    private static AiTask completedTask() {
        return new AiTask(
                TASK_ID, "req-7001", SESSION_ID, USER_ID, null, AiScene.STOCK,
                "这只股票怎么样？", null, null, AiTaskStatus.COMPLETED, false, 1, 2,
                "DASHSCOPE", "qwen3.8-max-0902", "trace-7001",
                NOW, NOW, NOW, NOW, NOW, NOW, NOW, NOW,
                null, null, null, 2,
                List.of(new AiContextTarget(
                        AiTargetType.SECURITY, null, "600519", "模拟证券600519",
                        AiTargetRole.PRIMARY, 600519L)));
    }

    private static AiContextTarget hydratedTarget() {
        return new AiContextTarget(
                AiTargetType.SECURITY, "sim-600519", "600519", "模拟证券600519",
                AiTargetRole.PRIMARY, 600519L);
    }

    private static AiReport validReport() {
        return new AiReport(
                8001L, TASK_ID, SESSION_ID, 5002L,
                "核心结论", "量价依据", null, null, "风险", "不构成投资建议。",
                "# 报告", AiReportQuality.VALID, null, "v1", "p2",
                "DASHSCOPE", "qwen3.8-max-0902", NOW, NOW, "a".repeat(64), NOW);
    }

    private static AiMessage userMessage() {
        return new AiMessage(5001L, SESSION_ID, TASK_ID, AiMessageRole.USER, 1, "这只股票怎么样？", null, NOW);
    }

    private static AiMessage assistantMessage() {
        return new AiMessage(5002L, SESSION_ID, TASK_ID, AiMessageRole.ASSISTANT, 2, "# 报告", NOW, NOW);
    }
}
