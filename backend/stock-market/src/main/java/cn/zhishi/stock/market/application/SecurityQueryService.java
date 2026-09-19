package cn.zhishi.stock.market.application;

import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecuritySearchMatch;
import cn.zhishi.stock.market.domain.SecuritySearchMatch.MatchedField;
import cn.zhishi.stock.market.domain.SecuritySearchResult;
import cn.zhishi.stock.market.domain.SecuritySummary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 证券主数据的查询用例：搜索建议（STK-01）与列表（STK-02）。
 *
 * <p>两个用例共用同一份主数据与同一套过滤逻辑，差别只在"排序依据"：
 * 搜索按**匹配优先级**排序（用户输入什么就先给什么），列表按**调用方指定的字段**排序。
 *
 * <p>多值筛选的解析规则（裁剪空白、丢弃空项、大小写不敏感）统一放在
 * {@link QueryParameters}，与榜单接口共用一份实现。
 *
 * <h2>为什么筛选值不校验合法性，排序字段却必须校验</h2>
 * <ul>
 *   <li>筛选值（{@code types}、{@code exchangeCode}…）不存在时返回空结果，语义上仍然诚实——
 *       {@code types=ETF} 是合法取值，只是当前模拟数据里没有 ETF。若报 400，
 *       就把"没有数据"错报成"参数非法"。</li>
 *   <li>排序字段被静默忽略时，调用方拿到的是"顺序不对但看起来正常"的响应，极难排查。
 *       因此白名单外的字段直接报错，不回落默认值。</li>
 * </ul>
 */
public class SecurityQueryService {

  /** 证券主数据是市场无关的全集；接口没有 marketCode 参数，内部固定为 CN。 */
  private static final String MARKET_CODE = "CN";

  private static final int MIN_QUERY_LENGTH = 1;
  private static final int MAX_QUERY_LENGTH = 50;
  private static final int DEFAULT_SEARCH_LIMIT = 10;
  private static final int MAX_SEARCH_LIMIT = 20;

  private static final int DEFAULT_PAGE = 1;
  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 100;

  private static final String DEFAULT_SORT_FIELD = "fullSymbol";
  private static final String DIRECTION_ASC = "asc";
  private static final String DIRECTION_DESC = "desc";

  /** 用 List 而非 Set：错误信息里的字段顺序要稳定，便于阅读与断言。 */
  private static final List<String> SORTABLE_FIELDS =
      List.of("fullSymbol", "securityCode", "securityName", "securityId");

  private final SecurityMasterProvider securityMasterProvider;

  public SecurityQueryService(SecurityMasterProvider securityMasterProvider) {
    this.securityMasterProvider = securityMasterProvider;
  }

  /** STK-01：搜索建议。 */
  public SecuritySearchResult search(
      String q, String types, String exchangeCodes, Integer limit) {
    String needle = normalizeQuery(q);
    int effectiveLimit = validateSearchLimit(limit);
    Set<String> typeFilter = QueryParameters.parseCsvFilter(types);
    Set<String> exchangeFilter = QueryParameters.parseCsvFilter(exchangeCodes);

    List<SecuritySearchMatch> matches = new ArrayList<>();
    for (SecuritySummary security : securityMasterProvider.findAll(MARKET_CODE)) {
      if (!matchesFilter(security, typeFilter, exchangeFilter)) {
        continue;
      }
      match(security, needle).ifPresent(matches::add);
    }
    matches.sort(Comparator
        .comparingInt((SecuritySearchMatch match) -> match.matchedField().ordinal())
        .thenComparing(match -> match.security().fullSymbol()));

    return new SecuritySearchResult(
        matches.size() <= effectiveLimit ? matches : matches.subList(0, effectiveLimit));
  }

  /** STK-02：证券列表。 */
  public PageData<SecuritySummary> list(SecurityListCriteria criteria) {
    SecurityListCriteria effective =
        criteria == null ? SecurityListCriteria.empty() : criteria;
    int page = validatePage(effective.page());
    int size = validatePageSize(effective.size());
    SortSpec sort = parseSort(effective.sort());

    // 板块关系数据（stock_sector / stock_security_sector）在 M2-07 之前不存在，
    // 因此"没有任何证券属于该板块"在当下是事实，返回空页而不是报错或忽略条件。
    if (QueryParameters.isPresent(effective.sectorId())) {
      return PageData.slice(List.of(), page, size);
    }

    List<SecuritySummary> filtered = securityMasterProvider.findAll(MARKET_CODE).stream()
        .filter(security -> matchesListFilters(security, effective))
        .sorted(sort.comparator())
        .toList();
    return PageData.slice(filtered, page, size);
  }

  // ---------- 匹配 ----------

  /**
   * 按固定优先级取**第一个**命中的字段；一只证券只产生一条结果。
   *
   * <p>代码用**前缀**匹配、名称用**包含**匹配：代码是结构化标识，输入 {@code 600}
   * 期望的是 {@code 600xxx} 这一段；名称是自然语言，输入"银行"期望匹配名字里任何位置的"银行"。
   */
  private static Optional<SecuritySearchMatch> match(SecuritySummary security, String needle) {
    String lowerNeedle = needle.toLowerCase(Locale.ROOT);
    if (startsWithIgnoreCase(security.securityCode(), lowerNeedle)) {
      return Optional.of(new SecuritySearchMatch(
          security, MatchedField.CODE, prefix(security.securityCode(), needle.length())));
    }
    if (startsWithIgnoreCase(security.fullSymbol(), lowerNeedle)) {
      return Optional.of(new SecuritySearchMatch(
          security, MatchedField.CODE, prefix(security.fullSymbol(), needle.length())));
    }
    int nameIndex = indexOfIgnoreCase(security.securityName(), lowerNeedle);
    if (nameIndex >= 0) {
      return Optional.of(new SecuritySearchMatch(
          security,
          MatchedField.NAME,
          security.securityName().substring(nameIndex, nameIndex + needle.length())));
    }
    if (startsWithIgnoreCase(security.pinyin(), lowerNeedle)) {
      return Optional.of(new SecuritySearchMatch(
          security, MatchedField.PINYIN, prefix(security.pinyin(), needle.length())));
    }
    if (startsWithIgnoreCase(security.pinyinAbbr(), lowerNeedle)) {
      return Optional.of(new SecuritySearchMatch(
          security, MatchedField.PINYIN_ABBR, prefix(security.pinyinAbbr(), needle.length())));
    }
    return Optional.empty();
  }

  private static boolean matchesFilter(
      SecuritySummary security, Set<String> typeFilter, Set<String> exchangeFilter) {
    if (!typeFilter.isEmpty()
        && !typeFilter.contains(QueryParameters.lower(security.securityType()))) {
      return false;
    }
    return exchangeFilter.isEmpty()
        || exchangeFilter.contains(QueryParameters.lower(security.exchangeCode()));
  }

  private static boolean matchesListFilters(
      SecuritySummary security, SecurityListCriteria criteria) {
    if (!containsIgnoreCase(security.securityCode(), criteria.keyword())
        && !containsIgnoreCase(security.securityName(), criteria.keyword())
        && !containsIgnoreCase(security.fullSymbol(), criteria.keyword())) {
      return false;
    }
    return equalsIgnoreCase(security.securityType(), criteria.securityType())
        && equalsIgnoreCase(security.exchangeCode(), criteria.exchangeCode())
        && equalsIgnoreCase(security.boardCode(), criteria.boardCode())
        && equalsIgnoreCase(security.listingStatus(), criteria.listingStatus());
  }

  // ---------- 校验 ----------

  private static String normalizeQuery(String q) {
    String trimmed = q == null ? "" : q.trim();
    if (trimmed.length() < MIN_QUERY_LENGTH || trimmed.length() > MAX_QUERY_LENGTH) {
      throw new InvalidSecurityQueryException("q 长度必须为 1 至 50 个字符");
    }
    return trimmed;
  }

  private static int validateSearchLimit(Integer limit) {
    if (limit == null) {
      return DEFAULT_SEARCH_LIMIT;
    }
    if (limit < 1 || limit > MAX_SEARCH_LIMIT) {
      throw new InvalidSecurityQueryException("limit 必须为 1 至 20");
    }
    return limit;
  }

  private static int validatePage(Integer page) {
    if (page == null) {
      return DEFAULT_PAGE;
    }
    if (page < 1) {
      throw new InvalidSecurityQueryException("page 必须大于等于 1");
    }
    return page;
  }

  private static int validatePageSize(Integer size) {
    if (size == null) {
      return DEFAULT_PAGE_SIZE;
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new InvalidSecurityQueryException("size 必须为 1 至 100");
    }
    return size;
  }

  private static SortSpec parseSort(String sort) {
    if (!QueryParameters.isPresent(sort)) {
      return new SortSpec(DEFAULT_SORT_FIELD, false);
    }
    String[] parts = sort.split(",", -1);
    if (parts.length > 2) {
      throw new InvalidSecurityQueryException("sort 格式必须为 field,asc 或 field,desc");
    }
    String field = parts[0].trim();
    if (!SORTABLE_FIELDS.contains(field)) {
      throw new InvalidSecurityQueryException(
          "sort 字段仅支持 " + String.join("、", SORTABLE_FIELDS));
    }
    boolean descending = false;
    if (parts.length == 2) {
      String direction = parts[1].trim().toLowerCase(Locale.ROOT);
      if (DIRECTION_DESC.equals(direction)) {
        descending = true;
      } else if (!DIRECTION_ASC.equals(direction)) {
        throw new InvalidSecurityQueryException("sort 方向仅支持 asc 或 desc");
      }
    }
    return new SortSpec(field, descending);
  }

  // ---------- 小工具 ----------

  private static boolean equalsIgnoreCase(String value, String expected) {
    return !QueryParameters.isPresent(expected)
        || (value != null && value.equalsIgnoreCase(expected.trim()));
  }

  private static boolean containsIgnoreCase(String value, String needle) {
    return !QueryParameters.isPresent(needle)
        || indexOfIgnoreCase(value, needle.trim().toLowerCase(Locale.ROOT)) >= 0;
  }

  /**
   * 返回忽略大小写的首次命中位置，语义同 {@link String#indexOf}。
   *
   * <p>用 {@code Locale.ROOT} 小写化：对 ASCII 与中文而言长度不变，
   * 因此可以直接把命中位置套回原串做 {@code substring} 取原文。
   */
  private static int indexOfIgnoreCase(String value, String lowerNeedle) {
    if (value == null) {
      return -1;
    }
    return value.toLowerCase(Locale.ROOT).indexOf(lowerNeedle);
  }

  private static boolean startsWithIgnoreCase(String value, String lowerNeedle) {
    return value != null && value.toLowerCase(Locale.ROOT).startsWith(lowerNeedle);
  }

  private static String prefix(String value, int length) {
    return value.substring(0, Math.min(length, value.length()));
  }

  /** 已校验的排序描述。 */
  private record SortSpec(String field, boolean descending) {

    /**
     * 比较器只按单个字段排序，**不加次级键**：证券全集里
     * {@code fullSymbol} / {@code securityCode} / {@code securityId} 本身就唯一，
     * {@code securityName} 不唯一但排序稳定性由 {@code sorted()} 的稳定排序保证。
     */
    Comparator<SecuritySummary> comparator() {
      Comparator<SecuritySummary> base = switch (field) {
        case "securityCode" -> Comparator.comparing(SecuritySummary::securityCode);
        case "securityName" -> Comparator.comparing(SecuritySummary::securityName);
        case "securityId" -> Comparator.comparing(SecuritySummary::securityId);
        default -> Comparator.comparing(SecuritySummary::fullSymbol);
      };
      return descending ? base.reversed() : base;
    }
  }
}
