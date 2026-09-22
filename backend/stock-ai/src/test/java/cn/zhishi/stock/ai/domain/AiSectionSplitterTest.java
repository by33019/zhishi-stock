package cn.zhishi.stock.ai.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AiSectionSplitter} 的行为测试。
 *
 * <p>这个类守着的是"流式切分"而不只是"字符串切分"——最容易被写错、且写错之后
 * 单次手工验证看不见的，是**标记被拆在两个增量之间**：供应商按自己的节奏分片，
 * {@code [[SECT} + {@code ION:CORE_CONCLUSION]]} 是完全正常的分法。
 * 若实现把不完整的标记当正文吐出去，正文里会混入半个标记，而整行永远等不到完整标记，
 * 表现为"报告里偶尔出现一串方括号"——只在某些分片边界上出现。
 */
class AiSectionSplitterTest {

    @Test
    @DisplayName("标记独占一行时按其切分章节，标记本身不进正文")
    void splitsOnMarkerLines() {
        AiSectionSplitter splitter = new AiSectionSplitter();
        List<AiSectionSplitter.Segment> segments = new ArrayList<>();

        segments.addAll(splitter.accept(
                AiSectionMarker.of(AiReportSection.CORE_CONCLUSION) + "\n结论正文\n"));
        segments.addAll(splitter.accept(
                AiSectionMarker.of(AiReportSection.DISCLAIMER) + "\n免责声明正文\n"));
        segments.addAll(splitter.finish());

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).section()).isEqualTo(AiReportSection.CORE_CONCLUSION);
        assertThat(segments.get(0).delta()).isEqualTo("结论正文\n");
        assertThat(segments.get(1).section()).isEqualTo(AiReportSection.DISCLAIMER);
        assertThat(segments.get(1).delta()).isEqualTo("免责声明正文\n");
        assertThat(join(segments)).doesNotContain("[[").doesNotContain("]]");
    }

    @Test
    @DisplayName("标记被拆在两个增量之间时仍能识别，且不把半个标记写进正文")
    void recognisesMarkerSplitAcrossChunks() {
        AiSectionSplitter splitter = new AiSectionSplitter();
        List<AiSectionSplitter.Segment> segments = new ArrayList<>();

        // 逐个字符地喂，覆盖所有可能的分片边界，而不只是挑一个。
        String stream = AiSectionMarker.of(AiReportSection.QUOTE_EVIDENCE) + "\n量价依据。\n";
        for (char c : stream.toCharArray()) {
            segments.addAll(splitter.accept(String.valueOf(c)));
        }
        segments.addAll(splitter.finish());

        // 逐字符喂入时一个字符就是一个片段，因此这里断言合并结果与章节归属，
        // 而不是片段个数——片段个数是分片方式的函数，不是本类的契约。
        assertThat(segments).isNotEmpty();
        assertThat(segments).allSatisfy(
                segment -> assertThat(segment.section()).isEqualTo(AiReportSection.QUOTE_EVIDENCE));
        assertThat(join(segments)).isEqualTo("量价依据。\n");
        assertThat(join(segments)).doesNotContain("[");
    }

    @Test
    @DisplayName("正文无需等待换行即可吐出，保住逐字上屏")
    void streamsProseWithoutWaitingForNewline() {
        AiSectionSplitter splitter = new AiSectionSplitter();
        splitter.accept(AiSectionMarker.of(AiReportSection.CORE_CONCLUSION) + "\n");

        // 一长行、没有换行的中间增量必须立刻产出。
        List<AiSectionSplitter.Segment> segments =
                splitter.accept("这是一段还没有结束的长文本，它不应该被扣在缓冲区里等待换行");

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).section()).isEqualTo(AiReportSection.CORE_CONCLUSION);
        assertThat(segments.get(0).delta()).isEqualTo("这是一段还没有结束的长文本，它不应该被扣在缓冲区里等待换行");
    }

    @Test
    @DisplayName("结论章节的第二段不受影响：切章节不丢正文")
    void keepsAllProseWithinASection() {
        AiSectionSplitter splitter = new AiSectionSplitter();
        List<AiSectionSplitter.Segment> segments = new ArrayList<>();
        segments.addAll(splitter.accept(AiSectionMarker.of(AiReportSection.CORE_CONCLUSION) + "\n"));
        segments.addAll(splitter.accept("第一句。\n"));
        segments.addAll(splitter.accept("第二句还没有换行"));
        segments.addAll(splitter.accept("，续上。\n"));
        segments.addAll(splitter.finish());

        assertThat(join(segments)).isEqualTo("第一句。\n第二句还没有换行，续上。\n");
    }

    @Test
    @DisplayName("第一个标记之前的开场白被丢弃，不污染任何章节")
    void discardsPreambleBeforeFirstMarker() {
        AiSectionSplitter splitter = new AiSectionSplitter();
        List<AiSectionSplitter.Segment> segments = new ArrayList<>();

        segments.addAll(splitter.accept("好的，我将按要求分析这只股票的走势。\n"));
        segments.addAll(splitter.accept(AiSectionMarker.of(AiReportSection.CORE_CONCLUSION) + "\n结论。\n"));
        segments.addAll(splitter.finish());

        assertThat(segments).hasSize(1);
        assertThat(join(segments)).isEqualTo("结论。\n");
        assertThat(join(segments)).doesNotContain("好的");
    }

    @Test
    @DisplayName("模型完全没输出标记时产出为空，交由定稿校验以「缺少必填章节」明确失败")
    void producesNothingWhenModelIgnoresMarkerInstruction() {
        AiSectionSplitter splitter = new AiSectionSplitter();
        List<AiSectionSplitter.Segment> segments = new ArrayList<>();
        segments.addAll(splitter.accept("一、核心结论\n这只股票短期偏强。\n"));
        segments.addAll(splitter.finish());

        assertThat(segments).isEmpty();
        assertThat(splitter.seenSections()).isEmpty();
    }

    @Test
    @DisplayName("接受中文标题写法，但形状像标记而章节名不认识的那一行从正文里剔除")
    void toleratesChineseTitleAndDropsUnknownMarker() {
        AiSectionSplitter splitter = new AiSectionSplitter();
        List<AiSectionSplitter.Segment> segments = new ArrayList<>();
        segments.addAll(splitter.accept("[[SECTION:核心结论]]\n正文一。\n"));
        segments.addAll(splitter.accept("[[SECTION:SUMMARY]]\n"));
        segments.addAll(splitter.accept("正文二。\n"));
        segments.addAll(splitter.finish());

        // 中文标题能识别为 CORE_CONCLUSION；不存在的 SUMMARY 既不算章节也不进正文。
        assertThat(splitter.seenSections()).containsExactly(AiReportSection.CORE_CONCLUSION);
        assertThat(join(segments)).isEqualTo("正文一。\n正文二。\n");
        assertThat(join(segments)).doesNotContain("SUMMARY");
    }

    @Test
    @DisplayName("流结束时必须刷出最后一行，否则报告会少最后一句")
    void flushesTrailingLineWithoutNewline() {
        AiSectionSplitter splitter = new AiSectionSplitter();
        List<AiSectionSplitter.Segment> segments = new ArrayList<>();
        segments.addAll(splitter.accept(AiSectionMarker.of(AiReportSection.DISCLAIMER) + "\n"));
        segments.addAll(splitter.accept("本内容不构成投资建议。"));
        // 注意：这里没有换行符收尾。
        segments.addAll(splitter.finish());

        assertThat(join(segments)).isEqualTo("本内容不构成投资建议。");
    }

    @Test
    @DisplayName("正文里出现的标记字样（非独占一行）按普通文本处理，不被当成章节切换")
    void doesNotTreatInlineMarkerAsSectionSwitch() {
        AiSectionSplitter splitter = new AiSectionSplitter();
        List<AiSectionSplitter.Segment> segments = new ArrayList<>();
        segments.addAll(splitter.accept(AiSectionMarker.of(AiReportSection.CORE_CONCLUSION) + "\n"));
        segments.addAll(splitter.accept("下面说明标记长什么样：" + AiSectionMarker.of(AiReportSection.DISCLAIMER) + "\n"));
        segments.addAll(splitter.finish());

        assertThat(splitter.seenSections()).containsExactly(AiReportSection.CORE_CONCLUSION);
        assertThat(join(segments)).contains("下面说明标记长什么样");
    }

    private static String join(List<AiSectionSplitter.Segment> segments) {
        StringBuilder builder = new StringBuilder();
        segments.forEach(segment -> builder.append(segment.delta()));
        return builder.toString();
    }
}
