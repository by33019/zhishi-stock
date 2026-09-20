package cn.zhishi.stock.system.watchlist;

/**
 * 自选模块的业务码与 HTTP 状态。
 *
 * <p>状态码写在这里而不是 {@code GlobalExceptionHandler} 里：契约里同一个模块的异常状态各不相同
 * （400 / 404 / 409），在 handler 里用 switch 推断会让"新增一个业务码"必须同时改两处。
 *
 * <p>用 {@code int} 而不是 {@code HttpStatus}，是为了让 stock-system 不依赖 spring-web。
 */
public enum WatchlistErrorCode {

    /** 分组名去除首尾空白后不在 1~20 个字符。响应体里带 fieldErrors。 */
    GROUP_NAME_INVALID("VALIDATION_FAILED", 400),

    /** 缺 If-Match、If-Match 非法、groupIds 缺项/重复、includeItems=true（本轮未实现）。 */
    INVALID_REQUEST("INVALID_REQUEST", 400),

    /** 删除非空分组但没给 moveItemsToGroupId。 */
    TARGET_GROUP_REQUIRED("WATCHLIST_TARGET_GROUP_REQUIRED", 400),

    /**
     * 路径上的 {groupId} 不是本人有效分组。
     *
     * <p>刻意与"不存在"共用一个码：契约 §12.3 要求对他人资源也返回这个码，避免泄露存在性。
     */
    RESOURCE_NOT_FOUND("WATCHLIST_RESOURCE_NOT_FOUND", 404),

    /** 请求体 / Query 里引用的分组 ID 无法解析（WAT-04 的 moveItemsToGroupId）。 */
    GROUP_NOT_FOUND("WATCHLIST_GROUP_NOT_FOUND", 404),

    /** 同一用户的有效分组名重复（表 collation 是 utf8mb4_general_ci，因此大小写不敏感）。 */
    GROUP_NAME_EXISTS("WATCHLIST_GROUP_NAME_EXISTS", 409),

    /** 默认分组不可删除。 */
    DEFAULT_GROUP_CANNOT_DELETE("DEFAULT_GROUP_CANNOT_DELETE", 409),

    /** 目标分组与源分组相同（WAT-04）。 */
    TARGET_GROUP_CONFLICT("WATCHLIST_TARGET_GROUP_CONFLICT", 409),

    /** If-Match 的版本号与当前版本不一致。 */
    VERSION_CONFLICT("WATCHLIST_VERSION_CONFLICT", 409);

    private final String externalCode;
    private final int httpStatus;

    WatchlistErrorCode(String externalCode, int httpStatus) {
        this.externalCode = externalCode;
        this.httpStatus = httpStatus;
    }

    public String externalCode() {
        return externalCode;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
