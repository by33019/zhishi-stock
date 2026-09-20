package cn.zhishi.stock.news.domain;

import java.util.List;

/**
 * 一次采集的结果。刻意把"采集到多少"与"入库多少"分开——
 * 两者不相等正是去重生效的证据，合成一个数字会让"去重到底有没有工作"变成不可观测。
 *
 * <p>{@code limitations} 只写**本平台自己的**跳过原因（来源未登记、授权不可用、
 * 来源 ID 幂等、并发冲突），**不写 Provider 内部错误**：
 * 契约 NEWS-03 明确要求"不泄露 Provider 内部错误或配置"。
 */
public record NewsIngestionResult(
        int fetchedCount,
        int insertedCount,
        int deduplicatedCount,
        int skippedCount,
        int relationCount,
        List<String> limitations) {

    public NewsIngestionResult {
        limitations = List.copyOf(limitations);
    }

    /** 全部条目都被跳过（没有新增任何稿件）。 */
    public boolean nothingInserted() {
        return insertedCount == 0;
    }
}
