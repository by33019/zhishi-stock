package cn.zhishi.stock.integration.market;

import java.util.Optional;

/**
 * 模拟证券 ID 构词规则的**唯一定义**：{@code securityId = "sim-" + securityCode}。
 *
 * <p>这条规则原先在 {@link SimulatedSecurityQuoteProvider} 与
 * {@link SimulatedSectorProvider} 各写了一遍，靠注释互相提醒"两处一致"。
 * M3-02 之后它不再是只写不读的约定：自选表把证券存成 {@code bigint}，
 * 需要**反解** {@code sim-600519} 才能回显，于是"两处一致"变成一个可被打破的前提——
 * 改一处就会让自选项指向另一只证券，而且不会有任何测试变红。
 * 因此抽出本类，两个产出方与一个解析方共用同一份常量与同一对正反函数。
 *
 * <p>{@code securityCode} 在模拟全集里恒为 6 位（3 位代码段 + 3 位补零），
 * 因此"代码本身"可以无损地当作 {@code bigint} 代理键：
 * {@link #storageIdOf} 与 {@link #securityIdOfStorageId} 互为逆运算
 * （由 {@code SimulatedSecurityIdentityProviderTest} 对 5149 只证券全量断言）。
 */
public final class SimulatedSecurityIds {

    private static final String PREFIX = "sim-";
    private static final int CODE_WIDTH = 6;

    private SimulatedSecurityIds() {
    }

    /** 由证券代码构造对外 {@code securityId}。 */
    public static String securityIdOf(String securityCode) {
        return PREFIX + securityCode;
    }

    /** 反解出证券代码；前缀不对或代码宽度不是 6 位时返回空（不猜）。 */
    public static Optional<String> securityCodeOf(String securityId) {
        if (securityId == null || !securityId.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String code = securityId.substring(PREFIX.length());
        if (code.length() != CODE_WIDTH || !code.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        return Optional.of(code);
    }

    /** 对外 {@code securityId} → 可存进 {@code bigint} 列的代理键。 */
    public static Optional<Long> storageIdOf(String securityId) {
        return securityCodeOf(securityId).map(Long::parseLong);
    }

    /** 代理键 → 对外 {@code securityId}，左补零到固定宽度。 */
    public static String securityIdOfStorageId(long storageId) {
        String digits = Long.toString(storageId);
        return PREFIX + "0".repeat(Math.max(0, CODE_WIDTH - digits.length())) + digits;
    }
}
