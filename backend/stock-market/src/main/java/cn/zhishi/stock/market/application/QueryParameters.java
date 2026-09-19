package cn.zhishi.stock.market.application;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 查询参数的公共解析规则。
 *
 * <p>抽出来是因为"逗号分隔的多值筛选"必须在**所有列表接口里语义一致**：
 * 是否裁剪空白、是否丢弃空项、是否大小写不敏感。各写一遍的话，
 * {@code exchangeCodes=SH,,SZ} 会在 STK-02 与 QTE-01 上给出不同结果，
 * 而这种分叉不会有任何测试变红。
 */
final class QueryParameters {

  private QueryParameters() {
  }

  /** 解析逗号分隔的多值筛选：裁剪空白、丢弃空项、统一小写；参数为空表示该条件不参与筛选。 */
  static Set<String> parseCsvFilter(String csv) {
    if (!isPresent(csv)) {
      return Set.of();
    }
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(item -> !item.isEmpty())
        .map(item -> item.toLowerCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());
  }

  static boolean isPresent(String value) {
    return value != null && !value.isBlank();
  }

  static String lower(String value) {
    return value == null ? null : value.toLowerCase(Locale.ROOT);
  }
}
