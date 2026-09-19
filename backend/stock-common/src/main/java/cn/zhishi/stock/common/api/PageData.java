package cn.zhishi.stock.common.api;

import java.util.List;

/**
 * 通用分页响应数据，与 {@link ApiResponse} 同属跨域共用的响应外壳。
 *
 * <p>放在 {@code stock-common} 而非某个业务域：榜单、资讯、AI 历史等分页接口都要用，
 * 放进单个域会让其它域反向依赖它。
 *
 * @param items      当前页数据
 * @param page       页码，从 1 开始
 * @param size       每页条数
 * @param total      过滤后的总条数（不是全表条数）
 * @param totalPages 总页数，{@code total = 0} 时为 0
 * @param hasNext    是否存在下一页
 */
public record PageData<T>(
    List<T> items,
    int page,
    int size,
    long total,
    int totalPages,
    boolean hasNext) {

  public PageData {
    items = List.copyOf(items);
  }

  /**
   * 对**已过滤、已排序**的完整列表做分页切片。
   *
   * <p>越界页码返回空页而不是报错——翻到最后一页之后是正常的客户端行为，
   * 且 {@code total} 仍然给出真实总数，调用方可以据此纠正页码。
   */
  public static <T> PageData<T> slice(List<T> filtered, int page, int size) {
    long total = filtered.size();
    int totalPages = (int) ((total + size - 1) / size);
    long offset = (long) (page - 1) * size;
    int from = (int) Math.min(offset, filtered.size());
    int to = (int) Math.min(offset + size, filtered.size());
    return new PageData<>(
        filtered.subList(from, to), page, size, total, totalPages, page < totalPages);
  }
}
