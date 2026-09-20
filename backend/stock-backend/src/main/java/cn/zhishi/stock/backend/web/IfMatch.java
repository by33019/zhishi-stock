package cn.zhishi.stock.backend.web;

/**
 * {@code If-Match} 请求头的解析（契约 §3.5 / §3.7：对含 {@code version} 的资源提交当前版本号）。
 *
 * <p>接受 {@code 3} 与 {@code "3"} 两种写法——契约表格里的示例是带引号的 HTTP ETag 风格，
 * 但客户端实际两种都会发。
 *
 * <p>刻意**不接受** {@code *}：契约没有为这些资源定义"匹配任意版本"的语义，
 * 静默把它当成"随便改"会让乐观锁形同虚设，不如响亮地拒绝。
 *
 * <p>抽成共用工具而不是写在某个控制器里：WAT-03 / WAT-04 之后，
 * USER-02、HIS-03、ADM-PRV-04 都要同一段解析。
 */
final class IfMatch {

    private IfMatch() {
    }

    static int version(String header) {
        if (header == null || header.isBlank()) {
            throw new InvalidIfMatchException("缺少 If-Match 请求头");
        }
        String value = header.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new InvalidIfMatchException("If-Match 必须是当前 version 的整数");
        }
    }
}
