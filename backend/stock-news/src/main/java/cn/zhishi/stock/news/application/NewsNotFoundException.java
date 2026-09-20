package cn.zhishi.stock.news.application;

/**
 * 资讯资源不可用（→ 404）。
 *
 * <p>四种情况的 HTTP 状态都是 404，只有业务码不同，因此合并成一个异常类、
 * 用静态工厂区分语义（同 {@code SectorNotFoundException} 的处理）。
 * 业务码显式携带在异常上，不在处理器里靠 {@code instanceof} 或消息文本推断。
 *
 * <p>为什么"撤稿"与"授权过期"也算 404 而不是 410 / 403：
 * 契约 §11.2 对外的语义是"这条资讯当前拿不到"，而区分 410（永久消失）与 403（无权）
 * 会把授权细节泄露给未登录的游客——NEWS-02 是 PUBLIC 接口。
 */
public class NewsNotFoundException extends RuntimeException {

    private final String code;

    private NewsNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 资讯 ID 不存在。 */
    public static NewsNotFoundException notFound(String newsId) {
        return new NewsNotFoundException("NEWS_NOT_FOUND", "资讯 " + newsId + " 不存在");
    }

    /**
     * 资讯已撤稿。
     *
     * <p>元数据保留（按 id 取详情时能明确告知"它撤了"），但摘要不再展示——
     * 契约 §11.2：「撤稿保留元数据时必须显著标记，不继续展示摘要」。
     * 因此这里的响应**不含** {@code summary}，前端拿到的 message 就是那条显著标记。
     */
    public static NewsNotFoundException withdrawn(String newsId) {
        return new NewsNotFoundException("NEWS_WITHDRAWN", "资讯 " + newsId + " 已撤稿，内容不再展示");
    }

    /** 资讯的授权已过期。 */
    public static NewsNotFoundException rightsExpired(String newsId) {
        return new NewsNotFoundException("NEWS_RIGHTS_EXPIRED", "资讯 " + newsId + " 的授权已过期");
    }

    /**
     * 路径里的证券不存在。
     *
     * <p>与"这只证券没有资讯"是两件事：前者是 404，后者是空页。
     * 用空页代替 404 会让"代码打错了"看起来像"这只票很安静"。
     */
    public static NewsNotFoundException securityNotFound(String securityId) {
        return new NewsNotFoundException("SECURITY_NOT_FOUND", "证券 " + securityId + " 不存在");
    }

    /** 路径里的板块不存在。 */
    public static NewsNotFoundException sectorNotFound(String sectorId) {
        return new NewsNotFoundException("SECTOR_NOT_FOUND", "板块 " + sectorId + " 不存在");
    }
}
