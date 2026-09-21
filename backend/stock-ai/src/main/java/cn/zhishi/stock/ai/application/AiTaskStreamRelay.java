package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiReportSection;
import cn.zhishi.stock.ai.domain.AiReportText;
import cn.zhishi.stock.ai.domain.AiTask;
import cn.zhishi.stock.ai.domain.AiTaskEvent;
import cn.zhishi.stock.ai.domain.AiTaskEventStream;
import cn.zhishi.stock.ai.domain.AiTaskEventType;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSE 中继（AI-05）。
 *
 * <h2>它只做三件事</h2>
 * <ol>
 *   <li>建连时发一条 {@code snapshot}：任务当前摘要 + 已经产生的临时文本
 *       （重连的客户端据此恢复"半份报告"，而不是从空白开始）；
 *   <li>把 {@code Last-Event-ID} 之后的事件按序号补发、再持续跟随；
 *   <li>收到终态事件（{@code done}）或客户端断开时收尾。
 * </ol>
 *
 * <h2>中继不是报告存储</h2>
 * 契约 §13.4 明确：临时片段可能因为最终结构、引用或安全校验失败而不形成报告，
 * 前端必须以 {@code report} 事件或任务 {@code COMPLETED} 为成功依据。
 * 所以这里只转发，不判断"这份内容算不算报告"。
 *
 * <h2>两条终止路径，缺一不可</h2>
 * <ul>
 *   <li><b>收到 {@code done} 事件</b>——正常路径。
 *   <li><b>流里没有 {@code done} 但任务已终态</b>——兜底路径。它对应两种真实情况：
 *       恢复扫描判的超时（那条路径只写 {@code error} + {@code done}，但如果它写事件时
 *       Redis 不可用就会漏掉），以及事件流被保留期清理。少了这条兜底，
 *       连接会一直挂到 {@code maxDuration} 才断，而前端一直在转圈。
 * </ul>
 *
 * <h2>为什么要 {@code maxDuration}</h2>
 * 连接是长连接，但没有"永远正确"的结束条件：任务可能因为某个未预料的路径停在非终态。
 * 给一个上界，最坏情况也只是"前端多一次重连"，而不是连接与线程永久泄漏。
 */
public class AiTaskStreamRelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiTaskStreamRelay.class);

    private final AiTaskEventStream events;
    private final AiTaskStore tasks;
    private final int batchSize;
    private final Duration pollInterval;
    private final Duration maxDuration;
    private final int partialContentScanLimit;

    public AiTaskStreamRelay(
            AiTaskEventStream events,
            AiTaskStore tasks,
            int batchSize,
            Duration pollInterval,
            Duration maxDuration,
            int partialContentScanLimit) {
        this.events = events;
        this.tasks = tasks;
        this.batchSize = batchSize;
        this.pollInterval = pollInterval;
        this.maxDuration = maxDuration;
        this.partialContentScanLimit = partialContentScanLimit;
    }

    /**
     * 阻塞地把事件推给 {@code sink}，直到终态、客户端断开或超过 {@code maxDuration}。
     *
     * @param task       已校验归属的任务摘要（中继不做鉴权）
     * @param lastSequence 客户端回传的 {@code Last-Event-ID}；首次连接传 0
     */
    public void relay(AiTaskSummary task, long lastSequence, AiTaskEventSink sink) {
        if (sink.closed()) {
            // 建连与写 snapshot 之间客户端可能已经走了（浏览器关闭、网络抖动）。
            // 先看一眼，省掉一次注定失败的写与一次必然无用的读。
            return;
        }
        long taskId = Long.parseLong(task.taskId());
        sink.sendSnapshot(AiTaskEventPayloads.snapshot(
                task, events.latestSequence(taskId).orElse(0L), partialContentOf(taskId)));

        long cursor = Math.max(0L, lastSequence);
        long deadlineNanos = System.nanoTime() + maxDuration.toNanos();
        while (!sink.closed()) {
            boolean done = false;
            for (AiTaskEvent event : events.readAfter(taskId, cursor, batchSize)) {
                sink.send(event);
                cursor = event.sequence();
                if (event.terminal()) {
                    done = true;
                    break;
                }
            }
            if (done || sink.closed()) {
                return;
            }
            if (terminal(taskId)) {
                // 兜底：任务已经结束，但流里没有 done 事件（见类注释）。
                // 再读一次，把"刚好在这期间到达"的事件补上，然后收尾。
                drain(taskId, cursor, sink);
                return;
            }
            if (System.nanoTime() >= deadlineNanos) {
                LOGGER.info("SSE 中继到达最长时限，主动收尾：taskId={} cursor={}", taskId, cursor);
                return;
            }
            if (!pause()) {
                return;
            }
        }
    }

    private void drain(long taskId, long cursor, AiTaskEventSink sink) {
        for (AiTaskEvent event : events.readAfter(taskId, cursor, batchSize)) {
            if (sink.closed()) {
                return;
            }
            sink.send(event);
        }
    }

    /**
     * 任务是否已经结束。
     *
     * <p>任务行读不到时按"已结束"处理：它可能已被清理。此时继续跟随没有意义，
     * 而继续跟随的代价是一条永远不结束的连接。
     */
    private boolean terminal(long taskId) {
        return tasks.find(taskId).map(AiTask::status).map(AiTaskStatus::terminal).orElse(true);
    }

    /** @return 是否应当继续循环（被打断时返回 false） */
    private boolean pause() {
        try {
            Thread.sleep(Math.max(0L, pollInterval.toMillis()));
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 已产生的临时文本，按六章节顺序渲染。
     *
     * <p>没有片段时返回 {@code null}（不是空字符串）：{@code snapshot} 载荷据此决定
     * 要不要带这个字段，而"空字符串"会让前端把已经渲染好的内容清空。
     *
     * <p>渲染顺序复用 {@link AiReportText#renderMarkdown}——章节顺序与标题只有一处定义。
     */
    private String partialContentOf(long taskId) {
        Map<AiReportSection, StringBuilder> bySection = new EnumMap<>(AiReportSection.class);
        for (AiTaskEvent event : events.readAfter(taskId, 0L, partialContentScanLimit)) {
            if (event.type() != AiTaskEventType.CHUNK) {
                continue;
            }
            AiTaskEventPayloads.readChunk(event.dataJson()).ifPresent(chunk ->
                    bySection.computeIfAbsent(chunk.section(), key -> new StringBuilder())
                            .append(chunk.delta()));
        }
        if (bySection.isEmpty()) {
            return null;
        }
        return AiReportText.renderMarkdown(section -> {
            StringBuilder body = bySection.get(section);
            return body == null ? null : body.toString();
        });
    }
}
