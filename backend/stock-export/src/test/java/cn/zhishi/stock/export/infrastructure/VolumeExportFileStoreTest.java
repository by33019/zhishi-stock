package cn.zhishi.stock.export.infrastructure;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本地卷导出文件存储（契约 §9.2 的"受控 Docker Volume"）。
 *
 * <p>{@code exportId} / {@code fileName} 会被拼进文件系统路径，这是本模块里
 * **唯一**能把外部输入变成文件系统操作的地方，所以"穿越"是这里的一等用例，
 * 而不是边角料。
 */
class VolumeExportFileStoreTest {

  @TempDir
  Path root;

  @Test
  void writesAndReadsBackTheContent() {
    VolumeExportFileStore store = new VolumeExportFileStore(root);

    store.write("1", "ranking.xlsx", "内容".getBytes(UTF_8));

    assertThat(store.read("1", "ranking.xlsx")).contains("内容".getBytes(UTF_8));
  }

  @Test
  void createsOneDirectoryPerExport() {
    VolumeExportFileStore store = new VolumeExportFileStore(root);

    store.write("1", "a.xlsx", new byte[] {1});
    store.write("2", "b.xlsx", new byte[] {2});

    assertThat(root.resolve("1")).isDirectory();
    assertThat(root.resolve("2")).isDirectory();
  }

  /**
   * 按**目录**取而不是按文件名精确匹配：目录里只会有一份文件，
   * 因此记录里的 {@code fileName} 与磁盘上的那一刻若不再一致，仍能拿到唯一的那份，
   * 而按名字取会误报"文件不存在"。
   */
  @Test
  void readsTheSingleFileInTheDirectoryEvenIfTheNameDiffers() {
    VolumeExportFileStore store = new VolumeExportFileStore(root);
    store.write("1", "ranking-gainers.xlsx", "内容".getBytes(UTF_8));

    assertThat(store.read("1", "a-name-from-a-different-version.xlsx"))
        .contains("内容".getBytes(UTF_8));
  }

  @Test
  void returnsEmptyForUnknownExportOrBlankFileName() {
    VolumeExportFileStore store = new VolumeExportFileStore(root);

    assertThat(store.read("42", "x.xlsx")).isEmpty();
    assertThat(store.read("1", null)).isEmpty();
    assertThat(store.read("1", "  ")).isEmpty();
  }

  @Test
  void returnsEmptyBeforeAnythingIsWritten() {
    assertThat(new VolumeExportFileStore(root).read("1", "x.xlsx")).isEmpty();
  }

  /** 同一个作业重写（生成失败后重建）只该留下一份文件，否则"文件在哪"会有两个答案。 */
  @Test
  void rewritingKeepsOnlyTheLatestFile() throws IOException {
    VolumeExportFileStore store = new VolumeExportFileStore(root);
    store.write("1", "first.xlsx", new byte[] {1});

    store.write("1", "second.xlsx", new byte[] {2});

    try (Stream<Path> entries = Files.list(root.resolve("1"))) {
      assertThat(entries.map(path -> path.getFileName().toString()))
          .containsExactly("second.xlsx");
    }
    assertThat(store.read("1", "second.xlsx")).contains(new byte[] {2});
  }

  // ---------- 路径穿越 ----------

  /** 少了这道门，一个含 {@code ../} 的 id 就能让"删除我的导出"变成"删除任意目录"。 */
  @Test
  void rejectsExportIdsThatCouldEscapeTheRoot() {
    VolumeExportFileStore store = new VolumeExportFileStore(root);

    for (String id : List.of(
        "..", "../etc", "../../etc/passwd", "a/b", "a\\b", "/absolute", "C:\\windows",
        "1/../2", "a b", "1;rm -rf", "", "1.xlsx", "%2e%2e")) {
      assertThatThrownBy(() -> store.write(id, "x.xlsx", new byte[0]))
          .as("write 应拒绝 id=%s", id)
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> store.read(id, "x.xlsx"))
          .as("read 应拒绝 id=%s", id)
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> store.delete(id))
          .as("delete 应拒绝 id=%s", id)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void acceptsTheCharactersTheServiceActuallyGenerates() {
    VolumeExportFileStore store = new VolumeExportFileStore(root);

    for (String id : List.of("1", "1234567890", "a-b_c", "ABC")) {
      store.write(id, "x.xlsx", new byte[] {1});
      assertThat(store.read(id, "x.xlsx")).isPresent();
    }
  }

  /**
   * {@code fileName} 也参与路径拼接，虽然由 {@code ExportJobService} 按固定模板拼出，
   * 但仍不例外。
   *
   * <p>这里断言的是"**作业目录之外永远不会多出东西**"这条不变量本身，
   * <b>而不是某个平台对 {@code \} 的处理</b>：{@code ..\escape.xlsx} 在 Windows 上会被
   * {@code \} 拆成穿越，在 Linux 上却只是一个普通文件名——同一条用例在两个平台上会得出
   * 相反结论（本仓库正是这样在 Linux CI 上挂的）。所以改用一批**两种平台都认得是越权**的
   * 名字，把平台差异挡在断言之外。
   */
  @Test
  void rejectsFileNamesThatWouldEscapeTheExportDirectory() {
    VolumeExportFileStore store = new VolumeExportFileStore(root);

    for (String name : List.of(
        "../escape.xlsx", "..\\escape.xlsx", "../../escape.xlsx", "1/../../escape.xlsx",
        "a/b.xlsx", "..", ".hidden.xlsx")) {
      assertThatThrownBy(() -> store.write("1", name, new byte[0]))
          .as("非法 fileName=%s 应被路径守卫拒绝", name)
          .isInstanceOf(IllegalArgumentException.class);
    }

    // 磁盘上不该留下任何痕迹：作业目录之外没有逃逸出来的文件，
    // 作业目录本身也不该被建出来（校验前置于落笔，见 VolumeExportFileStore#write）。
    assertThat(root.resolve("escape.xlsx")).doesNotExist();
    assertThat(entryNamesIn(root)).isEmpty();
  }

  /**
   * 校验必须发生在清理之前。
   *
   * <p>否则一次非法的重建请求会先把上一份好文件删掉、再抛异常，
   * 用户看到的是"重新生成把原来那份也弄没了"——而失败本该是**不动存量**的。
   */
  @Test
  void anIllegalFileNameDoesNotDestroyTheExistingFile() {
    VolumeExportFileStore store = new VolumeExportFileStore(root);
    store.write("1", "good.xlsx", "好文件".getBytes(UTF_8));

    assertThatThrownBy(() -> store.write("1", "../escape.xlsx", new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(store.read("1", "good.xlsx")).contains("好文件".getBytes(UTF_8));
  }

  private static List<String> entryNamesIn(Path directory) {
    try (Stream<Path> entries = Files.list(directory)) {
      return entries.map(path -> path.getFileName().toString()).sorted().toList();
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  // ---------- 删除 ----------

  @Test
  void deleteRemovesTheWholeExportDirectoryAndIsIdempotent() {
    VolumeExportFileStore store = new VolumeExportFileStore(root);
    store.write("1", "a.xlsx", new byte[] {1});

    store.delete("1");
    assertThat(root.resolve("1")).doesNotExist();

    // 幂等：不存在就是已经删掉了，不该抛。
    store.delete("1");
  }

  @Test
  void rootIsNormalizedToAnAbsolutePath() {
    assertThat(new VolumeExportFileStore(root).root()).isEqualTo(root.toAbsolutePath().normalize());
  }
}
