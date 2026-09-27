package cn.zhishi.stock.admin.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 联系方式脱敏（契约 §16.1："普通管理员看不到完整联系方式"）。
 *
 * <p>重点不在"遮了几个字符"，而在**空值与有值必须可区分**：
 * 一个统一返回 {@code "***"} 的实现会让"账号没填邮箱"看起来像"填了但看不到"，
 * 于是 ADM-USR-07 会把一次"送不出去"的重置请求记成成功。
 */
class MaskedContactTest {

    @Test
    void keepsTheDomainButHidesTheLocalPart() {
        assertThat(MaskedContact.email("analyst@example.com")).isEqualTo("a***@example.com");
        assertThat(MaskedContact.email("a@b.cn")).isEqualTo("a***@b.cn");
    }

    @Test
    void keepsThePrefixAndSuffixOfAPhoneNumber() {
        assertThat(MaskedContact.phone("13800001111")).isEqualTo("138****1111");
    }

    @Test
    void returnsNullForMissingValuesInsteadOfAMask() {
        assertThat(MaskedContact.email(null)).isNull();
        assertThat(MaskedContact.email("   ")).isNull();
        assertThat(MaskedContact.phone(null)).isNull();
        assertThat(MaskedContact.phone("")).isNull();
    }

    /** 形状不可判断的输入整串遮掉，而不是猜它的结构（猜错就会露出真实片段）。 */
    @Test
    void hidesUnrecognisableValuesEntirely() {
        assertThat(MaskedContact.email("not-an-email")).isEqualTo("***");
        assertThat(MaskedContact.email("@leading.com")).isEqualTo("***");
        assertThat(MaskedContact.phone("1234567")).isEqualTo("***");
    }
}
