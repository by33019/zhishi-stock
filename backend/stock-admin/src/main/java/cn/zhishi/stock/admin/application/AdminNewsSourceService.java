package cn.zhishi.stock.admin.application;

import cn.zhishi.stock.admin.domain.AdminNewsSourcePatch;
import cn.zhishi.stock.admin.domain.AdminNewsSourceQuery;
import cn.zhishi.stock.admin.domain.AdminNewsSourceStore;
import cn.zhishi.stock.admin.domain.NewAdminNewsSource;
import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.news.domain.NewsSource;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资讯来源管理用例（契约 §17.1 ADM-NEWS-01~04）。
 *
 * <h2>授权状态的推导规则（"不能伪造为有效"的落地）</h2>
 * {@code authorization_status} 是"这份内容的许可现在算不算有效"，它在后台只有
 * 三个出处，全部收口在 {@link #resolveOnCreate} 与 {@link #resolveOnPatch}：
 * <ol>
 *   <li>**从授权区间推导**：区间存在且未到期（{@code rights_valid_to} 为空或
 *       {@code >=} 今天）→ {@code AUTHORIZED}；已到期 → {@code EXPIRED}。
 *       起始日在未来的授权也算 {@code AUTHORIZED}——"授权已签、生效日未到"
 *       与"无效"是两回事，逐日精确的门禁由资讯域 {@code NewsSource.usableOn}
 *       在采集与 AI 证据两侧执行，状态列不重复承担日期判断。</li>
 *   <li>**人工改严**：{@code SUSPENDED}（授权暂停但区间未到期）与
 *       {@code EXPIRED} 可以由客户端直接写入。</li>
 *   <li>**保持现状**：PATCH 没碰授权区间也没显式给状态时，沿用当前值——
 *       否则改个来源名称就会把 V4 默认的 {@code AUTHORIZED} 意外重算。</li>
 * </ol>
 * 客户端传 {@code AUTHORIZED} 只在"按区间推导本来就是有效"时被接受；
 * 已到期或无区间的来源传 {@code AUTHORIZED} 一律 400——那正是契约禁止的伪造。
 * 人工暂停（{@code SUSPENDED}）比区间更"粘"：编辑区间不会悄悄解除暂停。
 *
 * <h2>"到期立即停止新内容进入 AI"为什么不需要额外代码</h2>
 * 资讯采集与 AI 证据两侧的授权闸门都读 {@code NewsSource.usableOn(today)}
 * （状态 + 区间 + 运行状态），且每次采集都从库重读来源——本服务把行改对，
 * 下一轮采集自然停，AI 证据侧立即生效。资讯域没有缓存（M3-04 决策），
 * 不存在要失效的副本。
 */
public class AdminNewsSourceService {

    private final AdminNewsSourceStore store;
    private final Clock clock;

    public AdminNewsSourceService(AdminNewsSourceStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** ADM-NEWS-01：来源分页。 */
    public PageData<NewsSource> list(AdminNewsSourceQuery query) {
        long total = store.count(query);
        List<NewsSource> items = total == 0 ? List.of() : store.page(query);
        return PageData.of(items, query.page(), query.size(), total);
    }

    /** ADM-NEWS-02：来源详情。 */
    public NewsSource detail(long sourceId) {
        return requireSource(sourceId);
    }

    /** ADM-NEWS-03：创建来源。 */
    @Transactional
    public NewsSource create(NewAdminNewsSource command) {
        if (command.sourceCode() == null || command.sourceCode().isBlank()) {
            throw AdminException.invalidRequest("sourceCode 不能为空");
        }
        if (command.sourceName() == null || command.sourceName().isBlank()) {
            throw AdminException.invalidRequest("sourceName 不能为空");
        }
        if (command.sourceType() == null) {
            throw AdminException.invalidRequest("sourceType 不能为空");
        }
        requireValidPeriod(command.rightsValidFrom(), command.rightsValidTo());

        store.findByCode(command.sourceCode()).ifPresent(existing -> {
            throw AdminException.newsSourceCodeExists(command.sourceCode());
        });
        NewsSource.AuthorizationStatus authorizationStatus =
                resolveOnCreate(command.rightsValidFrom(), command.rightsValidTo());
        try {
            return store.insert(command, authorizationStatus);
        } catch (DuplicateKeyException exception) {
            // 预检查与插入之间被并发抢先：唯一索引兜底，给出与预检查相同的答案。
            throw AdminException.newsSourceCodeExists(command.sourceCode());
        }
    }

    /** ADM-NEWS-04：修改来源（If-Match 乐观锁）。 */
    @Transactional
    public NewsSource update(long sourceId, int expectedVersion, AdminNewsSourcePatch patch) {
        if (patch.hasNoChange()) {
            throw AdminException.invalidRequest("PATCH 没有携带任何可修改字段");
        }
        NewsSource current = requireSource(sourceId);

        LocalDate mergedFrom = patch.rightsValidFrom().orElse(current.rightsValidFrom());
        LocalDate mergedTo = patch.rightsValidTo().orElse(current.rightsValidTo());
        requireValidPeriod(mergedFrom, mergedTo);

        NewsSource.AuthorizationStatus resolved = resolveOnPatch(current, patch, mergedFrom, mergedTo);
        Optional<NewsSource> updated = store.update(sourceId, expectedVersion, patch, resolved);
        return updated.orElseGet(() -> applyConflict(sourceId, expectedVersion));
    }

    // ---------- 内部 ----------

    private NewsSource requireSource(long sourceId) {
        return store.find(sourceId)
                .orElseThrow(() -> AdminException.newsSourceNotFound(sourceId));
    }

    /**
     * CAS 更新影响 0 行时，回读区分"来源不存在"与"版本过期"——
     * 404 与 409 是两种处置（前者找错资源，后者刷新重试），不能合并。
     */
    private NewsSource applyConflict(long sourceId, int expectedVersion) {
        NewsSource current = store.find(sourceId)
                .orElseThrow(() -> AdminException.newsSourceNotFound(sourceId));
        if (current.version() != expectedVersion) {
            throw AdminException.versionConflict(current.version());
        }
        // 版本相同却更新失败：数据库约束拒绝（如授权区间 CHECK）。给出与
        // 预检查一致的业务码，消息指向约束本身。
        throw AdminException.newsRightsPeriodInvalid("数据库校验拒绝了这次更新");
    }

    private void requireValidPeriod(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw AdminException.newsRightsPeriodInvalid(
                    "rightsValidTo（" + to + "）早于 rightsValidFrom（" + from + "）");
        }
    }

    private NewsSource.AuthorizationStatus resolveOnCreate(LocalDate from, LocalDate to) {
        // 后台新建的来源没有授权区间时从 UNKNOWN 起步：V4 的列默认值 AUTHORIZED
        // 是给采集侧 ensureAll 登记模拟来源用的；后台是人事入口，
        // "没登记区间就默认有效"正是契约禁止的伪造。
        return derive(from, to).orElse(NewsSource.AuthorizationStatus.UNKNOWN);
    }

    private NewsSource.AuthorizationStatus resolveOnPatch(
            NewsSource current,
            AdminNewsSourcePatch patch,
            LocalDate mergedFrom,
            LocalDate mergedTo) {
        if (patch.authorizationStatus().isPresent()) {
            NewsSource.AuthorizationStatus requested = patch.authorizationStatus().get();
            if (requested == NewsSource.AuthorizationStatus.AUTHORIZED
                    && derive(mergedFrom, mergedTo)
                            .orElse(NewsSource.AuthorizationStatus.EXPIRED)
                            != NewsSource.AuthorizationStatus.AUTHORIZED) {
                throw AdminException.newsRightsPeriodInvalid(
                        "授权区间未覆盖或已到期，不能把授权状态标为 AUTHORIZED");
            }
            return requested;
        }
        if (!patch.touchesRightsPeriod()) {
            return current.authorizationStatus();
        }
        if (current.authorizationStatus() == NewsSource.AuthorizationStatus.SUSPENDED) {
            // 人工暂停不被区间编辑悄悄解除；恢复授权是显式操作。
            return NewsSource.AuthorizationStatus.SUSPENDED;
        }
        return derive(mergedFrom, mergedTo).orElse(current.authorizationStatus());
    }

    private Optional<NewsSource.AuthorizationStatus> derive(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        if (from == null && to == null) {
            return Optional.empty();
        }
        if (to != null && to.isBefore(today)) {
            return Optional.of(NewsSource.AuthorizationStatus.EXPIRED);
        }
        return Optional.of(NewsSource.AuthorizationStatus.AUTHORIZED);
    }
}
