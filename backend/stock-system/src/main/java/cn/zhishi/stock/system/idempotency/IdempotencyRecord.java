package cn.zhishi.stock.system.idempotency;

/**
 * 一次幂等写入的记录。
 *
 * @param requestBody 首次提交时的请求体（规范化后的 JSON 文本）
 * @param responseJson 首次提交返回的响应数据（序列化后的 JSON 文本）
 */
public record IdempotencyRecord(String requestBody, String responseJson) {
}
