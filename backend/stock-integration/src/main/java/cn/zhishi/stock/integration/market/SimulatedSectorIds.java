package cn.zhishi.stock.integration.market;

import java.util.Optional;

/**
 * 模拟板块 ID 构词规则的**唯一定义**：{@code sectorId = "sim-bk" + 4 位左补零的序号}。
 *
 * <p>与 {@link SimulatedSecurityIds} 完全同形，理由也一样：M3-04 之前这条规则只被
 * {@link SimulatedSectorProvider} 用来**产出** ID，无人反解；资讯关联表把板块存成
 * {@code bigint} 之后就需要**反解** {@code sim-bk0001} 才能回显，
 * "两处一致"于是变成一个可被打破的前提。因此把常量与正反函数收到这里，
 * 产出方与解析方共用同一份定义。
 *
 * <p>序号本身可以无损地当作 {@code bigint} 代理键（1 起、连续、不重复），
 * {@link #storageIdOf} 与 {@link #sectorIdOfStorageId} 互为逆运算
 * （由 {@code SimulatedSectorIdentityProviderTest} 对全部板块断言）。
 */
public final class SimulatedSectorIds {

    private static final String PREFIX = "sim-bk";
    private static final int WIDTH = 4;

    private SimulatedSectorIds() {
    }

    /** 由板块序号构造对外 {@code sectorId}。 */
    public static String sectorIdOf(long ordinal) {
        return PREFIX + pad(ordinal);
    }

    /** 反解出板块序号；前缀不对或宽度不是 4 位数字时返回空（不猜）。 */
    public static Optional<Long> storageIdOf(String sectorId) {
        if (sectorId == null || !sectorId.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String digits = sectorId.substring(PREFIX.length());
        if (digits.length() != WIDTH || !digits.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(digits));
    }

    /** 代理键 → 对外 {@code sectorId}，左补零到固定宽度。 */
    public static String sectorIdOfStorageId(long storageId) {
        return PREFIX + pad(storageId);
    }

    private static String pad(long value) {
        String digits = Long.toString(value);
        return "0".repeat(Math.max(0, WIDTH - digits.length())) + digits;
    }
}
