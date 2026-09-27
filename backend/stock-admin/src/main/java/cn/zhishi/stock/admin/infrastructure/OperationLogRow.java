package cn.zhishi.stock.admin.infrastructure;

import java.time.LocalDateTime;

/**
 * {@code sys_log} 的一行（原始值，未脱敏）。
 *
 * <p>这是列表与详情**共用的**行对象：两者的列是同一组，差别只在用例层挑哪些字段往外给。
 * 拆成两个行对象就要写两条 SQL，而"详情比列表多一列"这种差异在下次加字段时
 * 极容易只在其中一条上实现。
 *
 * <p>不出 {@code infrastructure} 包——脱敏发生在仓储把它映射成
 * {@code OperationLogDetail} 的时候。
 */
public record OperationLogRow(
        long logId,
        Long userId,
        String legacyUserRef,
        String username,
        String operation,
        Integer durationMillis,
        String method,
        String requestUri,
        String httpMethod,
        String resultStatus,
        String paramsSummary,
        String ip,
        String traceId,
        LocalDateTime createdAt) {
}
