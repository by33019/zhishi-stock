package cn.zhishi.stock.export.application;

import cn.zhishi.stock.export.domain.ExportFileStore;
import cn.zhishi.stock.export.domain.ExportJobStore;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 清理到期的导出作业与文件。
 *
 * <h2>为什么必须有它，而不是只靠读时懒清理</h2>
 * EXP-02 / EXP-03 会在读到已到期作业时顺手把它标记为 {@code EXPIRED} 并删文件
 * （见 {@link ExportJobService#expire}）。但那只覆盖"有人再来访问"的作业：
 * 一次性的导出，用户下载完就走了，之后不会有人再查它——文件就会一直留在卷上，
 * 而索引里的 id 也一直占着一行。清理任务不看状态、只看"到没到可清理的时刻"，
 * 因此这两种情况都能收尾。
 *
 * <h2>先删文件，后删记录</h2>
 * 顺序不能反。记录是**唯一**能找到文件的东西：先删记录再删文件，
 * 一旦进程在两步之间退出，那份文件就再也没有任何线索指向它，只能靠人工翻卷。
 * 反过来则最多是"记录多留一轮"，下一轮重试即可。
 *
 * <h2>单个作业失败不阻断整轮</h2>
 * 一个作业删不掉（权限、卷异常）不应该让同轮里其它几十个作业陪着一起等下一轮；
 * 失败的记录保持不动，下一轮自然会再被取出来重试。
 */
public class ExportRetentionSweeper {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExportRetentionSweeper.class);

    private final ExportJobStore jobs;
    private final ExportFileStore files;

    public ExportRetentionSweeper(ExportJobStore jobs, ExportFileStore files) {
        this.jobs = jobs;
        this.files = files;
    }

    /**
     * 清理一轮，返回本轮真正清掉的作业数。
     *
     * @param limit 单轮上限。不用"一次清完"：清理是后台任务，宁可每轮少做一点、
     *              失败时影响面小一点，也不要在一个长事务里把整卷读一遍。
     */
    public int sweep(int limit) {
        List<String> expired = jobs.findExpired(limit);
        if (expired.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (String exportId : expired) {
            if (purge(exportId)) {
                removed++;
            }
        }
        LOGGER.info("导出清理完成：候选 {} 个，实际清理 {} 个", expired.size(), removed);
        return removed;
    }

    private boolean purge(String exportId) {
        try {
            files.delete(exportId);
            jobs.delete(exportId);
            return true;
        } catch (RuntimeException exception) {
            LOGGER.error("导出作业清理失败，保留记录待下一轮重试：exportId={}", exportId, exception);
            return false;
        }
    }
}
