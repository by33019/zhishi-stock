package cn.zhishi.stock.ai.application;

/**
 * AI-02 请求里的一个目标。
 *
 * <p>{@code targetType} / {@code targetRole} 声明为 {@code String} 而不是枚举：
 * 枚举绑定失败会被 Spring 转成 {@code MethodArgumentTypeMismatchException}，
 * 语义上把"取值不在白名单"混同为"参数格式错误"。由用例层解析，
 * 与 STK-01 / NEWS-01 等既有接口同一处理方式。
 *
 * @param targetType 目标类型码：{@code MARKET} / {@code SECTOR} / {@code SECURITY}
 * @param targetId   目标对外标识：{@code CN} / {@code sim-bk0001} / {@code sim-600519}
 * @param targetRole 目标角色码：{@code PRIMARY} / {@code COMPARISON}
 */
public record AiTargetRequest(String targetType, String targetId, String targetRole) {
}
