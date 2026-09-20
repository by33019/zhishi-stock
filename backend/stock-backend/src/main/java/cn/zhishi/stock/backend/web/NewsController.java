package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.news.application.NewsQueryService;
import cn.zhishi.stock.news.domain.NewsDetail;
import cn.zhishi.stock.news.domain.NewsOptions;
import cn.zhishi.stock.news.domain.NewsPage;
import cn.zhishi.stock.news.domain.NewsSyncStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资讯接口（{@code RESTful-API.md} §11.1 NEWS-01~04，以及 §9.2 STK-10、§10 SEC-07）。
 *
 * <p>六个端点全部是 {@code PUBLIC}：资讯中心、个股页与板块页的资讯区在未登录时也要出数，
 * 与榜单、板块同属只读行情数据。因此这里没有任何鉴权逻辑，
 * 放开访问由 {@code SecurityConfiguration} 的前缀白名单负责。
 *
 * <h2>为什么 STK-10 / SEC-07 写在这里，而不是 SecurityController / SectorController</h2>
 *
 * <p>它们的路径确实挂在 {@code /securities/{id}} 与 {@code /sectors/{id}} 下，但资源是**资讯**：
 * 业务规则、端口、持久化全在 {@code stock-news}。写进行情侧的控制器会让
 * {@code stock-backend} 的证券/板块 Web 层反向依赖资讯域，并给它们的构造签名各加一个
 * 只为一个端点服务的参数。按资源而不是按路径前缀划分控制器，这里更合算。
 *
 * <p>参数一律声明为 {@code String} / {@code Integer} 并在用例层校验，同 STK-01 / SEC-01：
 * 枚举绑定失败会被 Spring 转成 {@code MethodArgumentTypeMismatchException}，
 * 语义上把"取值不在白名单"混同为"参数格式错误"。
 *
 * <p>{@code newsTypes} 是**逗号分隔多值**（契约写的是复数），而 STK-10 / SEC-07 用的是单值
 * {@code newsType}；两者都交给 {@link NewsQueryService} 解析，控制器不自己拆分字符串——
 * "哪些取值合法"只允许有一处知识。
 *
 * <p>路由顺序上 {@code /news/sync-status} 与 {@code /news/options} 与
 * {@code /news/{newsId}} 同前缀：Spring 的字面量路径优先于模板路径，
 * 因此 {@code sync-status} 不会被当成一个 {@code newsId}。这一点由契约测试钉住。
 */
@RestController
@RequestMapping("/api/v1")
public class NewsController {

    private final NewsQueryService newsQueryService;
    private final Clock clock;

    public NewsController(NewsQueryService newsQueryService, Clock clock) {
        this.newsQueryService = newsQueryService;
        this.clock = clock;
    }

    /** NEWS-01：资讯中心列表。 */
    @GetMapping("/news")
    public ApiResponse<NewsPage> list(
            @RequestParam(required = false) String newsTypes,
            @RequestParam(required = false) String securityId,
            @RequestParam(required = false) String sectorId,
            @RequestParam(required = false) String marketCode,
            @RequestParam(required = false) String startAt,
            @RequestParam(required = false) String endAt,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        return ok(newsQueryService.browse(
                newsTypes, securityId, sectorId, marketCode,
                startAt, endAt, keyword, page, size), request);
    }

    /**
     * NEWS-02：资讯详情。
     *
     * <p>重复稿的 id 会被折叠到主记录；撤稿、删除、授权失效分别报不同的业务码
     * （{@code NEWS_NOT_FOUND} / {@code NEWS_WITHDRAWN} / {@code NEWS_RIGHTS_EXPIRED}）。
     * 这些区分在用例层做——控制器不认识它们的差别。
     */
    @GetMapping("/news/{newsId}")
    public ApiResponse<NewsDetail> detail(
            @PathVariable String newsId, HttpServletRequest request) {
        return ok(newsQueryService.detail(newsId), request);
    }

    /** NEWS-03：公开的资讯同步状态。只有聚合计数与时间，没有任何来源名或错误文本。 */
    @GetMapping("/news/sync-status")
    public ApiResponse<NewsSyncStatus> syncStatus(HttpServletRequest request) {
        return ok(newsQueryService.syncStatus(), request);
    }

    /** NEWS-04：受控筛选项与当前生效的筛选规则说明。 */
    @GetMapping("/news/options")
    public ApiResponse<NewsOptions> options(HttpServletRequest request) {
        return ok(newsQueryService.options(), request);
    }

    /**
     * STK-10：个股资讯。
     *
     * <p>只返回与股票**确认**关联的资讯。契约明确"候选或已拒绝关系不返回"，
     * 因此低置信关联不会在这里出现——那正是"我们不确定这条消息属于这只票"的表达。
     */
    @GetMapping("/securities/{securityId}/news")
    public ApiResponse<NewsPage> securityNews(
            @PathVariable String securityId,
            @RequestParam(required = false) String newsType,
            @RequestParam(required = false) String startAt,
            @RequestParam(required = false) String endAt,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        return ok(newsQueryService.bySecurity(
                securityId, newsType, startAt, endAt, page, size), request);
    }

    /**
     * SEC-07：板块资讯。
     *
     * <p>只返回与板块**确认**关联的资讯：个股资讯不会因为"它属于这个板块"就自动
     * 泛化成板块事实——一条关于某公司的事，不等于关于整个行业的事。
     */
    @GetMapping("/sectors/{sectorId}/news")
    public ApiResponse<NewsPage> sectorNews(
            @PathVariable String sectorId,
            @RequestParam(required = false) String newsType,
            @RequestParam(required = false) String startAt,
            @RequestParam(required = false) String endAt,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        return ok(newsQueryService.bySector(
                sectorId, newsType, startAt, endAt, page, size), request);
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.success(
                data, TraceIdFilter.current(request), OffsetDateTime.now(clock));
    }
}
