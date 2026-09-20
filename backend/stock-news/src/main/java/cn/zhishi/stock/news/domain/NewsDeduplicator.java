package cn.zhishi.stock.news.domain;

/**
 * 跨来源去重判定：给定内容指纹与"库里已有的同指纹主记录"，产出本条稿件应有的
 * {@code dedup_status} 与 {@code canonical_news_id}。
 *
 * <h2>为什么判定要独立成一个纯函数</h2>
 * {@code ck_stock_news_canonical} 把不变量写进了库约束：
 * {@code ORIGINAL ⇒ canonical IS NULL}、{@code DUPLICATE ⇒ canonical IS NOT NULL 且 ≠ 自身}。
 * 若判定散落在采集服务里，某天有人加一条"指纹相同但来源不同时也当 ORIGINAL"的分支，
 * 库会拒绝写入——那时错误信息指向的是约束名，而不是那条新分支。
 * 收到这里之后，判定只有一处，测试可以直接断言这张真值表。
 *
 * <h2>它不管什么</h2>
 * 它**不做**"来源 ID 幂等"——那是同一份稿件被重复投递（采集重试、游标回退），
 * 结果是**整条跳过**、不新增记录，与"另一家媒体也发了"是两件事。见 spec §3.4。
 */
public final class NewsDeduplicator {

    /**
     * 去重结论。
     *
     * @param status          {@code ORIGINAL} 或 {@code DUPLICATE}
     * @param canonicalNewsId 仅当 {@code status} 为 {@code DUPLICATE} 时非空
     */
    public record Decision(NewsDedupStatus status, Long canonicalNewsId) {

        public Decision {
            boolean duplicate = status == NewsDedupStatus.DUPLICATE;
            if (duplicate != (canonicalNewsId != null)) {
                // 构造即校验：让"状态与主记录 id 不匹配"在产生的那一行就炸掉，
                // 而不是等到 INSERT 被 ck_stock_news_canonical 拒绝。
                throw new IllegalArgumentException(
                        "去重结论自相矛盾：status=" + status + ", canonicalNewsId=" + canonicalNewsId);
            }
        }
    }

    private NewsDeduplicator() {
    }

    /**
     * 判定一条新稿件的去重状态。
     *
     * @param fingerprint            本条稿件的内容指纹
     * @param existingOriginalNewsId 库里同指纹的**主记录** id；没有则为 {@code null}
     */
    public static Decision decide(String fingerprint, Long existingOriginalNewsId) {
        if (fingerprint == null || fingerprint.isBlank()) {
            // 空指纹会让"第一个空指纹的稿件"成为之后所有空指纹稿件的 canonical，
            // 把一批互不相关的资讯折叠成一条。这里必须拒绝，不能靠调用方自觉。
            throw new IllegalArgumentException("内容指纹不可为空：它是去重判定的键");
        }
        if (existingOriginalNewsId == null) {
            return new Decision(NewsDedupStatus.ORIGINAL, null);
        }
        return new Decision(NewsDedupStatus.DUPLICATE, existingOriginalNewsId);
    }
}
