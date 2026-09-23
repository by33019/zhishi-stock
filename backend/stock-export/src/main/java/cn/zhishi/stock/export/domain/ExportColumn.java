package cn.zhishi.stock.export.domain;

/**
 * 一列的对外标识、表头与显示格式。
 *
 * <p>{@code key} 是请求里能出现的取值（白名单），{@code header} 是文件里的中文表头，
 * {@code format} 是这一列怎么显示。三者分开是必需的：
 * {@code key} 是接口契约的一部分，改了是破坏性变更；表头是给人看的，
 * 允许在不影响接口的前提下调整措辞；而格式跟着语义走（见 {@link ExportColumnFormat}）。
 */
public record ExportColumn(String key, String header, ExportColumnFormat format) {
}
