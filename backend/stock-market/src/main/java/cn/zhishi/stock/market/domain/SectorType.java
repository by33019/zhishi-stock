package cn.zhishi.stock.market.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 板块类型，取值与 {@code RESTful-API.md} §10 SEC-01 的 {@code sectorType} 对齐。
 *
 * <p>与 {@code stock_sector.sector_type} 的 CHECK 约束同集合：
 * 枚举与库约束各写一遍时，新增一种类型会先通过应用校验、再被数据库拒绝，
 * 因此这里的取值集合**必须**与 V3 迁移保持一致。
 */
public enum SectorType {

    /** 行业板块：MVP 的主口径（PRD §7.4 SEC-01「MVP 以行业板块为主」）。 */
    INDUSTRY("INDUSTRY"),

    /** 概念板块。 */
    CONCEPT("CONCEPT"),

    /** 地域板块。 */
    REGION("REGION");

    private final String code;

    SectorType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 全部类型代码，顺序与声明一致。用于错误信息，避免在别处再抄一遍枚举取值。 */
    public static List<String> codes() {
        return Arrays.stream(values()).map(SectorType::code).toList();
    }

    /** 解析类型代码，大小写不敏感；未知取值返回空，**不回落默认值**。 */
    public static Optional<SectorType> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.code.equals(normalized))
                .findFirst();
    }
}
