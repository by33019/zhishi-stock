package cn.zhishi.stock.integration.market;

/**
 * 模拟源共享的确定性哈希。
 *
 * <p>抽出来是因为 SplitMix64 的收尾混合已在个股行情与价格序列里各有一份；
 * 板块归属是第三个用户。三份拷贝一旦有一处改动，"同一只证券的序号"在三条链路上
 * 会指向不同的取值，而这种分叉不会有任何测试变红。
 *
 * <p>不用 {@code Math.random} / {@code String.hashCode()} 之外的随机源：
 * 模拟数据必须"同一输入恒定同一输出"，否则测试与 CI 会随运行而漂移。
 */
public final class SimulatedHashing {

  private SimulatedHashing() {
  }

  /** SplitMix64 的收尾混合：把相邻的序号、日期与相似的代码打散成互不相关的桶号。 */
  public static long mix(long value) {
    long z = value + 0x9E3779B97F4A7C15L;
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  /**
   * 由字符串键与盐值导出桶号。
   *
   * <p>用 {@link String#hashCode()}：它的算法由 Java 规范固定，跨 JVM / 跨平台一致，
   * 因此"这只证券属于哪个板块"不依赖任何运行环境。
   */
  public static long bucket(String key, int salt) {
    return mix(key.hashCode() * 1_000_003L + salt);
  }
}
