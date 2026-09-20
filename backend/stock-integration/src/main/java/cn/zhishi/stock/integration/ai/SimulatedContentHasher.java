package cn.zhishi.stock.integration.ai;

import cn.zhishi.stock.ai.domain.AiContentHasher;
import cn.zhishi.stock.integration.market.SimulatedHashing;
import java.util.Map;
import java.util.TreeMap;

/**
 * 确定性内容哈希。
 *
 * <p>用 {@link SimulatedHashing}（SplitMix64）派生 64 个十六进制字符，与
 * {@code ai_context_snapshot.content_hash} / {@code ai_evidence.content_hash} 的
 * {@code char(64)} 列宽对齐。
 *
 * <p><b>它不是密码学安全的</b>，用途只是"同一份事实是否被重复固化"与
 * "报告引用的证据是否与固化时一致"，不是防篡改。真实环境应换成 SHA-256——
 * 端口（{@link AiContentHasher}）已就位，替换实现类即可。
 *
 * <p>{@link #hashOf(Map)} 按 key 排序后再计算：否则"内容相同"会退化成
 * "插入顺序相同"，而那是实现细节，不是事实。
 */
public class SimulatedContentHasher implements AiContentHasher {

    @Override
    public String hashOf(Map<String, ?> content) {
        return hashOfText(String.valueOf(new TreeMap<>(content)));
    }

    @Override
    public String hashOfText(String text) {
        String source = text == null ? "" : text;
        long first = SimulatedHashing.mix(source.hashCode() * 31L + 1L);
        long second = SimulatedHashing.mix(first ^ 0x2L);
        long third = SimulatedHashing.mix(second ^ 0x3L);
        long fourth = SimulatedHashing.mix(third ^ 0x4L);
        return String.format("%016x%016x%016x%016x", first, second, third, fourth);
    }
}
