package cn.zhishi.stock.export.domain;

import java.util.Optional;

/**
 * 导出文件的存放端口。
 *
 * <p>契约 §9.2 允许"受控 Docker Volume 或对象存储"，所以这里只定义动作，
 * 不定义位置：换成对象存储时改实现，用例层一行不动。
 *
 * <p>键是 {@code exportId} 而不是文件名——文件名由用户可见的榜单类型与时间拼成，
 * 会随措辞变化；用它当存储键，改一次文案就找不到旧文件了。
 */
public interface ExportFileStore {

    /** 写入一份文件。同名覆盖（同一个作业只会写一次）。 */
    void write(String exportId, String fileName, byte[] content);

    /** 读回文件内容；不存在时返回空。 */
    Optional<byte[]> read(String exportId, String fileName);

    /**
     * 删除该作业的全部文件。
     *
     * <p><b>幂等</b>：文件不存在不算失败（重复清理、用户重复删除都不该报错）。
     * 但**真实的 IO 失败必须抛出去**——吞掉它，调用方会接着删掉作业记录，
     * 磁盘上就留下一份再也不会被清理的孤儿文件（记录没了，清理任务再也找不到它）。
     */
    void delete(String exportId);
}
