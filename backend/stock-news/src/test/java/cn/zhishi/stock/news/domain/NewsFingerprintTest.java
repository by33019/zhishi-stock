package cn.zhishi.stock.news.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link NewsFingerprint} 的口径测试。
 *
 * <p>指纹是"内容指纹幂等"这条验收标准的唯一实现：清洗口径错一点，
 * 同一篇稿件就会算出两个指纹、跨来源去重全部失效——而且**不会报错**，
 * 只会让列表里出现两条一模一样的内容。因此这里逐条钉住清洗行为。
 */
class NewsFingerprintTest {

    @Test
    @DisplayName("指纹是 64 位小写十六进制，与 char(64) 列一一对应")
    void producesSixtyFourLowercaseHexChars() {
        String fingerprint = NewsFingerprint.of("标题", "摘要");

        assertThat(fingerprint).hasSize(64);
        assertThat(fingerprint).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("同输入同输出：指纹必须确定性")
    void isDeterministic() {
        assertThat(NewsFingerprint.of("标题", "摘要"))
                .isEqualTo(NewsFingerprint.of("标题", "摘要"));
    }

    @Test
    @DisplayName("空白差异不改变指纹：换行、制表、全角空格、多余空格一律折叠")
    void ignoresWhitespaceDifferences() {
        String baseline = NewsFingerprint.of("标题", "摘要内容");

        assertThat(NewsFingerprint.of(" 标题 ", "摘要内容")).isEqualTo(baseline);
        assertThat(NewsFingerprint.of("标题", "摘要\n内容")).isEqualTo(baseline);
        assertThat(NewsFingerprint.of("标题", "摘要\t\t内容")).isEqualTo(baseline);
        assertThat(NewsFingerprint.of("标题", "摘要\u3000内容")).isEqualTo(baseline);
        assertThat(NewsFingerprint.of("标题", "摘要   内容")).isEqualTo(baseline);
    }

    @Test
    @DisplayName("HTML 标签差异不改变指纹：来源侧摘要常带 <p> / <em>")
    void ignoresHtmlTags() {
        assertThat(NewsFingerprint.of("标题", "<p>摘要<em>内容</em></p>"))
                .isEqualTo(NewsFingerprint.of("标题", "摘要内容"));
    }

    @Test
    @DisplayName("零宽字符差异不改变指纹：它们不可见却会让字符串不相等")
    void ignoresZeroWidthCharacters() {
        assertThat(NewsFingerprint.of("标\u200b题", "摘\ufeff要\u200d内容"))
                .isEqualTo(NewsFingerprint.of("标题", "摘要内容"));
    }

    @Test
    @DisplayName("实质内容不同则指纹不同：不做相似度容忍")
    void distinguishesDifferentContent() {
        assertThat(NewsFingerprint.of("标题", "摘要内容"))
                .isNotEqualTo(NewsFingerprint.of("标题", "摘要内容不同"));
        assertThat(NewsFingerprint.of("标题甲", "摘要内容"))
                .isNotEqualTo(NewsFingerprint.of("标题乙", "摘要内容"));
    }

    @Test
    @DisplayName("大小写不折叠：'ABC' 与 'abc' 是不同内容，误判比漏判更难发现")
    void doesNotFoldCase() {
        assertThat(NewsFingerprint.of("ABC", "摘要"))
                .isNotEqualTo(NewsFingerprint.of("abc", "摘要"));
    }

    @Test
    @DisplayName("摘要为空：null 与空串、纯空白同指纹")
    void treatsBlankSummaryAsEmpty() {
        String nullSummary = NewsFingerprint.of("标题", null);

        assertThat(NewsFingerprint.of("标题", "")).isEqualTo(nullSummary);
        assertThat(NewsFingerprint.of("标题", "   ")).isEqualTo(nullSummary);
    }

    @Test
    @DisplayName("标题与摘要不串味：把摘要搬到标题位置会得到不同指纹")
    void doesNotMixTitleAndSummary() {
        assertThat(NewsFingerprint.of("甲", "乙")).isNotEqualTo(NewsFingerprint.of("乙", "甲"));
    }
}
