package cn.zhishi.stock.news.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 内容指纹：清洗后的标题 + 摘要的 SHA-256，与 {@code stock_news.content_fingerprint}
 * 的 {@code char(64)} 列一一对应。
 *
 * <h2>清洗口径</h2>
 * 按顺序做三件事，缺任何一件都会让"同一篇稿件"算出两个指纹：
 *
 * <ol>
 *   <li>去掉 HTML 标签（来源侧摘要常带 {@code <p>} / {@code <em>}）——
 *       <b>直接删除而不是替换成空格</b>，否则 {@code <p>摘要<em>内容</em></p>}
 *       会变成 {@code "摘要 内容"}，与源文本 {@code "摘要内容"} 判为不同；
 *   <li>去掉零宽字符（{@code U+200B~U+200D}、{@code U+FEFF}）——
 *       它们在编辑器里不可见，却会让两个看起来一样的字符串不相等；
 *   <li>去掉**全部**空白（含换行、制表、全角空格 {@code U+3000}）。
 * </ol>
 *
 * <p>空白为什么要"去掉"而不是"折叠成一个空格"：折叠之后 {@code "摘要\n内容"}
 * 与 {@code "摘要内容"} 仍判为不同，而这两者显然是同一篇稿件。
 * 代价是空白分隔的语言里 {@code "A B"} 与 {@code "AB"} 会撞车——
 * 这在只影响"是否判为重复"的场景下可以接受（展示文本不受影响）。
 *
 * <p>字段之间用 {@code "\n"} 分隔，因此 {@code ("AB", "")} 与 {@code ("A", "B")}
 * 得到不同指纹：分隔符在清洗之后才拼接，不会被第 3 步吃掉。
 *
 * <p>**不做**大小写折叠、不做简繁转换、不做标点归一：那些会改变语义，
 * 让"内容不同但相似"的稿件被判为重复，而误判比漏判更难发现。
 *
 * <h2>已知局限（如实记录）</h2>
 * 精确指纹只能抓到**逐字相同**的内容。真实世界里两家媒体报同一件事，
 * 标题措辞几乎不会相同，因此本函数在真实数据上的召回率会很低。
 * 相似度去重（编辑距离 / 向量）是独立课题，不在 M3-04 范围（见 spec §3.4）。
 */
public final class NewsFingerprint {

    private static final String HTML_TAG = "<[^>]*>";
    private static final String ZERO_WIDTH = "[\\u200B-\\u200D\\uFEFF]";
    /** {@code \s} 在 Java 里只匹配 ASCII 空白，全角空格必须显式列出。 */
    private static final String ALL_WHITESPACE = "[\\s\\u3000]";

    private NewsFingerprint() {
    }

    /** 计算指纹：{@code SHA-256(清洗(title) + "\n" + 清洗(summary))} 的小写十六进制。 */
    public static String of(String title, String summary) {
        return sha256Hex(clean(title) + "\n" + clean(summary));
    }

    /** 清洗单个字段。{@code null} 按空串处理（摘要可空）。 */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll(HTML_TAG, "")
                .replaceAll(ZERO_WIDTH, "")
                .replaceAll(ALL_WHITESPACE, "");
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }
}
