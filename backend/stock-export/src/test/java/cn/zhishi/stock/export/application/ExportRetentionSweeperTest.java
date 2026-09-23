package cn.zhishi.stock.export.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.export.domain.ExportFileStore;
import cn.zhishi.stock.export.domain.ExportJob;
import cn.zhishi.stock.export.domain.ExportJobStore;
import cn.zhishi.stock.export.domain.ExportRequest;
import cn.zhishi.stock.export.domain.ExportType;
import cn.zhishi.stock.export.domain.RankingExportFilters;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 到期导出清理（契约 §9.2："默认 24 小时过期"）。
 *
 * <p>读路径的懒清理（EXP-02 / EXP-03 读到已到期就顺手删）只覆盖"有人再来访问"的作业。
 * 一次性的导出，用户下载完就走了，之后不会有人再查它——文件会一直留在卷上。
 * 这一组用例钉的是**清理任务兜住这件事**，以及它出问题时的取舍。
 */
class ExportRetentionSweeperTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  private final FakeJobStore jobs = new FakeJobStore();
  private final FakeFileStore files = new FakeFileStore();

  @Test
  void deletesBothRecordAndFileForEveryExpiredJob() {
    jobs.seed("1").seed("2");

    assertThat(sweeper().sweep(100)).isEqualTo(2);

    assertThat(jobs.stored).isEmpty();
    assertThat(files.deleted).containsExactlyInAnyOrder("1", "2");
  }

  /** 先删文件、后删记录：记录是**唯一**能找到文件的东西，反过来会留下再也找不到的孤儿文件。 */
  @Test
  void deletesTheFileWhileTheRecordStillPointsAtIt() {
    jobs.seed("1");

    sweeper().sweep(100);

    assertThat(files.recordStillExistedWhenDeleted).containsEntry("1", true);
  }

  /**
   * 一个作业删不掉不该让同轮里其它几十个陪着一起等下一轮：
   * 失败的记录保持不动，下一轮自然会再被取出来重试。
   */
  @Test
  void keepsGoingWhenOneJobFailsAndRetriesItNextRound() {
    jobs.seed("1").seed("2").seed("3");
    files.failOn.add("2");

    assertThat(sweeper().sweep(100)).isEqualTo(2);

    assertThat(jobs.stored).containsOnlyKeys("2");
    assertThat(files.deleted).containsExactlyInAnyOrder("1", "3");
  }

  @Test
  void returnsZeroWhenNothingIsDue() {
    assertThat(sweeper().sweep(100)).isZero();

    assertThat(files.deleted).isEmpty();
  }

  /** 单轮上限要真的传下去：清理是后台任务，宁可每轮少做一点、失败时影响面小一点。 */
  @Test
  void passesTheBatchLimitToTheStore() {
    jobs.seed("1").seed("2").seed("3");

    sweeper().sweep(2);

    assertThat(jobs.lastLimit).isEqualTo(2);
    assertThat(jobs.stored).containsOnlyKeys("3");
  }

  /**
   * 索引里还留着、记录已经不在（TTL 先到期或上次删除删到一半）时，清理必须仍然成功：
   * 清理是幂等的，不能因为"想删的东西本来就没有"而报错、更不能卡在同一条上。
   */
  @Test
  void toleratesAJobWhoseRecordIsAlreadyGone() {
    jobs.seed("1");
    jobs.stored.clear();

    assertThat(sweeper().sweep(100)).isEqualTo(1);

    assertThat(jobs.purgeIndex).isEmpty();
  }

  private ExportRetentionSweeper sweeper() {
    return new ExportRetentionSweeper(jobs, files);
  }

  private static ExportJob job(String exportId) {
    return ExportJob.queued(
        exportId,
        7L,
        new ExportRequest(
            ExportType.STOCK_RANKING,
            new RankingExportFilters("GAINERS", null, null, null, null, null),
            List.of()),
        OffsetDateTime.ofInstant(Instant.parse("2026-09-20T00:00:00Z"), ZONE),
        Duration.ofHours(48));
  }

  private static final class FakeJobStore implements ExportJobStore {

    /** 记录本体（对应 Redis 里的 {@code export:job:{id}}）。 */
    private final LinkedHashMap<String, ExportJob> stored = new LinkedHashMap<>();

    /**
     * 待清理索引（对应 Redis 里的 {@code export:jobs:purge} 有序集合）。
     *
     * <p>刻意与 {@link #stored} 分开：两者是一个作业的两份独立状态，
     * 只有分开才能表达"索引里还留着、记录已经没了"这种真实会出现的错位
     * （TTL 先到期、上次删除删到一半）。共用一份的话这类用例根本写不出来。
     */
    private final List<String> purgeIndex = new ArrayList<>();

    private int lastLimit;

    private FakeJobStore seed(String exportId) {
      stored.put(exportId, job(exportId));
      purgeIndex.add(exportId);
      return this;
    }

    @Override
    public void save(ExportJob job) {
      stored.put(job.exportId(), job);
      if (!purgeIndex.contains(job.exportId())) {
        purgeIndex.add(job.exportId());
      }
    }

    @Override
    public Optional<ExportJob> find(String exportId) {
      return Optional.ofNullable(stored.get(exportId));
    }

    @Override
    public void delete(String exportId) {
      stored.remove(exportId);
      purgeIndex.remove(exportId);
    }

    @Override
    public List<String> findExpired(int limit) {
      lastLimit = limit;
      return purgeIndex.stream().limit(limit).toList();
    }
  }

  private final class FakeFileStore implements ExportFileStore {

    private final List<String> deleted = new ArrayList<>();
    private final List<String> failOn = new ArrayList<>();
    private final Map<String, Boolean> recordStillExistedWhenDeleted = new HashMap<>();

    @Override
    public void write(String exportId, String fileName, byte[] content) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<byte[]> read(String exportId, String fileName) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delete(String exportId) {
      if (failOn.contains(exportId)) {
        throw new IllegalStateException("卷异常：" + exportId);
      }
      recordStillExistedWhenDeleted.put(exportId, jobs.stored.containsKey(exportId));
      deleted.add(exportId);
    }
  }
}
