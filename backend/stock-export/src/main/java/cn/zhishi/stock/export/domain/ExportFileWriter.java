package cn.zhishi.stock.export.domain;

/**
 * 写出端口：把表变成一份 xlsx 字节。
 *
 * <p>返回字节而不是直接写文件：写到哪里由 {@link ExportFileStore} 决定，
 * 而"怎么把表排成格子"是纯粹的格式问题。两者合一之后，
 * "换存储"与"换 Excel 库"会变成同一件事。
 */
public interface ExportFileWriter {

    byte[] write(ExportTable table);
}
