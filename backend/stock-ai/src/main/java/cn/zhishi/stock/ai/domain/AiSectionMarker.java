package cn.zhishi.stock.ai.domain;

/**
 * 章节分隔标记的**唯一定义处**。
 *
 * <h2>为什么需要它</h2>
 * 真实大模型输出的是连续文本，而 {@link LlmCompletion} 必须是「章节 → 文本」的映射
 * （定稿校验要逐章节检查必填与引用编号）。{@code SimulatedLlmProvider} 天然按章节产出，
 * 真实实现不会，于是需要一个模型能稳定输出、适配器能可靠切分的约定。
 *
 * <h2>为什么必须只有一处定义</h2>
 * 这个格式同时是「Prompt 承诺的输出格式」与「适配器解析的输入格式」。
 * 写成两份（渲染器里一份、解析器里一份）时，改一处忘另一处的表现是
 * 「模型按新格式输出、解析按旧格式切分」——所有任务失败，而两边各自看都对。
 * 这与 {@link AiPromptRenderer} 的类注释警告的是同一类缺陷，处置方式也一样：收敛到一处。
 *
 * <h2>容错：接受英文名，也接受中文标题</h2>
 * 模型偶尔会把 {@code [[SECTION:CORE_CONCLUSION]]} 写成 {@code [[SECTION:核心结论]]}。
 * 两种都认，而不是一律判失败——标记本身不是分析内容，为它的措辞差异让整份分析作废
 * 是把成本转嫁给了用户，而用户无法从错误信息里知道该改什么。
 *
 * <p>英文名的大小写不敏感（模型可能写成 {@code core_conclusion}）。
 */
public final class AiSectionMarker {

    /** 标记前缀。用 {@code [[...]]} 而不是 Markdown 标题：Markdown 标题会与正文标题混淆。 */
    private static final String OPEN = "[[SECTION:";

    private static final String CLOSE = "]]";

    private AiSectionMarker() {
    }

    /** 该章节的标记文本，例如 {@code [[SECTION:CORE_CONCLUSION]]}。 */
    public static String of(AiReportSection section) {
        return OPEN + section.name() + CLOSE;
    }

    /**
     * 解析一行是否为章节标记。
     *
     * <p>标记必须**独占一行**（允许行首行尾空白）。嵌在正文中间的 {@code [[SECTION:...]]}
     * 不视为标记——模型在解释"标记长什么样"时会把它写进正文，那种情况按普通文本处理更安全。
     *
     * @return 命中则返回对应章节；该行不是标记则返回 {@code null}
     */
    public static AiReportSection parse(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (!trimmed.startsWith(OPEN) || !trimmed.endsWith(CLOSE)) {
            return null;
        }
        String token = trimmed.substring(OPEN.length(), trimmed.length() - CLOSE.length()).trim();
        if (token.isEmpty()) {
            return null;
        }
        for (AiReportSection section : AiReportSection.values()) {
            if (section.name().equalsIgnoreCase(token) || section.title().equals(token)) {
                return section;
            }
        }
        return null;
    }

    /**
     * 判断一行是否**看起来像**章节标记但无法解析出章节。
     *
     * <p>用途是把它从正文里剔除，而不是当成内容拼进去：模型写了
     * {@code [[SECTION:SUMMARY]]} 这种不存在的章节名时，那一行既不是合法标记，
     * 也不该出现在报告正文里——把它渲染给用户看，用户会以为这是报告的一部分。
     */
    public static boolean looksLikeMarker(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        return trimmed.startsWith(OPEN) && trimmed.endsWith(CLOSE);
    }
}
