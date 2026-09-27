package cn.zhishi.stock.admin.domain;

import cn.zhishi.stock.news.domain.NewsSource;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 修改资讯来源的命令（契约 §17.1 ADM-NEWS-04 的请求体，可改字段全集）。
 *
 * <h2>两个"状态"各是各的</h2>
 * 表里有两个状态列，语义完全不同（契约 §17.1 的字段清单也分别列出）：
 * <ul>
 *   <li>{@code authorizationStatus}：**授权状态**（这份内容的许可），取值
 *       AUTHORIZED / EXPIRED / SUSPENDED / UNKNOWN。改它回答"这份授权还有效吗"。</li>
 *   <li>{@code status}：**运行状态**（采集通道），取值 ACTIVE / DEGRADED / DISABLED。
 *       改它回答"还要不要继续从这条来源采集"。</li>
 * </ul>
 * PATCH 只更新 {@code Optional} 非空的字段；全空即 400（契约 §16 的
 * "PATCH 没有任何可改字段"同一口径），由用例层检查。
 *
 * <h2>授权状态只能改严、不能改有效</h2>
 * 契约原文："授权状态不能由客户端任意伪造为有效"。因此 {@code AUTHORIZED}
 * 不能直接写入——它只能由授权区间推导（推导规则见 {@code AdminNewsSourceService}）。
 * 客户端可以写 {@code SUSPENDED} / {@code EXPIRED}（把授权改严是人事决定），
 * 也可以不传（{@link #empty()}），此时若授权区间变了就自动重推。
 */
public record AdminNewsSourcePatch(
        Optional<String> sourceName,
        Optional<String> homepageUrl,
        Optional<LocalDate> rightsValidFrom,
        Optional<LocalDate> rightsValidTo,
        Optional<Boolean> allowAiAnalysis,
        Optional<NewsSource.AuthorizationStatus> authorizationStatus,
        Optional<NewsSource.SourceStatus> status) {

    public static AdminNewsSourcePatch empty() {
        return new AdminNewsSourcePatch(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    // ---------- 便于构造部分补丁的 wither（测试与调用方都只用这几条路径） ----------

    public AdminNewsSourcePatch withName(String sourceName) {
        return new AdminNewsSourcePatch(Optional.ofNullable(sourceName), homepageUrl,
                rightsValidFrom, rightsValidTo, allowAiAnalysis, authorizationStatus, status);
    }

    public AdminNewsSourcePatch withRightsValidTo(LocalDate rightsValidTo) {
        return new AdminNewsSourcePatch(sourceName, homepageUrl,
                rightsValidFrom, Optional.ofNullable(rightsValidTo),
                allowAiAnalysis, authorizationStatus, status);
    }

    public AdminNewsSourcePatch withAuthorizationStatus(
            NewsSource.AuthorizationStatus authorizationStatus) {
        return new AdminNewsSourcePatch(sourceName, homepageUrl, rightsValidFrom, rightsValidTo,
                allowAiAnalysis, Optional.ofNullable(authorizationStatus), status);
    }

    public boolean hasNoChange() {
        return sourceName.isEmpty() && homepageUrl.isEmpty() && rightsValidFrom.isEmpty()
                && rightsValidTo.isEmpty() && allowAiAnalysis.isEmpty()
                && authorizationStatus.isEmpty() && status.isEmpty();
    }

    /** 授权区间是否被本次请求触碰（决定要不要重推授权状态）。 */
    public boolean touchesRightsPeriod() {
        return rightsValidFrom.isPresent() || rightsValidTo.isPresent();
    }
}
