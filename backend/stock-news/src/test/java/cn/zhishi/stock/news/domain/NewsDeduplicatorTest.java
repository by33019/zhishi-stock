package cn.zhishi.stock.news.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link NewsDeduplicator} 的真值表测试。
 *
 * <p>它守着 {@code ck_stock_news_canonical} 那条库约束：
 * {@code ORIGINAL ⇒ canonical IS NULL}、{@code DUPLICATE ⇒ canonical IS NOT NULL 且 ≠ 自身}。
 * 库约束是第二道防线，第一道在这里——因为库报错时给出的信息是约束名，
 * 而不是"哪条分支写错了"。
 */
class NewsDeduplicatorTest {

    @Test
    @DisplayName("库里没有同指纹主记录 → ORIGINAL 且 canonicalNewsId 为空")
    void marksOriginalWhenNoCanonicalExists() {
        NewsDeduplicator.Decision decision = NewsDeduplicator.decide("fingerprint-a", null);

        assertThat(decision.status()).isEqualTo(NewsDedupStatus.ORIGINAL);
        assertThat(decision.canonicalNewsId()).isNull();
    }

    @Test
    @DisplayName("库里已有同指纹主记录 → DUPLICATE 且指向该主记录")
    void marksDuplicateWhenCanonicalExists() {
        NewsDeduplicator.Decision decision = NewsDeduplicator.decide("fingerprint-a", 4242L);

        assertThat(decision.status()).isEqualTo(NewsDedupStatus.DUPLICATE);
        assertThat(decision.canonicalNewsId()).isEqualTo(4242L);
    }

    @Test
    @DisplayName("同一指纹反复判定结果相同：判定不依赖调用次数或时间")
    void isDeterministic() {
        assertThat(NewsDeduplicator.decide("fingerprint-a", 7L))
                .isEqualTo(NewsDeduplicator.decide("fingerprint-a", 7L));
        assertThat(NewsDeduplicator.decide("fingerprint-a", null))
                .isEqualTo(NewsDeduplicator.decide("fingerprint-a", null));
    }

    @Test
    @DisplayName("ORIGINAL 却带 canonicalNewsId → 构造即拒绝，不留到 INSERT 才炸")
    void rejectsOriginalWithCanonical() {
        assertThatThrownBy(() -> new NewsDeduplicator.Decision(NewsDedupStatus.ORIGINAL, 9L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("自相矛盾");
    }

    @Test
    @DisplayName("DUPLICATE 却没有 canonicalNewsId → 构造即拒绝")
    void rejectsDuplicateWithoutCanonical() {
        assertThatThrownBy(() -> new NewsDeduplicator.Decision(NewsDedupStatus.DUPLICATE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("自相矛盾");
    }
}
