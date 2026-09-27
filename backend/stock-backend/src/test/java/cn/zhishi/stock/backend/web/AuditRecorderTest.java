package cn.zhishi.stock.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhishi.stock.common.audit.AuditEvent;
import cn.zhishi.stock.common.audit.AuditLog;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * {@link AuditRecorder} 的行为固定。
 *
 * <p>这个类被导出与后台管理两条链路共用，因此它自己的口径必须先被钉住：
 * 字段从哪来、失败记成什么、审计挂了会不会影响业务。
 */
class AuditRecorderTest {

    private static final long USER_ID = 42L;
    private static final String USERNAME = "analyst";

    private final RecordingAuditLog auditLog = new RecordingAuditLog();
    private final AuditRecorder recorder = new AuditRecorder(auditLog);

    /** 契约 §22.3：审计要能回答"谁、从哪、什么时候、请求了什么"。 */
    @Test
    void recordsWebLayerFactsOnSuccess() {
        String result = recorder.audited(
                authentication(), request("203.0.113.7, 10.0.0.1"), "EXPORT_CREATE", "type=RANKING",
                () -> "done");

        assertThat(result).isEqualTo("done");
        assertThat(auditLog.events).singleElement().satisfies(event -> {
            assertThat(event.userId()).isEqualTo(USER_ID);
            assertThat(event.username()).isEqualTo(USERNAME);
            assertThat(event.operation()).isEqualTo("EXPORT_CREATE");
            assertThat(event.resultStatus()).isEqualTo(AuditEvent.SUCCESS);
            assertThat(event.requestUri()).isEqualTo("/api/v1/export-jobs");
            assertThat(event.httpMethod()).isEqualTo("POST");
            // 取第一跳：反向代理会让 getRemoteAddr() 变成代理地址，审计价值几乎为零。
            assertThat(event.ip()).isEqualTo("203.0.113.7");
            assertThat(event.traceId()).isEqualTo("trace-1");
            assertThat(event.paramsSummary()).isEqualTo("type=RANKING");
        });
    }

    /** 失败也要留下记录，否则审计上会出现"有成功、没有失败"的空洞；异常必须原样抛出。 */
    @Test
    void recordsFailureAndRethrowsTheOriginalException() {
        IllegalStateException failure = new IllegalStateException("boom");

        assertThatThrownBy(() -> recorder.audited(
                authentication(), request(null), "ADMIN_USER_DELETE", "userId=7",
                () -> {
                    throw failure;
                }))
                .isSameAs(failure);

        assertThat(auditLog.events).singleElement()
            .extracting(AuditEvent::resultStatus)
            .isEqualTo(AuditEvent.FAILURE);
    }

    /** 各域可以自带"哪些异常算被拒"的口径，判定函数只写一处、只调一次。 */
    @Test
    void usesCallerSuppliedClassifierToMarkDenied() {
        assertThatThrownBy(() -> recorder.audited(
                authentication(), request(null), "EXPORT_CREATE", null,
                exception -> AuditEvent.DENIED,
                () -> {
                    throw new IllegalStateException("rate limited");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(auditLog.events).singleElement()
            .extracting(AuditEvent::resultStatus)
            .isEqualTo(AuditEvent.DENIED);
    }

    /**
     * 判定函数返回 null 时回退为 {@code FAILURE}。
     *
     * <p>{@code result_status} 是"这次操作结果如何"的唯一答案，写进去一个空值
     * 等于把这个事实丢掉，且查日志时无法与之区分。
     */
    @Test
    void fallsBackToFailureWhenClassifierReturnsNull() {
        assertThatThrownBy(() -> recorder.audited(
                authentication(), request(null), "ADMIN_JOB_TRIGGER", null,
                exception -> null,
                () -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(auditLog.events).singleElement()
            .extracting(AuditEvent::resultStatus)
            .isEqualTo(AuditEvent.FAILURE);
    }

    /**
     * 审计是**旁路事实**：它自己出问题，既不能改变成功的结果，也不能顶替原始异常。
     *
     * <p>两条路都要测：只在成功路上兜底的话，"审计抛异常 + 业务也失败"时
     * 用户会看到一个审计异常，而真正的原因被吞掉。
     */
    @Test
    void keepsBusinessOutcomeWhenAuditLogItselfThrows() {
        AuditRecorder broken = new AuditRecorder(event -> {
            throw new IllegalStateException("sys_log 不可用");
        });

        assertThat(broken.audited(authentication(), request(null), "EXPORT_DOWNLOAD", null,
                () -> "content"))
            .isEqualTo("content");

        IllegalStateException business = new IllegalStateException("业务失败");
        assertThatThrownBy(() -> broken.audited(
                authentication(), request(null), "EXPORT_DOWNLOAD", null,
                () -> {
                    throw business;
                }))
                .isSameAs(business);
    }

    /** 后台的定时执行、异步生成等场景没有登录上下文：记 0 / null，而不是编一个用户。 */
    @Test
    void recordsPlaceholdersWhenThereIsNoAuthentication() {
        recorder.audited(null, request(null), "EXPORT_GENERATE", "exportId=1", () -> "ok");

        assertThat(auditLog.events).singleElement().satisfies(event -> {
            assertThat(event.userId()).isZero();
            assertThat(event.username()).isNull();
            assertThat(event.ip()).isEqualTo("127.0.0.1");
        });
    }

    /** 没有（或只有空白的）转发头时回落到 {@code getRemoteAddr()}，而不是记一个空 IP。 */
    @Test
    void fallsBackToRemoteAddressWithoutForwardedHeader() {
        recorder.audited(authentication(), request("   "), "EXPORT_DELETE", null, () -> "ok");

        assertThat(auditLog.events).singleElement()
            .extracting(AuditEvent::ip)
            .isEqualTo("127.0.0.1");
    }

    private static Authentication authentication() {
        AccessTokenPrincipal principal = new AccessTokenPrincipal(
                USER_ID, USERNAME, Set.of("sys:user:list"), "jti-1",
                Instant.parse("2030-01-01T00:00:00Z"), 3);
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    private static MockHttpServletRequest request(String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/export-jobs");
        request.setRemoteAddr("127.0.0.1");
        request.setAttribute(TraceIdFilter.ATTRIBUTE, "trace-1");
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    private static final class RecordingAuditLog implements AuditLog {

        private final List<AuditEvent> events = new ArrayList<>();

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }
    }
}
