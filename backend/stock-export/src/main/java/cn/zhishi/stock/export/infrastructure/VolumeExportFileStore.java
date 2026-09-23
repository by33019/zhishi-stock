package cn.zhishi.stock.export.infrastructure;

import cn.zhishi.stock.export.domain.ExportFileStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ExportFileStore} 的本地卷实现（契约 §9.2 的"受控 Docker Volume"）。
 *
 * <h2>目录形状</h2>
 * <pre>
 *   &lt;root&gt;/&lt;exportId&gt;/&lt;fileName&gt;.xlsx
 * </pre>
 * 一个作业一个目录，而不是所有文件平铺在根目录下：
 * 删除一个作业就是删一个目录，不会误伤别的作业；
 * 而"按 {@code exportId} 前缀匹配文件名"的做法在 id 互为前缀时会删错（{@code 12} 与 {@code 123}）。
 *
 * <h2>{@code exportId} 与 {@code fileName} 都必须过白名单才允许拼进路径</h2>
 * 路径拼接是**唯一**能把外部输入变成文件系统操作的地方，所以两个片段都按字符集白名单收口：
 * id 只接受 {@code [A-Za-z0-9_-]}，文件名只接受以字母/数字开头、其余为 {@code [A-Za-z0-9._-]}。
 * 少了这道门，一个含 {@code ../} 的 id 就能让"删除我的导出"变成"删除任意目录"。
 *
 * <h2>为什么判据是字符集，而不是"解析后是否仍在目录内"</h2>
 * {@code normalize()} + {@code startsWith()} 看起来更"正统"，但它随平台给不同答案：
 * {@code "..\escape.xlsx"} 在 Windows 上会被 {@code \} 拆成穿越，在 Linux 上却只是一个
 * 普通文件名——同一份输入，一边被挡住、一边被放行。守卫的答案随操作系统变，就等于没有守卫，
 * 而且这种差异在开发机（Windows）与 CI（Linux）之间不会自己暴露出来。
 * 按字符集判，两个平台一致；代价是零，因为文件名由 {@code ExportJobService} 按固定模板拼出
 * （{@code stock-ranking-<榜单>-<时间戳>.xlsx}），本来就不含分隔符。
 *
 * <h2>删除失败要抛，而不是吞掉</h2>
 * "文件不存在"是成功的（幂等），但真实的 IO 失败必须抛出去：
 * 吞掉它，调用方就会接着把作业记录删掉，于是磁盘上留下一份**再也不会被清理**的孤儿文件
 * ——记录没了，清理任务再也找不到它。抛出后由调用方决定取舍（见 {@code ExportJobService}）。
 */
public class VolumeExportFileStore implements ExportFileStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(VolumeExportFileStore.class);

    /** {@code exportId} 的允许字符集。**只有这一处**定义它。 */
    private static final String SAFE_ID_PATTERN = "[A-Za-z0-9_-]+";

    /**
     * {@code fileName} 的允许字符集：字母/数字开头，其余可含 {@code . _ -}。
     *
     * <p>要求首字符是字母或数字，是为了顺手挡掉 {@code .}、{@code ..}、{@code .hidden}——
     * 只写"不含分隔符"的话，{@code ..} 本身就能通过。
     */
    private static final String SAFE_FILE_NAME_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]*";

    private final Path root;

    public VolumeExportFileStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** 导出根目录，供启动时打印与排查用。 */
    public Path root() {
        return root;
    }

    @Override
    public void write(String exportId, String fileName, byte[] content) {
        Path directory = directoryOf(exportId);
        // 先校验、再落笔。clearDirectory 会清掉该作业已有文件，若把校验放在它后面，
        // 一个非法文件名会先毁掉上一份好文件、再抛异常——"重建"于是变成"两头空"。
        Path target = directory.resolve(requireSafeFileName(fileName));
        try {
            Files.createDirectories(directory);
            // 先清空该作业目录再写：同一个作业理论上只写一次，但生成失败后重建过就会
            // 出现第二个文件名（名字里带时间戳）。留着旧的那份，会让"文件在哪"有两个答案。
            clearDirectory(directory);
            Files.write(target, content,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("导出文件写入失败：" + exportId, exception);
        }
    }

    @Override
    public Optional<byte[]> read(String exportId, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return Optional.empty();
        }
        Path directory = directoryOf(exportId);
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        // 目录里只会有一份文件，因此按目录取而不是按名字精确匹配：
        // 记录里的 fileName 与磁盘上的那一刻若因改名/改文案而不一致，
        // 按目录取仍能拿到唯一的那份，而按名字取会误报"文件不存在"。
        try (Stream<Path> entries = Files.list(directory)) {
            Path only = entries.filter(Files::isRegularFile).findFirst().orElse(null);
            return only == null ? Optional.empty() : Optional.of(Files.readAllBytes(only));
        } catch (IOException exception) {
            LOGGER.error("导出文件读取失败：exportId={}", exportId, exception);
            return Optional.empty();
        }
    }

    @Override
    public void delete(String exportId) {
        Path directory = directoryOf(exportId);
        if (!Files.exists(directory)) {
            // 幂等：不存在就是已经删掉了。
            return;
        }
        try {
            deleteRecursively(directory);
        } catch (IOException exception) {
            throw new IllegalStateException("导出文件删除失败：" + exportId, exception);
        }
    }

    /** 目录里只保留"将要写入的那一份"，其余先清掉。 */
    private void clearDirectory(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                deleteRecursively(entry);
            }
        }
    }

    /**
     * 递归删除。
     *
     * <p>{@code Files.walk} + 逆序（深的先删）比手写递归短，且**不跟随符号链接**
     * （默认行为），因此卷里若被人放了指向目录外的软链，不会被顺着删出去。
     */
    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path each : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(each);
            }
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    /**
     * 校验片段后原样返回，调用方直接拿去 {@code resolve}。
     *
     * <p>只判字符集，不做 {@code normalize}/{@code startsWith}：原因见类注释——
     * "解析后是否仍在目录内"这个判据随平台变，而白名单不随平台变。
     */
    private static String requireSafeFileName(String fileName) {
        if (fileName == null || !fileName.matches(SAFE_FILE_NAME_PATTERN)) {
            throw new IllegalArgumentException("非法导出文件名：" + fileName);
        }
        return fileName;
    }

    /**
     * 作业目录。
     *
     * <p>每次调用都重新过一遍白名单，而不是只在入口校验一次：{@code read} / {@code delete}
     * 也走这里，任何一条都可能是外部输入的落点。
     */
    private Path directoryOf(String exportId) {
        if (exportId == null || !exportId.matches(SAFE_ID_PATTERN)) {
            throw new IllegalArgumentException("非法导出标识：" + exportId);
        }
        return root.resolve(exportId);
    }
}
