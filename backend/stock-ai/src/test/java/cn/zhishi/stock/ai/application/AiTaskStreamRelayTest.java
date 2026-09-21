package cn.zhishi.stock.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.ai.domain.AiReportSection;
import cn.zhishi.stock.ai.domain.AiScene;
import cn.zhishi.stock.ai.domain.AiTask;
import cn.zhishi.stock.ai.domain.AiTaskEvent;
import cn.zhishi.stock.ai.domain.AiTaskEventStream;
import cn.zhishi.stock.ai.domain.AiTaskEventType;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SSE 中继测试。
 *
 * <p>它守着三件**不会报错**的事：
 * <ol>
 *   <li><b>重连起点错位</b>：{@code Last-Event-ID} 没被当成游标，前端会重复拼接已经收到的片段，
 *       或者丢掉一段——两种都只是"文本看着不对"。
 *   <li><b>没有终点的连接</b>：流里没有 {@code done}（恢复扫描判的超时、或事件被保留期清理）
 *       时若不兜底，连接会一直挂着，前端一直转圈。
 *   <li><b>重连后从空白开始</b>：{@code snapshot} 不带 {@code partialContent}，
 *       用户会看到"刷新一下报告内容就没了"。
 * </ol>
 */
class AiTaskStreamRelayTest {

    private static final long TASK_ID = 8001L;

    private final FakeEventStream events = new FakeEventStream();
    private final StubTaskStore tasks = new StubTaskStore();

    private AiTaskStreamRelay relay() {
        return new AiTaskStreamRelay(events, tasks, 50, Duration.ZERO, Duration.ofSeconds(5), 200);
    }

    @Test
    @DisplayName("建连：先发 snapshot，再按序号补发，收到 done 立即收尾")
    void sendsSnapshotThenEventsAndStopsAtDone() {
        events.append(TASK_ID, AiTaskEventType.STATUS, sequence -> statusPayload(sequence));
        events.append(TASK_ID, AiTaskEventType.CHUNK, sequence -> chunkPayload(sequence, "第一段"));
        events.append(TASK_ID, AiTaskEventType.DONE, sequence -> donePayload(sequence));
        RecordingSink sink = new RecordingSink();

        relay().relay(summary(AiTaskStatus.COMPLETED), 0L, sink);

        assertThat(sink.snapshots).hasSize(1);
        assertThat(sink.snapshots.get(0))
                .describedAs("snapshot 必须带上 lastSequence，前端据此对齐游标")
                .contains("\"lastSequence\":3");
        assertThat(sink.types).containsExactly(
                AiTaskEventType.STATUS, AiTaskEventType.CHUNK, AiTaskEventType.DONE);
    }

    @Test
    @DisplayName("重连：Last-Event-ID=1 时只补发它之后的事件")
    void resumesFromLastEventId() {
        events.append(TASK_ID, AiTaskEventType.STATUS, sequence -> statusPayload(sequence));
        events.append(TASK_ID, AiTaskEventType.CHUNK, sequence -> chunkPayload(sequence, "第一段"));
        events.append(TASK_ID, AiTaskEventType.DONE, sequence -> donePayload(sequence));
        RecordingSink sink = new RecordingSink();

        relay().relay(summary(AiTaskStatus.COMPLETED), 1L, sink);

        assertThat(sink.types)
                .describedAs("序号 1 已经收到过，不该再补发")
                .containsExactly(AiTaskEventType.CHUNK, AiTaskEventType.DONE);
        assertThat(sink.sequences).containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("重连：snapshot 里的 partialContent 是已产生片段的完整渲染（六章节顺序）")
    void snapshotCarriesPartialContent() {
        events.append(TASK_ID, AiTaskEventType.CHUNK,
                sequence -> chunkPayload(sequence, AiReportSection.QUOTE_EVIDENCE, "量价依据"));
        events.append(TASK_ID, AiTaskEventType.CHUNK,
                sequence -> chunkPayload(sequence, AiReportSection.CORE_CONCLUSION, "核心结论"));
        RecordingSink sink = new RecordingSink();

        relay().relay(summary(AiTaskStatus.RUNNING), 2L, sink);

        assertThat(sink.snapshots.get(0))
                .describedAs("章节顺序由 AiReportText 定义，不是按到达顺序拼")
                .contains("## 核心结论")
                .contains("核心结论")
                .contains("## 行情与量价依据")
                .contains("量价依据");
        assertThat(sink.snapshots.get(0).indexOf("核心结论"))
                .isLessThan(sink.snapshots.get(0).indexOf("行情与量价依据"));
    }

    @Test
    @DisplayName("没有片段时不带 partialContent（空串会让前端清掉已渲染内容）")
    void omitsPartialContentWhenThereIsNone() {
        RecordingSink sink = new RecordingSink();

        relay().relay(summary(AiTaskStatus.RUNNING), 0L, sink);

        assertThat(sink.snapshots.get(0)).doesNotContain("partialContent");
    }

    @Test
    @DisplayName("客户端断开：立即停止，不再读事件")
    void stopsWhenClientDisconnects() {
        events.append(TASK_ID, AiTaskEventType.STATUS, sequence -> statusPayload(sequence));
        RecordingSink sink = new RecordingSink();
        sink.close();

        relay().relay(summary(AiTaskStatus.RUNNING), 0L, sink);

        assertThat(sink.snapshots).isEmpty();
        assertThat(sink.types).isEmpty();
    }

    /**
     * 兜底路径：任务已终态但流里没有 {@code done} 事件。
     *
     * <p>它对应真实情况——恢复扫描判的超时、或者事件被保留期清理过。
     * 少了这条兜底，连接会一直挂到最长时限，而前端一直在转圈。
     */
    @Test
    @DisplayName("任务已终态但流里没有 done：兜底收尾，不会挂住")
    void stopsWhenTaskIsTerminalWithoutDoneEvent() {
        events.append(TASK_ID, AiTaskEventType.STATUS, sequence -> statusPayload(sequence));
        RecordingSink sink = new RecordingSink();

        relay().relay(summary(AiTaskStatus.TIMED_OUT), 0L, sink);

        assertThat(sink.types).containsExactly(AiTaskEventType.STATUS);
    }

    /** 任务行读不到（已被清理）时同样收尾，不挂住。 */
    @Test
    @DisplayName("任务行读不到：按已结束处理，立即收尾")
    void stopsWhenTaskRowIsGone() {
        tasks.rows.clear();
        RecordingSink sink = new RecordingSink();

        relay().relay(summary(AiTaskStatus.RUNNING), 0L, sink);

        assertThat(sink.types).isEmpty();
    }

    // ---------- 夹具 ----------

    private static String statusPayload(long sequence) {
        return AiTaskEventPayloads.status(TASK_ID, AiTaskStatus.RUNNING, sequence);
    }

    private static String chunkPayload(long sequence, String delta) {
        return chunkPayload(sequence, AiReportSection.CORE_CONCLUSION, delta);
    }

    private static String chunkPayload(long sequence, AiReportSection section, String delta) {
        return AiTaskEventPayloads.chunk(TASK_ID, section, delta, sequence);
    }

    private static String donePayload(long sequence) {
        return AiTaskEventPayloads.done(TASK_ID, AiTaskStatus.COMPLETED, sequence);
    }

    private static AiTaskSummary summary(AiTaskStatus status) {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-20T10:00:00+08:00");
        return new AiTaskSummary(
                Long.toString(TASK_ID),
                "5001",
                AiScene.STOCK,
                status,
                List.of(),
                "怎么看",
                status.progressStage(),
                now,
                null,
                status.terminal() ? now : null,
                null,
                null);
    }

    /** 内存事件流：只实现中继用到的那三个方法。 */
    private static final class FakeEventStream implements AiTaskEventStream {

        private final Map<Long, List<AiTaskEvent>> byTask = new LinkedHashMap<>();

        @Override
        public AiTaskEvent append(
                long taskId, AiTaskEventType type, LongFunction<String> payloadBuilder) {
            List<AiTaskEvent> list = byTask.computeIfAbsent(taskId, key -> new ArrayList<>());
            long sequence = list.size() + 1L;
            AiTaskEvent event =
                    new AiTaskEvent(sequence, type, payloadBuilder.apply(sequence));
            list.add(event);
            return event;
        }

        @Override
        public List<AiTaskEvent> readAfter(long taskId, long lastSequence, int count) {
            return byTask.getOrDefault(taskId, List.of()).stream()
                    .filter(event -> event.sequence() > lastSequence)
                    .limit(count)
                    .toList();
        }

        @Override
        public Optional<Long> latestSequence(long taskId) {
            List<AiTaskEvent> list = byTask.getOrDefault(taskId, List.of());
            return list.isEmpty()
                    ? Optional.empty()
                    : Optional.of(list.get(list.size() - 1).sequence());
        }
    }

    /** 只需要 {@code find}；其余方法在中继里用不到。 */
    private static final class StubTaskStore implements AiTaskStore {

        final Map<Long, AiTask> rows = new LinkedHashMap<>();

        @Override
        public Optional<AiTask> find(long taskId) {
            return Optional.ofNullable(rows.get(taskId));
        }

        @Override
        public Optional<AiTask> findByRequestId(String requestId) {
            return Optional.empty();
        }

        @Override
        public void insert(AiTask task) {
        }

        @Override
        public Optional<AiTask> save(AiTask task) {
            return Optional.empty();
        }

        @Override
        public boolean claimForExecution(long taskId, OffsetDateTime at) {
            return false;
        }

        @Override
        public boolean requestCancel(long taskId) {
            return false;
        }

        @Override
        public List<AiTask> findByUserAndStatuses(long userId, List<AiTaskStatus> statuses) {
            return List.of();
        }

        @Override
        public int countByUserAndStatuses(long userId, List<AiTaskStatus> statuses) {
            return 0;
        }

        @Override
        public int countCreatedSince(long userId, OffsetDateTime from) {
            return 0;
        }

        @Override
        public List<AiTask> findRecoverable(
                OffsetDateTime queuedBefore, OffsetDateTime heartbeatBefore, int limit) {
            return List.of();
        }
    }

    private static final class RecordingSink implements AiTaskEventSink {

        final List<String> snapshots = new ArrayList<>();
        final List<AiTaskEventType> types = new ArrayList<>();
        final List<Long> sequences = new ArrayList<>();
        private boolean closed;

        @Override
        public void sendSnapshot(String dataJson) {
            snapshots.add(dataJson);
        }

        @Override
        public void send(AiTaskEvent event) {
            types.add(event.type());
            sequences.add(event.sequence());
        }

        @Override
        public boolean closed() {
            return closed;
        }

        void close() {
            closed = true;
        }
    }
}
