package cn.zhishi.stock.ai.domain;

import java.util.Map;

/**
 * 内容哈希端口。
 *
 * <p>哈希用于两个地方：{@code ai_context_snapshot.content_hash} 与
 * {@code ai_evidence.content_hash}（都是 {@code char(64)}）。用途是
 * "同一份事实是否被重复固化"与"报告引用的证据是否与固化时一致"，**不是**防篡改。
 *
 * <p>端口而不是静态工具类：真实实现应换成 SHA-256（当前模拟实现用 {@code SimulatedHashing}，
 * 非密码学安全）。替换实现类即可切换，调用方不变。
 */
public interface AiContentHasher {

    /**
     * 对结构化内容计算稳定哈希。
     *
     * <p>实现必须**与 Map 的迭代顺序无关**（按 key 排序后再计算）：
     * 同一个 {@code LinkedHashMap} 与 {@code HashMap} 若给出不同哈希，
     * "内容相同"就变成了"插入顺序相同"，而那是实现细节，不是事实。
     *
     * @return 64 个十六进制字符，与 {@code char(64)} 列宽对齐
     */
    String hashOf(Map<String, ?> content);

    /** 对文本计算稳定哈希。{@code null} 与空串视为同一内容。 */
    String hashOfText(String text);
}
