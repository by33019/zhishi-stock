package cn.zhishi.stock.ai.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Prompt 渲染（纯函数）。
 *
 * <h2>为什么单独成类而不是塞在执行器里</h2>
 * Prompt 是**对外契约的一部分**：它决定了输出的章节结构、引用格式与免责声明要求，
 * 而这三样正是校验器要检查的东西。渲染与校验写在两个类里时，改一处忘另一处会表现为
 * "模型按新格式输出、校验按旧格式拒绝"——任务全部失败，而两边各自看都对。
 *
 * <h2>刻意不放进 Prompt 的东西</h2>
 * 证据正文（{@code sourceTitle} / {@code evidenceSummary}）通过
 * {@link LlmRequest#evidenceCandidates()} 传入，不在这里拼进 userPrompt：
 * 两处各写一份，就会出现"Prompt 里有 20 条、候选集合里有 18 条"，
 * 而模型引用的编号会落在校验器认为不存在的区间里。
 *
 * <p>同理，这里**不写任何行情数字**——数字只能来自固化的证据摘要，
 * 由 Prompt 复述一遍就等于给了模型一个可以自由发挥的入口。
 */
public final class AiPromptRenderer {

    private AiPromptRenderer() {
    }

    /**
     * 系统提示词。
     *
     * <p>{@code promptVersion} / {@code contentSchemaVersion} 写进正文而不是只作为请求字段：
     * 换版本后回看历史报告时，需要能分辨"这份报告是按哪版要求生成的"。
     */
    public static String systemPrompt(String promptVersion, String contentSchemaVersion) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是 A 股研究助手。你只依据给定的证据候选作答，不引入外部知识。\n");
        prompt.append("输出必须按以下章节顺序组织，章节名不可改动、不可省略必填章节：\n");
        for (AiReportSection section : AiReportSection.inOrder()) {
            prompt.append("  ").append(section.order()).append(". ")
                    .append(section.title()).append("（").append(section.name()).append("）")
                    .append(section.required() ? " —— 必填" : " —— 有材料时才输出")
                    .append('\n');
        }
        prompt.append("章节分隔（格式固定，解析器按它切分，写错即整份作废）：\n");
        prompt.append("  - 每章正文之前必须输出一行分隔标记，形如 ")
                .append(AiSectionMarker.of(AiReportSection.CORE_CONCLUSION))
                .append("，把其中的英文名换成该章自己的英文名。\n");
        prompt.append("  - 标记必须独占一行，前后不加其它文字；不要用 Markdown 标题代替它。\n");
        prompt.append("  - 六个标记按上面的顺序全部输出，一个都不能少。\n");
        prompt.append("  - 标记之外不要输出任何开场白、寒暄或收尾总结。\n");
        prompt.append("引用规则：\n");
        prompt.append("  - 正文里的每条事实性表述必须用 [n] 标注来源，n 是证据候选的编号。\n");
        prompt.append("  - 只能引用候选集合里存在的编号；引用不存在的编号会导致整份输出被拒绝。\n");
        prompt.append("  - 不要输出任何链接或 URL，链接由系统按证据绑定生成。\n");
        prompt.append("  - 数据不足时明确说明缺什么，不要用估计值填补。\n");
        prompt.append("最后一章必须是免责声明，写明本内容不构成投资建议。\n");
        prompt.append("promptVersion=").append(promptVersion)
                .append(" contentSchemaVersion=").append(contentSchemaVersion);
        return prompt.toString();
    }

    /** 用户提示词：描述这次要分析什么，不含证据正文。 */
    public static String userPrompt(AiTask task, AiContextBuildResult context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("场景：").append(task.scene().name()).append('\n');
        prompt.append("分析对象：\n");
        for (AiContextTarget target : task.targets()) {
            prompt.append("  - ").append(target.targetType()).append(' ')
                    .append(target.targetName()).append("（").append(target.targetCode()).append("）")
                    .append(" 角色=").append(target.targetRole())
                    .append('\n');
        }
        prompt.append("分析区间：").append(rangeOf(task)).append('\n');
        prompt.append("数据截止：").append(cutoffSummary(context)).append('\n');
        String question = task.question();
        prompt.append("用户问题：").append(question == null || question.isBlank() ? "（未提供）" : question)
                .append('\n');
        List<String> limitations = context.limitations();
        prompt.append("已知数据缺口：")
                .append(limitations.isEmpty() ? "无" : String.join("；", limitations))
                .append('\n');
        prompt.append("证据候选共 ").append(context.evidenceCandidates().size()).append(" 条。");
        return prompt.toString();
    }

    private static String rangeOf(AiTask task) {
        OffsetDateTime start = task.analysisStartAt();
        OffsetDateTime end = task.analysisEndAt();
        if (start == null && end == null) {
            return "未指定（按默认档位）";
        }
        return String.valueOf(start) + " ~ " + String.valueOf(end);
    }

    private static String cutoffSummary(AiContextBuildResult context) {
        if (context.dataCutoffs().isEmpty()) {
            return "无可用数据";
        }
        StringBuilder summary = new StringBuilder();
        for (AiDataCutoff cutoff : context.dataCutoffs()) {
            if (summary.length() > 0) {
                summary.append("，");
            }
            summary.append(cutoff.category()).append('=').append(cutoff.dataCutoffAt());
        }
        return summary.toString();
    }
}
