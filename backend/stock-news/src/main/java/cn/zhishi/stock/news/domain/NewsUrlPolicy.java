package cn.zhishi.stock.news.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * 原文链接的协议白名单，对应契约 §11.2「原文 URL 仅允许 {@code https} 等白名单协议」。
 *
 * <p>白名单里**只有 https**：
 *
 * <ul>
 *   <li>{@code http} 会让用户读到明文页面，中间人可以改写内容——而我们把这个链接
 *       作为"这条资讯的来源"呈现给用户，改写的代价由我们的可信度承担；
 *   <li>{@code javascript:} / {@code data:} / {@code vbscript:} 是注入向量，
 *       前端即使加了 {@code noopener noreferrer} 也挡不住 {@code href} 上的伪协议。
 * </ul>
 *
 * <p>校验放在**采集侧**：不合规的原文地址不该进库。查询侧再判一次属于纵深防御，
 * 但那时它已经占据了 {@code original_url} 这个 NOT NULL 列，无法区分"没有原文"与"原文不合规"。
 */
public final class NewsUrlPolicy {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("https");

    private NewsUrlPolicy() {
    }

    /** 该原文地址是否允许入库与对外展示。 */
    public static boolean isAllowed(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException exception) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            return false;
        }
        // 有 scheme 但没有 host 的地址（如 https:foo）在浏览器里行为不确定，同样拒绝。
        return uri.getHost() != null && !uri.getHost().isBlank();
    }
}
