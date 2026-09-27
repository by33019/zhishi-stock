package cn.zhishi.stock.admin.application;

/**
 * 联系方式的脱敏（契约 §16.1 ADM-USR-01："普通管理员看不到完整联系方式"）。
 *
 * <h2>为什么脱敏在领域层做</h2>
 * 仓储把数据库行映射成领域记录时就把原始值换成脱敏值，因此未脱敏的邮箱与手机号
 * 不会进入用例与 Web 层。反过来（在响应组装时才脱敏）意味着原始值在内存里穿过了更多地方，
 * 其中任何一处打印日志、抛异常带上对象、或将来有人加一个"顺便返回原始值"的字段，
 * 都会把它带出去。契约 §22.2 要求的正是"按字段白名单记录、先脱敏"。
 *
 * <h2>保留多少位是有意的</h2>
 * 邮箱留首字符 + 完整域名：管理员需要靠域名判断"这是公司邮箱还是外部邮箱"。
 * 手机留前 3 位与后 4 位：足以让人确认"这是我以为的那个号"（后四位是用户最常报的数字），
 * 又不构成一个可直接拨打的号码。
 *
 * <h2>空值回 {@code null}，不回 {@code "***"}</h2>
 * {@code null} 与"填了一个看不出来的邮箱"必须在接口上可区分：
 * 契约 §16.1 ADM-USR-07 的凭证投递需要一个真实目标，把两者混成一个遮罩串，
 * 就会把"账号没填邮箱"变成一个 200 的成功响应。
 */
public final class MaskedContact {

    private MaskedContact() {
    }

    public static String email(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String value = email.trim();
        int at = value.indexOf('@');
        if (at <= 0) {
            // 没有 @ 或 @ 在首位：不是一个可判断形状的邮箱，整串遮掉而不猜它的结构。
            return "***";
        }
        return value.charAt(0) + "***" + value.substring(at);
    }

    public static String phone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String value = phone.trim();
        // 11 位手机取 3 + 4；更短的号码按同样规则会露出大半，因此短号码整串遮掉。
        if (value.length() < 8) {
            return "***";
        }
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }
}
