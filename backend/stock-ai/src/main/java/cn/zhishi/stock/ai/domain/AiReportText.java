package cn.zhishi.stock.ai.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报告正文的渲染与校验（纯函数，无依赖）。
 *
 * <h2>为什么渲染与校验放在同一处</h2>
 * 「报告由哪几个章节组成」与「报告正文里的引用编号长什么样」是同一份知识的两个面：
 * 渲染时按 {@link AiReportSection#inOrder()} 拼接，校验时按同一份顺序检查缺章、
 * 按同一个正则提取引用。分成两处就会出现"校验认得的引用格式、渲染时写成了另一种"
 * ——这类分歧不会报错，只会让校验静默通过（或静默失败）。
 *
 * <h2>为什么引用校验是「编号必须落在候选集合内」</h2>
 * 契约 §13.5：「模型生成的 URL 不直接作为证据；引用只能绑定任务开始时固化的证据候选」。
 * 模型侧拿不到 URL（{@link LlmEvidence} 结构上没有该字段），所以能出问题的只有编号：
 * 正文里出现一个候选集合外的编号，意味着模型在引用一个不存在的证据——
 * 用户点不开、也查不到，而它看起来和真引用一模一样。
 */
public final class AiReportText {

    /** 正文里的引用编号形如 {@code [1]} / {@code [12]}。 */
    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,3})]");

    private AiReportText() {
    }

    /**
     * 按章节顺序拼出 {@code rendered_markdown}。
     *
     * <p>空章节**整段跳过**（连标题也不写）：一个只有标题、正文为空的小节会让读者
     * 以为"这里本来有内容但丢了"。章节有没有内容，由 {@code textOf} 如实回答。
     */
    public static String renderMarkdown(Function<AiReportSection, String> textOf) {
        StringBuilder markdown = new StringBuilder();
        for (AiReportSection section : AiReportSection.inOrder()) {
            String body = textOf.apply(section);
            if (body == null || body.isBlank()) {
                continue;
            }
            if (markdown.length() > 0) {
                markdown.append("\n\n");
            }
            markdown.append("## ").append(section.title()).append("\n\n").append(body.strip());
        }
        return markdown.toString();
    }

    /** 正文里出现的全部引用编号（升序，去重）。 */
    public static Set<Integer> citationsIn(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<Integer> found = new TreeSet<>();
        Matcher matcher = CITATION.matcher(text);
        while (matcher.find()) {
            found.add(Integer.parseInt(matcher.group(1)));
        }
        // 用 unmodifiableSortedSet 而不是 Set.copyOf：后者的迭代顺序未定义，
        // 于是"升序"这句 javadoc 会变成一句谎话（错误信息里的编号会随机排列）。
        return Collections.unmodifiableSortedSet((SortedSet<Integer>) found);
    }

    /**
     * 该场景下必须出现的章节。
     *
     * <p>比 {@link AiReportSection#required()} 多一条：{@code COMPARE} 场景必须有
     * {@code COMPARISON_ANALYSIS}。这个场景的目标类型白名单只有证券且至少两个，
     * 而"证券缺行情"会让任务在更早的闸门以 {@code AI_CORE_DATA_MISSING} 失败——
     * 也就是说走到校验这一步时，对比章节一定**可产出**。所以这里要求它是安全的，
     * 不会把一个正常任务判成失败。
     */
    public static List<AiReportSection> requiredSections(AiScene scene) {
        List<AiReportSection> required = new ArrayList<>();
        for (AiReportSection section : AiReportSection.inOrder()) {
            if (section.required() || (scene == AiScene.COMPARE && isComparison(section))) {
                required.add(section);
            }
        }
        return List.copyOf(required);
    }

    /** 场景要求的章节里缺失的那些（按章节顺序）。 */
    public static List<AiReportSection> missingRequired(
            AiScene scene, Collection<AiReportSection> produced) {
        return requiredSections(scene).stream().filter(section -> !produced.contains(section)).toList();
    }

    private static boolean isComparison(AiReportSection section) {
        return section == AiReportSection.COMPARISON_ANALYSIS;
    }
}
