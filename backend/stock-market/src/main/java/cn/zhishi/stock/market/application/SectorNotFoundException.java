package cn.zhishi.stock.market.application;

/**
 * 板块资源不可用（→ 404）。
 *
 * <p>三种情况的 HTTP 状态都是 404，只有业务码不同，因此合并成一个异常类、
 * 用静态工厂区分语义（同 {@link InvalidKlineParameterException} 的处理）。
 * 业务码显式携带在异常上，不在处理器里靠 {@code instanceof} 推断。
 *
 * <p>为什么"板块停用"与"成分关系缺失"也算 404 而不是 409：
 * 对调用方而言，这三者都意味着"你请求的这个板块资源当前拿不到"，
 * 与 §23.1 的 {@code 404 Not Found} 语义一致；用 409 会把"资源状态"混同为"并发冲突"。
 */
public class SectorNotFoundException extends RuntimeException {

    private final String code;

    private SectorNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 板块 ID 不存在。 */
    public static SectorNotFoundException notFound(String sectorId) {
        return new SectorNotFoundException("SECTOR_NOT_FOUND", "板块 " + sectorId + " 不存在");
    }

    /**
     * 板块已停用。
     *
     * <p>只用于"当前行情"与"当前成分"这两个语义上要求板块有效的接口（SEC-04 / SEC-06）。
     * SEC-03 详情对停用板块仍返回 200——契约明确「已停用板块可返回历史状态但不进入当前排行」。
     */
    public static SectorNotFoundException inactive(String sectorId) {
        return new SectorNotFoundException("SECTOR_INACTIVE", "板块 " + sectorId + " 已停用");
    }

    /**
     * 板块存在但没有任何有效成分关系。
     *
     * <p>不用空页代替：空页会被读成"该板块确实有 0 只成分股"，
     * 而 PRD §7.4 SEC-03 要求「无成分关系时显示数据问题，不用名称模糊匹配替代」。
     */
    public static SectorNotFoundException constituentsMissing(String sectorId) {
        return new SectorNotFoundException(
                "SECTOR_CONSTITUENTS_MISSING", "板块 " + sectorId + " 没有有效成分关系");
    }
}
