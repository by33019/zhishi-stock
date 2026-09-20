package cn.zhishi.stock.system.watchlist;

/**
 * WAT-04 的返回值。
 *
 * @param deleted 软删除是否完成（成功路径恒为 {@code true}，保留字段是为了与契约的响应形状逐字对齐）
 * @param movedItemCount <b>实际搬到目标组的条数</b>，不含被目标组合并掉的重复项
 */
public record DeleteResult(boolean deleted, int movedItemCount) {
}
