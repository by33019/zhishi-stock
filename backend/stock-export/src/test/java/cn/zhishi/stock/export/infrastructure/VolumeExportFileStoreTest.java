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
   * {@code fileName} 也参与路径拼接，虽然由服务端拼出，但仍不例外。
   *
   * <p>要区分两种"带斜杠"，它们**失败的原因不同**：
   * <ul>
   *   <li>{@code ../escape.xlsx} 会把落笔位置挪出作业目录 → 被路径守卫直接拒绝；</li>
   *   <li>{@code a/b.xlsx} 只是作业目录里的一个子路径，并没有跑出作业目录，
   *       它失败是因为中间目录不存在——这是 IO 失败，不是越权被挡。</li>
   * </ul>
   * 真正的不变量是"**作业目录之外永远不会多出东西**"，所以除了断言各自的失败原因，
   * 还要核对目录本身没被写出别的东西。
   */
  @Test
  void rejectsFileNamesThatWouldEscapeTheExportDirectory() {
    VolumeExportFileStore store = new VolumeExportFileStore(root);

    for (String name : List.of("../escape.xlsx", "..\\escape.xlsx", "../../escape.xlsx")) {
      assertThatThrownBy(() -> store.write("1", name, new byte[0]))
          .as("越出作业目录的 fileName=%s 应被路径守卫拒绝", name)
          .isInstanceOf(IllegalArgumentException.class);
    }

    assertThatThrownBy(() -> store.write("1", "a/b.xlsx", new byte[0]))
        .as("子路径文件名会因缺少中间目录而写入失败")
        .isInstanceOf(IllegalStateException.class);

    assertThat(root.resolve("escape.xlsx")).doesNotExist();
    assertThat(entryNamesIn(root)).containsExactly("1");
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
