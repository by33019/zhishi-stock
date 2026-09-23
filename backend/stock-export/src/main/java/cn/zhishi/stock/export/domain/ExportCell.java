package cn.zhishi.stock.export.domain;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 一个导出单元格。**只有两种形态**：受防护的文本，或真正的数值。
 *
 * <h2>为什么必须有这个类型，而不是到处传 {@code String}</h2>
 * 契约 §22.2（PRD §12 / 验收项 §25.9）要求"Excel 文本以 {@code =}、{@code +}、{@code -}、
 * {@code @} 开头时进行公式注入防护"。若列值一律是 {@code String}，防护就只能写在
 * **写出那一刻**——而写出点分布在列宽、单元格类型、表头等多处，漏一处就是一个可被
 * 公式注入的单元格。把防护收进 {@link #text} 工厂，就变成"想构造文本单元格就必然过这道门"。
 *
 * <h2>数值单元格为什么不转义</h2>
 * 转义是给"内容会被 Excel 当公式解释"的文本用的。数值单元格在 xlsx 里是
 * {@code <c t="n">}，Excel 只会按数字读，不存在把它当公式执行的可能——
 * 所以 {@code -1.23} 写进去仍是负数，而不是被加上一个撇号变成文本。
 * 这正是"防护要做在正确的地方"：一刀切地给所有值加撇号，会让整列无法求和。
 */
public sealed interface ExportCell {

    /** 交给 Excel 写出层的原始值：{@code String}（已防护）或 {@link BigDecimal}。 */
    Object excelValue();

    /** 文本单元格。构造时即完成公式注入防护。 */
    record Text(String value) implements ExportCell {
        @Override
        public Object excelValue() {
            return value;
        }
    }

    /** 数值单元格。 */
    record Decimal(BigDecimal value) implements ExportCell {
        @Override
        public Object excelValue() {
            return value;
        }
    }

    /**
     * 可能被 Excel 当作公式起点的字符（契约 §22.2 逐字给出的四个）。
     *
     * <p>{@code \t} 与 {@code \r} 不在契约清单里，这里也不自作主张加上：
     * 防护范围要能被契约核对，多防的字符会让"为什么这一格多了个撇号"变成无从解释的现象。
     */
    Set<Character> FORMULA_STARTERS = Set.of('=', '+', '-', '@');

    /** 文本单元格。null 与空白归一为空串（空单元格，而不是写着 "null" 的单元格）。 */
    static ExportCell text(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Text("");
        }
        return new Text(guard(raw));
    }

    /**
     * 数值单元格。
     *
     * <p>解析失败时**退化为文本**而不是抛异常：上游给的是"定点数字符串"，
     * 但契约没有保证它一定可解析（例如将来出现带单位的取值）。
     * 为了一个格式意外让整份导出失败，代价大于把它原样写成一个文本格。
     */
    static ExportCell decimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Text("");
        }
        try {
            return new Decimal(new BigDecimal(raw.trim()));
        } catch (NumberFormatException exception) {
            return text(raw);
        }
    }

    /** 布尔列写成中文，与界面上的口径一致（而不是 {@code true} / {@code false}）。 */
    static ExportCell flag(boolean value) {
        return new Text(value ? "是" : "否");
    }

    /** 公式注入防护：首字符是四个公式起点之一时前置单引号。 */
    private static String guard(String raw) {
        return FORMULA_STARTERS.contains(raw.charAt(0)) ? "'" + raw : raw;
    }
}
