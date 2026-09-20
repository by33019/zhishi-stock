package cn.zhishi.stock.ai.domain;

import java.util.List;
import java.util.Objects;

/**
 * 分析区间档位（AI-01 的 {@code defaultRange}）。
 *
 * <h2>为什么是"档位"而不是"日期区间"</h2>
 * {@code GET /ai/scenes} 的语义是"有哪些场景"，不是"现在的默认区间是哪段"。
 * 若返回具体日期，同一份场景定义会在两次请求间变化，且目录被迫依赖时钟。
 * 因此这里只描述**可选档位**与**上限**，具体日期在 AI-02 / AI-03 由请求参数或档位换算得出。
 *
 * <p>档位取值来自 PRD 第 371 行「1/5/20 个交易日或自定义范围」；
 * {@code maxCustomDays} 来自同一行的「自定义范围最长 1 年」。
 *
 * @param presets       可选档位，顺序即展示顺序
 * @param defaultPreset 默认选中档位；必须是 {@code presets} 之一
 * @param maxCustomDays 自定义区间的最长天数
 */
public record AiAnalysisRange(
        List<AiAnalysisRange.Preset> presets,
        AiAnalysisRange.Preset defaultPreset,
        int maxCustomDays) {

    public AiAnalysisRange {
        presets = List.copyOf(presets);
        if (presets.isEmpty()) {
            throw new IllegalArgumentException("presets 不得为空");
        }
        if (!presets.contains(defaultPreset)) {
            throw new IllegalArgumentException("defaultPreset 必须是 presets 之一：" + defaultPreset);
        }
        if (maxCustomDays < 1) {
            throw new IllegalArgumentException("maxCustomDays 必须为正数：" + maxCustomDays);
        }
    }

    /**
     * 分析区间档位。
     *
     * <p>{@link #tradingDays()} 是档位的**唯一**语义定义：换算成具体日期区间时按交易日历回推
     * 这么多个交易日。把天数写在这里而不是散在换算代码里，是为了让"5 个交易日"只有一处定义。
     */
    public enum Preset {

        /** 最近 1 个交易日。 */
        LAST_1_TRADING_DAY(1),

        /** 最近 5 个交易日。 */
        LAST_5_TRADING_DAYS(5),

        /** 最近 20 个交易日。 */
        LAST_20_TRADING_DAYS(20);

        private final int tradingDays;

        Preset(int tradingDays) {
            this.tradingDays = tradingDays;
        }

        public int tradingDays() {
            return tradingDays;
        }

        public static List<String> codes() {
            return java.util.Arrays.stream(values()).map(Enum::name).toList();
        }
    }

    /** 档位编码列表，用于对外序列化。 */
    public List<String> presetCodes() {
        return presets.stream().map(Enum::name).toList();
    }

    /** 该区间是否允许 {@code spanDays} 天的自定义跨度。 */
    public boolean allowsCustomSpan(long spanDays) {
        return spanDays >= 0 && spanDays <= maxCustomDays;
    }

    /** 是否包含指定档位。 */
    public boolean hasPreset(AiAnalysisRange.Preset preset) {
        return Objects.equals(defaultPreset, preset) || presets.contains(preset);
    }
}
