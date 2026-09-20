package cn.zhishi.stock.system.watchlist;

/**
 * 自选模块的业务异常，携带自己的业务码与 HTTP 状态。
 *
 * <p>与 {@code SectorNotFoundException} 同样的写法：业务码由异常自身携带，
 * {@code GlobalExceptionHandler} 不靠 instanceof 推断。
 */
public class WatchlistException extends RuntimeException {

    private final WatchlistErrorCode code;

    public WatchlistException(WatchlistErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public WatchlistErrorCode code() {
        return code;
    }
}
