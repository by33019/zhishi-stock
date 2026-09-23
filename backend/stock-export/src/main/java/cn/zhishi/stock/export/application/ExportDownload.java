package cn.zhishi.stock.export.application;

import java.time.OffsetDateTime;

/**
 * 下载结果：文件内容 + 对外响应头需要的东西。
 *
 * <p>契约 §9.2 EXP-03 要求响应头带 {@code Content-Disposition}、
 * {@code X-Data-Cutoff-At}、{@code X-Trace-Id}。前两个的数据就来自这里；
 * {@code X-Trace-Id} 由 Web 层从当前请求取。
 *
 * <p>{@code dataCutoffAt} 之所以跟着文件一起返回，是为了让**响应头里的数据时间**
 * 与文件说明区里的那一行同源——两处各取一次是这类"看起来对"的分叉的典型来源。
 */
public record ExportDownload(byte[] content, String fileName, OffsetDateTime dataCutoffAt) {
}
