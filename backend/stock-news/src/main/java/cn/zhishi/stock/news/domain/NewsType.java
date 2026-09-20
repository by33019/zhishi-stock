package cn.zhishi.stock.news.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 资讯类型，取值与 {@code RESTful-API.md} §4.3 {@code newsType} 对齐。
 *
 * <p>与 {@code stock_news.news_type} 的 CHECK 约束同集合：枚举与库约束各写一遍时，
 * 新增一种类型会先通过应用校验、再被数据库拒绝，因此这里的取值集合**必须**与 V4 迁移一致。
 *
 * <p>是唯一需要从查询参数解析的资讯枚举（NEWS-01 的 {@code newsTypes}、
 * STK-10 / SEC-07 的 {@code newsType}），因此提供 {@link #fromCode}；
 * 其余枚举只出现在库与内部逻辑里，用 {@code name()} 与列值一一对应即可。
 */
public enum NewsType {

    NEWS("NEWS"),
    ANNOUNCEMENT("ANNOUNCEMENT"),
    RESEARCH("RESEARCH"),
    OTHER("OTHER");

    private final String code;

    NewsType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 全部类型代码，顺序与声明一致。用于错误信息，避免在别处再抄一遍枚举取值。 */
    public static List<String> codes() {
        return Arrays.stream(values()).map(NewsType::code).toList();
    }

    /** 解析类型代码，大小写不敏感；未知取值返回空，**不回落默认值**。 */
    public static Optional<NewsType> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.code.equals(normalized))
                .findFirst();
    }
}
