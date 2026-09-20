package cn.zhishi.stock.system.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;

class WatchlistGroupNameTest {

  @Test
  void trimsSurroundingSpaces() {
    assertThat(WatchlistGroupName.of("  我的自选  ").value()).isEqualTo("我的自选");
  }

  @Test
  void rejectsNullEmptyAndBlankNames() {
    for (String raw : new String[] {null, "", "   ", "\t"}) {
      WatchlistException exception =
          catchThrowableOfType(() -> WatchlistGroupName.of(raw), WatchlistException.class);
      assertThat(exception)
          .describedAs("分组名 [%s] 应当被拒绝", raw)
          .isNotNull();
      assertThat(exception.code()).isEqualTo(WatchlistErrorCode.GROUP_NAME_INVALID);
    }
  }

  @Test
  void acceptsExactlyTwentyAsciiCharacters() {
    String twenty = "a".repeat(20);

    assertThat(WatchlistGroupName.of(twenty).value()).isEqualTo(twenty);
  }

  @Test
  void rejectsTwentyOneAsciiCharacters() {
    assertThat(catchThrowableOfType(
            () -> WatchlistGroupName.of("a".repeat(21)), WatchlistException.class))
        .isNotNull();
  }

  /**
   * 数据库用 {@code CHAR_LENGTH()} 数「字符」，Java 的 {@code length()} 数 UTF-16 码元。
   *
   * <p>20 个 emoji 在 Java 里 {@code length() == 40}：若按 {@code length()} 判断，
   * 应用层会拒绝一个数据库 CHECK 明确放行的名字。两边必须一致。
   */
  @Test
  void countsCharactersLikeMysqlCharLength() {
    String emoji = "\uD83D\uDE00".repeat(20);
    assertThat(emoji.length()).isEqualTo(40);

    assertThat(WatchlistGroupName.of(emoji).value()).isEqualTo(emoji);
  }

  @Test
  void canonicalConstructorItselfNormalizesAndValidates() {
    assertThat(new WatchlistGroupName("  x  ").value()).isEqualTo("x");
    assertThat(catchThrowableOfType(
            () -> new WatchlistGroupName("   "), WatchlistException.class))
        .isNotNull();
  }
}
