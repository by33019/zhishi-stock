package cn.zhishi.stock.export.domain;

/**
 * 取数端口：把一次请求变成一张待写出的表。
 *
 * <p>行数上限（5,000）由实现方在拿到全量结果后判定并抛
 * {@code ExportException(EXPORT_LIMIT_EXCEEDED)}，而不是在这里切一刀：
 * 静默截断会让用户以为"导全了"，而 PRD EX-24 的处置是"超过 5,000 行不生成，提示缩小范围"。
 */
public interface ExportDataSource {

    ExportTable tableOf(ExportRequest request);
}
