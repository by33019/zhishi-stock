package cn.zhishi.stock.market.application;

import cn.zhishi.stock.market.domain.RankingType;
import cn.zhishi.stock.market.domain.Sector;
import cn.zhishi.stock.market.domain.SectorType;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * 板块接口（{@code RESTful-API.md} §10 SEC-01 ~ SEC-06）的参数校验。
 *
 * <p>与 {@link QueryParameters} 的分工：后者管"逗号分隔多值的解析"这类跨模块规则，
 * 本类管板块接口自己的枚举与范围。两个服务（SEC-02 与 SEC-01/03/04/06）共用一份校验，
 * 否则同一个 {@code rankingType} 在两处会给出不同的错误消息与默认值。
 *
 * <p>校验规则沿用既有两条：
 * <ul>
 *   <li><b>枚举必须校验</b>：{@code rankingType} 被静默忽略时，调用方拿到的是
 *       "榜单类型不对但看起来正常"的响应，极难排查。</li>
 *   <li><b>筛选值不校验合法性</b>：{@code parentId=不存在} 是合法取值，只是数据里没有——
 *       返回空结果，语义上仍然诚实。</li>
 * </ul>
 */
final class SectorParameters {

    static final int DEFAULT_PAGE = 1;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    /** 板块排行的默认口径。PRD §7.4 SEC-01 的主用法是"识别市场热点"，即涨幅榜。 */
    static final RankingType DEFAULT_RANKING_TYPE = RankingType.GAINERS;

    private SectorParameters() {
    }

    /** 解析板块类型；未传返回空（表示该条件不参与筛选），取值非法报 400。 */
    static Optional<SectorType> sectorType(String raw) {
        if (!QueryParameters.isPresent(raw)) {
            return Optional.empty();
        }
        return Optional.of(SectorType.fromCode(raw).orElseThrow(() -> new InvalidSectorQueryException(
                "sectorType 必须为 " + String.join("、", SectorType.codes()) + " 之一")));
    }

    /** 解析板块状态；未传默认 {@code ACTIVE}（契约：普通用户默认只能查询有效板块）。 */
    static String status(String raw) {
        if (!QueryParameters.isPresent(raw)) {
            return Sector.STATUS_ACTIVE;
        }
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!Sector.STATUS_ACTIVE.equals(normalized) && !Sector.STATUS_INACTIVE.equals(normalized)) {
            throw new InvalidSectorQueryException("status 必须为 ACTIVE 或 INACTIVE");
        }
        return normalized;
    }

    /** 解析排行口径；未传返回空，由调用方决定默认值（SEC-02 与 SEC-06 的默认可以不同）。 */
    static Optional<RankingType> rankingType(String raw) {
        if (!QueryParameters.isPresent(raw)) {
            return Optional.empty();
        }
        return Optional.of(RankingType.fromCode(raw).orElseThrow(() -> new InvalidSectorQueryException(
                "rankingType 必须为 " + String.join("、", RankingType.codes()) + " 之一")));
    }

    static int page(Integer page) {
        if (page == null) {
            return DEFAULT_PAGE;
        }
        if (page < 1) {
            throw new InvalidSectorQueryException("page 必须大于等于 1");
        }
        return page;
    }

    static int pageSize(Integer size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidSectorQueryException("size 必须为 1 至 100");
        }
        return size;
    }

    /**
     * 解析成分关系的生效日期；未传返回 {@code null}（表示"按当前有效关系解析"）。
     *
     * <p>格式非法直接报 400 而不是静默忽略：调用方以为查的是历史成分、
     * 实际拿到当前成分，是最难排查的一类问题。
     */
    static LocalDate effectiveDate(String raw) {
        if (!QueryParameters.isPresent(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException exception) {
            throw new InvalidSectorQueryException("effectiveDate 格式必须为 yyyy-MM-dd");
        }
    }
}
