package cn.zhishi.stock.admin.application;

import java.util.Set;

/**
 * 读日志时的第二道脱敏（契约 §16.2 LOG-02："不返回密码、JWT、Cookie、验证码、API Key
 * 或完整 AI 上下文"）。
 *
 * <h2>写入侧已经脱敏了，为什么读的时候还要再做一次</h2>
 * 写入侧的脱敏（{@code AdminAudit.summary}）靠的是**调用方逐个字段拼摘要**，
 * 它对"新写的行"成立。但 {@code sys_log} 里有 V2 迁移过来的历史行，
 * 那些行由旧系统写入，形状与合规性都无从保证——它们是本类型的真正服务对象。
 * 只在写入侧设防，等于假定"库里现有的数据都合规"，而审计表的整个意义就是
 * 记录"当初到底发生了什么"，包括当初记录得不规范这件事。
 *
 * <h2>两种形状，两套策略</h2>
 * <ol>
 *   <li><b>{@code key=value;key=value}</b>（本项目写入的形状）——能定位到键的边界，
 *       因此只把命中黑名单的**值**换成 {@code ***}，其余信息保留，日志仍然可读。</li>
 *   <li><b>其它一切形状</b>（旧系统的 JSON、任意字符串）——键的边界不可靠，
 *       于是只要出现任一敏感关键词就**整段**打码。宁可少给一段摘要，
 *       也不能给出一个"看起来像摘要、其实是密码"的字符串。</li>
 * </ol>
 *
 * <h2>键名比较前先归一化</h2>
 * {@code access_token} / {@code accessToken} / {@code ACCESS_TOKEN} / {@code "accessToken"}
 * 是同一个字段的四种写法（最后一种来自 JSON 片段被当成键解析的情况）。
 * 统一小写并去掉非字母数字后再比对，比在黑名单里穷举写法可靠。
 */
public final class SensitiveParamsRedactor {

    /** 命中后替换成的字面量。长度固定、不含原值任何前缀——部分打码会留下可猜的线索。 */
    public static final String MASK = "***";

    private static final String PAIR_SEPARATOR = ";";
    private static final char PAIR_ASSIGNMENT = '=';

    /**
     * 敏感键名（已归一化）。
     *
     * <p>刻意**不含** {@code code}：它在业务里是"股票代码"这类正常字段，
     * 把 {@code code} 拉黑会让日志摘要里出现一片 {@code ***}。
     * 验证码因此只认 {@code captcha} / {@code smscode} 这类限定写法。
     */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "pwd", "passwordhash",
            "newpassword", "oldpassword", "confirmpassword", "temporarypassword",
            "token", "accesstoken", "refreshtoken", "idtoken", "authtoken", "jwt",
            "authorization", "cookie", "setcookie",
            "secret", "clientsecret", "apikey", "apisecret", "credential",
            "captcha", "verifycode", "verificationcode", "smscode",
            "prompt", "messages", "context", "aicontext", "conversation", "systemprompt");

    /** {@code Bearer xxx} 与 JWT 头（base64 的 {@code {"}）的归一化特征。 */
    private static final String BEARER_MARKER = "bearer";
    private static final String JWT_MARKER = "eyj";

    /**
     * 脱敏参数摘要。{@code null} / 空白原样返回——
     * 把空值变成 {@code ***} 会让"这条日志没有参数"与"参数被藏起来了"无法区分。
     */
    public String redact(String params) {
        if (params == null || params.isBlank()) {
            return params;
        }
        if (isPairList(params)) {
            return redactPairs(params);
        }
        return containsSensitiveToken(params) ? MASK : params;
    }

    /**
     * 是否为本项目写入的 {@code key=value;...} 形状。
     *
     * <p>含 {@code \{} 或 {@code [} 的一律不算：那是结构化文本（旧系统的 JSON 请求体），
     * 它的键边界与 {@code ;} 分隔的键值对不是一回事，硬按后者解析会把
     * {@code {"password":"x"}} 整体当成一个"值"，反而漏掉。
     */
    private static boolean isPairList(String params) {
        if (params.indexOf('{') >= 0 || params.indexOf('[') >= 0) {
            return false;
        }
        boolean sawPair = false;
        for (String segment : params.split(PAIR_SEPARATOR, -1)) {
            if (segment.isBlank()) {
                continue;
            }
            if (segment.indexOf(PAIR_ASSIGNMENT) < 0) {
                return false;
            }
            sawPair = true;
        }
        return sawPair;
    }

    private static String redactPairs(String params) {
        String[] segments = params.split(PAIR_SEPARATOR, -1);
        StringBuilder builder = new StringBuilder(params.length());
        for (int index = 0; index < segments.length; index++) {
            if (index > 0) {
                builder.append(PAIR_SEPARATOR);
            }
            String segment = segments[index];
            int assignment = segment.indexOf(PAIR_ASSIGNMENT);
            if (assignment < 0) {
                builder.append(segment);
                continue;
            }
            String key = segment.substring(0, assignment);
            String value = segment.substring(assignment + 1);
            if (isSensitiveKey(key) || looksLikeBearerOrJwt(value)) {
                builder.append(key).append(PAIR_ASSIGNMENT).append(MASK);
            } else {
                builder.append(segment);
            }
        }
        return builder.toString();
    }

    private static boolean isSensitiveKey(String key) {
        return SENSITIVE_KEYS.contains(normalize(key));
    }

    /** 形状不可信时（非键值对）的兜底判断：出现任一敏感关键词即认为整段不可给。 */
    private static boolean containsSensitiveToken(String params) {
        String normalized = normalize(params);
        for (String key : SENSITIVE_KEYS) {
            if (normalized.contains(key)) {
                return true;
            }
        }
        return normalized.contains(BEARER_MARKER) || normalized.contains(JWT_MARKER);
    }

    /**
     * 值本身的形状判断：键名正常（如 {@code reason}）不代表值安全——
     * 用户完全可能把一段 token 粘进备注里。
     *
     * <p>判断偏保守：{@code eyj} 是 base64 编码的 {@code {"}，任何 JWT 都以它开头；
     * 代价是极少数含该三字母组合的正常文本会被打码，这个代价换的是"不会漏掉一个 token"。
     */
    private static boolean looksLikeBearerOrJwt(String value) {
        String normalized = normalize(value);
        return normalized.contains(BEARER_MARKER) || normalized.contains(JWT_MARKER);
    }

    /** 小写并去掉所有非字母数字字符：{@code access_token} 与 {@code accessToken} 归一化后相同。 */
    private static String normalize(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                builder.append(Character.toLowerCase(character));
            }
        }
        return builder.toString();
    }
}
