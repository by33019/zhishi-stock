package cn.zhishi.stock.export.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 导出类型（契约 §9.2 EXP-01 的 {@code exportType}）。
 *
 * <h2>为什么 {@code AI_REPORT} 在这里"存在但不可用"</h2>
 * 契约 EXP-01 把 {@code AI_REPORT} 列为合法取值，但 PRD §5.3 明确把
 * "AI 报告 PDF/Markdown 导出"推迟到 V1.1。若 {@link #fromCode} 直接返回空，
 * 调用方会得到一句"取值不合法"，把一个**尚未交付**的功能报成**拼错了**——
 * 这两种情况的处置完全不同（前者等版本，后者改请求）。
 * 所以它留在枚举里，由 {@link #supported()} 表达"认得但还不支持"。
 */
public enum ExportType {

    /** 行情榜单（QTE-01 的三种口径）。 */
    STOCK_RANKING("STOCK_RANKING", "行情榜单", true),

    /** AI 报告。契约里是合法取值，交付时间在 V1.1（PRD §5.3）。 */
    AI_REPORT("AI_REPORT", "AI 报告", false);

    private final String code;
    private final String label;
    private final boolean supported;

    ExportType(String code, String label, boolean supported) {
        this.code = code;
        this.label = label;
        this.supported = supported;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 本版本是否真的能产出这种导出。 */
    public boolean supported() {
        return supported;
    }

    /** 大小写不敏感：契约给的是大写，但请求方不该因为大小写被拒。 */
    public static Optional<ExportType> fromCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (ExportType type : values()) {
            if (type.code.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /**
     * 全部取值，用于错误信息。
     *
     * <p>错误文案里那张"可选值"清单**必须**由这里产出，不能各写一遍：
     * 新增一种导出类型时，漏改一处就会让接口提示"可选值：STOCK_RANKING、AI_REPORT"，
     * 而实际上多了第三种——提示与实际不符，且不会有任何测试报错。
     */
    public static List<String> codes() {
        return Arrays.stream(values()).map(ExportType::code).toList();
    }

    /** 错误文案里的可选值清单，形如 {@code STOCK_RANKING、AI_REPORT}。 */
    public static String expectedCodes() {
        return String.join("、", codes());
    }
}
