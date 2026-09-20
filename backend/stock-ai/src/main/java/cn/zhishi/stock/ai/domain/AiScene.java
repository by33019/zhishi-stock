package cn.zhishi.stock.ai.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * AI 研究场景，取值与 {@code ai_session.scene} / {@code ai_task.scene} 的 CHECK 约束同集合
 * （{@code RESTful-API.md} §4.4、§13.1）。
 *
 * <p>枚举名与数据库取值逐字相同：多一层"业务名 ↔ 存储码"的映射，就多一处可以分叉的知识，
 * 而分叉不会报错——只会让某个场景在库里查不到。
 */
public enum AiScene {

    /** 市场总览解读。 */
    MARKET,

    /** 板块解读（PRD SEC-04）。 */
    SECTOR,

    /** 个股研究。 */
    STOCK,

    /** 个股风险梳理（PRD STK-06 的"风险梳理"入口）。 */
    STOCK_RISK,

    /** 多标的对比（PRD AI-05）。 */
    COMPARE;

    public static List<String> codes() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    /** 解析场景码；未知取值返回空，由调用方决定"未知"意味着什么。 */
    public static Optional<AiScene> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(scene -> scene.name().equals(normalized))
                .findFirst();
    }
}
