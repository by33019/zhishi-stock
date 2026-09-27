package cn.zhishi.stock.admin.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 读日志时的二次脱敏（契约 §16.2 LOG-02）。
 *
 * <h2>为什么单独一个测试类</h2>
 * 脱敏是纯函数，规则多、边界碎，混在 {@code OperationLogServiceTest} 里会被分页与
 * 时间范围的用例盖过去。而它又是"会不会把密码泄露出去"的唯一防线，值得逐条列出。
 *
 * <h2>断言的是"值不见了"，不是"字符串长这样"</h2>
 * 多数用例先断言原文里的敏感片段**不存在**，再断言 {@code ***} 出现了。
 * 只断言结果等于某个期望字符串，会在实现改成分号换成逗号时全绿——
 * 而那正是最需要被发现的时刻。
 */
class SensitiveParamsRedactorTest {

    private final SensitiveParamsRedactor redactor = new SensitiveParamsRedactor();

    @Test
    void masksTheValueOfEveryKnownSensitiveKey() {
        String params = redactor.redact(
                "userId=7001;password=Temp@12345;access_token=abc.def;apiKey=sk-1;"
                        + "nickName=小新");

        assertThat(params).doesNotContain("Temp@12345", "abc.def", "sk-1");
        assertThat(params).contains("password=" + SensitiveParamsRedactor.MASK);
        assertThat(params).contains("access_token=" + SensitiveParamsRedactor.MASK);
        assertThat(params).contains("apiKey=" + SensitiveParamsRedactor.MASK);
        assertThat(params)
                .describedAs("非敏感字段必须原样保留，否则日志摘要就没有价值了")
                .contains("userId=7001", "nickName=小新");
    }

    /** 键名的四种写法必须等价：漏掉一种，漏掉的这一种就是未来那次泄露。 */
    @Test
    void normalisesTheKeyBeforeComparing() {
        for (String key : new String[] {"accessToken", "access_token", "ACCESS-TOKEN", "Access Token"}) {
            assertThat(redactor.redact(key + "=secret-value"))
                    .describedAs("键名 %s 应被识别为敏感", key)
                    .isEqualTo(key + "=" + SensitiveParamsRedactor.MASK);
        }
    }

    /**
     * 键名正常不代表值安全：用户完全可能把一段 token 粘进备注。
     *
     * <p>这一条是最容易被漏掉的场景——写入侧的字段白名单只挡"按字段名进来的密码"，
     * 挡不住"密码被当文本贴进 reason"。
     */
    @Test
    void masksValuesThatLookLikeABearerTokenOrJwt() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature";

        assertThat(redactor.redact("reason=Bearer " + jwt)).doesNotContain(jwt);
        assertThat(redactor.redact("reason=" + jwt)).doesNotContain(jwt);
        assertThat(redactor.redact("reason=用户忘记密码，附上 " + jwt + " 作为凭据"))
                .doesNotContain(jwt);
        assertThat(redactor.redact("reason=正常备注"))
                .describedAs("没有触发特征的备注不该被动")
                .isEqualTo("reason=正常备注");
    }

    /**
     * 旧系统写的 JSON 请求体：键的边界与 {@code ;} 分隔的键值对不是一回事。
     *
     * <p>这类行的形状不可信，因此策略是"出现敏感词就整段打码"。
     * 断言里刻意不出现 {@code ***} 之外的任何原文片段——整段的意思就是整段。
     */
    @Test
    void masksTheWholeValueWhenTheShapeIsUntrusted() {
        assertThat(redactor.redact("{\"username\":\"admin\",\"password\":\"Temp@12345\"}"))
                .isEqualTo(SensitiveParamsRedactor.MASK);
        assertThat(redactor.redact("login failed for admin, password mismatch"))
                .isEqualTo(SensitiveParamsRedactor.MASK);
    }

    /** 形状不可信但**没有**敏感词时不能动它——否则历史行会被一片 {@code ***} 淹没。 */
    @Test
    void leavesUntrustedShapesAloneWhenNothingSensitiveIsInThem() {
        assertThat(redactor.redact("{\"username\":\"admin\",\"action\":\"login\"}"))
                .isEqualTo("{\"username\":\"admin\",\"action\":\"login\"}");
        assertThat(redactor.redact("采集任务执行完成"))
                .isEqualTo("采集任务执行完成");
    }

    /**
     * {@code code} 不在黑名单里。
     *
     * <p>它在业务里是"股票代码"这类正常字段；拉黑它会让一大类日志的摘要
     * 变成 {@code ***}，而脱敏过度与不脱敏一样有害——前者让日志失去用处的速度更快。
     * 验证码因此只认 {@code captcha} / {@code smsCode} 这类限定写法。
     */
    @Test
    void keepsBusinessFieldsThatMerelyLookSensitive() {
        assertThat(redactor.redact("code=600519;status=SUCCESS"))
                .isEqualTo("code=600519;status=SUCCESS");
        assertThat(redactor.redact("smsCode=998877"))
                .isEqualTo("smsCode=" + SensitiveParamsRedactor.MASK);
    }

    @Test
    void keepsNullAndBlankUntouched() {
        assertThat(redactor.redact(null)).isNull();
        assertThat(redactor.redact("")).isEmpty();
        assertThat(redactor.redact("   ")).isEqualTo("   ");
    }

    /**
     * 敏感键一律打码，包括值为空的时候。
     *
     * <p>看起来多此一举（空值本身不是秘密），但它把规则收敛成一句可以完整表述的话：
     * <b>敏感键的值永远不出现</b>。加上"值为空则原样保留"这条分支之后，
     * {@code password=}、{@code password= }、{@code password=null} 就要各有一套说法，
     * 而每多一种说法就多一个"没想到的那种写法"。
     */
    @Test
    void masksSensitiveKeysEvenWhenTheValueIsEmpty() {
        assertThat(redactor.redact("password="))
                .isEqualTo("password=" + SensitiveParamsRedactor.MASK);
        assertThat(redactor.redact("userId=7001;reason="))
                .describedAs("非敏感键的空值仍然原样保留")
                .isEqualTo("userId=7001;reason=");
    }

    /** 多个敏感字段并存时逐个处理，不能只处理第一个。 */
    @Test
    void masksEveryOccurrenceRatherThanJustTheFirst() {
        String params = redactor.redact("oldPassword=a1;newPassword=b2;confirmPassword=b2");

        assertThat(params).doesNotContain("a1", "b2");
        assertThat(params).isEqualTo(
                "oldPassword=" + SensitiveParamsRedactor.MASK
                        + ";newPassword=" + SensitiveParamsRedactor.MASK
                        + ";confirmPassword=" + SensitiveParamsRedactor.MASK);
    }
}
