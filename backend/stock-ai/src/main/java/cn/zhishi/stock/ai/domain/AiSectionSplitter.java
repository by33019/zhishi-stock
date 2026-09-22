package cn.zhishi.stock.ai.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 把大模型的连续文本流切成「章节 + 增量文本」对。
 *
 * <h2>为什么是有状态的流式切分，而不是"收完再正则匹配"</h2>
 * 收完再切会让 SSE 的逐字上屏退化成"憋完整份再一次性出现"——契约 §13.4 的
 * 临时片段语义就失去意义了，而用户看到的是一个长时间不动的转圈。
 * 所以这里按增量喂入、按增量吐出。
 *
 * <h2>难点：标记可能被拆在两个片段之间</h2>
 * {@code [[SECT} + {@code ION:CORE_CONCLUSION]]} 是完全正常的分片方式。
 * 因此不能"见到 {@code [[} 就当内容吐出去"——那样吐出去的半个标记会变成正文，
 * 而整行永远等不到完整标记。判定规则是：
 * <ul>
 *   <li>行首（去空白后）与 {@link AiSectionMarker} 的标记前缀"互为前缀" → <b>整行扣住</b>，
 *       等换行或流结束再判；标记行本身很短，扣住的代价是几十毫秒。
 *   <li>行首已经出现与标记前缀无关的字符 → 这一行<b>确定不是</b>标记行，
 *       立即全部吐出。这是保住"逐字上屏"的关键：正文多是长行，
 *       若为了等换行而扣住整段，流式就白做了。
 * </ul>
 *
 * <h2>第一个标记之前的内容会被丢弃</h2>
 * 模型常先来一句"好的，我将按要求分析如下"。那段话不属于任何章节，
 * 拼进任何章节都是污染。刻意**不**兜底到 {@code CORE_CONCLUSION}：
 * 模型完全没输出标记时，兜底会让整份寒暄泄漏进"核心结论"；而丢弃会让
 * {@link LlmCompletion#sections()} 为空，定稿校验以"缺少必填章节"明确失败——
 * 后者是可诊断的，前者会产出一份看起来正常、实则可疑的报告。
 */
public final class AiSectionSplitter {

    /** 标记前缀中"到冒号为止"的一段，用于判定行首是否可能正在拼一个标记。 */
    private static final String MARKER_PREFIX = markerPrefix();

    private final StringBuilder pending = new StringBuilder();

    private final List<AiReportSection> seen = new ArrayList<>();

    private AiReportSection current;

    /** 一个已切分好的片段：属于哪个章节、增量文本是什么。 */
    public record Segment(AiReportSection section, String delta) {
    }

    /**
     * 喂入一段增量文本，返回本次可确定归属的片段。
     *
     * <p>返回值可能为空（例如刚好吃到一个标记行）。
     */
    public List<Segment> accept(String text) {
        List<Segment> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        pending.append(text);
        int newline;
        while ((newline = pending.indexOf("\n")) >= 0) {
            String line = pending.substring(0, newline);
            pending.delete(0, newline + 1);
            emitLine(line, true, out);
        }
        flushSafeHead(out);
        return out;
    }

    /**
     * 流结束时调用，吐出最后一行（它后面没有换行符）。
     *
     * <p>不调用它会让最后一段正文永久留在缓冲区里——表现为"报告少了最后一句"，
     * 而前面每一章都正常，很难联想到是缓冲没刷。
     */
    public List<Segment> finish() {
        List<Segment> out = new ArrayList<>();
        if (pending.length() > 0) {
            String line = pending.toString();
            pending.setLength(0);
            emitLine(line, false, out);
        }
        return out;
    }

    /** 已识别出的章节（按首次出现顺序），供断言"模型到底输出了哪几章"。 */
    public List<AiReportSection> seenSections() {
        return List.copyOf(seen);
    }

    private void emitLine(String line, boolean withNewline, List<Segment> out) {
        AiReportSection marker = AiSectionMarker.parse(line);
        if (marker != null) {
            current = marker;
            if (!seen.contains(marker)) {
                seen.add(marker);
            }
            return;
        }
        if (AiSectionMarker.looksLikeMarker(line)) {
            // 形如标记但章节名不认识：既不是合法标记，也不该出现在报告正文里。
            return;
        }
        if (current == null) {
            // 第一个标记之前的寒暄，不属于任何章节。
            return;
        }
        out.add(new Segment(current, withNewline ? line + "\n" : line));
    }

    /**
     * 把缓冲里"确定不属于标记"的部分立即吐出去。
     *
     * <p>扣住的情形只有一种：行首还可能是标记的片段（含空白行首与空缓冲）。
     * 尚未进入任何章节时直接丢弃而不是扣住——否则越说越长的开场白会一直堆在缓冲里。
     */
    private void flushSafeHead(List<Segment> out) {
        String head = pending.toString();
        if (isMarkerCompatible(stripLeadingWhitespace(head))) {
            return;
        }
        pending.setLength(0);
        if (current == null) {
            return;
        }
        out.add(new Segment(current, head));
    }

    /** 行首是否可能正在拼一个标记（两者互为前缀，任一为空也算）。 */
    private static boolean isMarkerCompatible(String leading) {
        if (leading.isEmpty()) {
            return true;
        }
        return MARKER_PREFIX.startsWith(leading) || leading.startsWith(MARKER_PREFIX);
    }

    private static String stripLeadingWhitespace(String text) {
        int i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return text.substring(i);
    }

    /** 从标记定义里裁出前缀，避免在这里再写一份 {@code "[[SECTION:"}。 */
    private static String markerPrefix() {
        String marker = AiSectionMarker.of(AiReportSection.CORE_CONCLUSION);
        int colon = marker.indexOf(':');
        return colon < 0 ? marker : marker.substring(0, colon + 1);
    }
}
