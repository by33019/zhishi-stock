package cn.zhishi.stock.export.domain;

import java.util.List;
import java.util.Optional;

/**
 * 导出作业的仓储端口。
 *
 * <p>契约 §20 把导出归类为"短期任务状态读 Redis、不作为永久业务事实"，
 * 因此这里刻意**没有**"列出本人全部导出"这类查询——MVP 不提供导出历史
 * （契约 §9.2 末段明说不承诺重启后恢复与长期历史）。{@link #findExpired}
 * 是给清理用的内部查询，不是对外能力。
 */
public interface ExportJobStore {

    void save(ExportJob job);

    Optional<ExportJob> find(String exportId);

    void delete(String exportId);

    /**
     * 找出已经可以清理的作业 id，最多 {@code limit} 条。
     *
     * <p>"可以清理"指**记录本身的保留期已过**，而不是文件过期：文件到期（{@code expiresAt}）
     * 后作业已进入 {@code EXPIRED}、不再提供下载，但记录还要多留一段，
     * 好让"这个导出曾经存在但文件过期了"与"这个 id 从来不存在"分得开
     * （见 {@code ExportPolicy.RECORD_TTL}）。真正的删除由清理方执行。
     *
     * <p>返回 id 而不是作业，是因为清理只需要"删文件 + 删记录"两件事，
     * 而这两件事都只需要 id。
     */
    List<String> findExpired(int limit);
}
