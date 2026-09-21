package cn.zhishi.stock.ai.domain;

/**
 * 会话消息角色（{@code ai_message.role_type}）。
 *
 * <p>{@code SYSTEM} 在本轮**从不写入**：契约 HIS-05 要求"不返回 SYSTEM 内部 Prompt"，
 * 而"写进去但不返回"需要在读取侧永远记得过滤。不写才是结构性保证。
 */
public enum AiMessageRole {
    /** 用户问题。 */
    USER,
    /** 助手回答（最终报告正文）。 */
    ASSISTANT,
    /** 系统状态说明。本轮不写入。 */
    SYSTEM
}
