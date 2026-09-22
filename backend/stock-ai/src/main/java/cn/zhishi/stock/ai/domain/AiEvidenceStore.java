package cn.zhishi.stock.ai.domain;

import java.util.List;

/**
 * 报告证据仓储（{@code ai_evidence}）。
 *
 * <h2>写入只有一个动词，且是整批</h2>
 * 证据行在报告定稿时**一次性全量**写入——候选集合就是这次分析的全部事实来源，
 * 没有后续追加的语义。不提供 {@code insert} 单条版本：提供了就会有调用方
 * 在定稿之外补写，而那时报告已经发出，晚到的证据与正文引用对不上号。
 *
 * <h2>幂等由数据库兜底</h2>
 * {@code uk_ai_evidence_report_no} 保证同一报告同一编号只有一行。
 * 与报告本身的 {@code uk_ai_report_task} 是同一层防护：恢复扫描若把
 * 已写过报告的任务再跑一遍，会先在报告插入上失败，根本走不到证据写入。
 */
public interface AiEvidenceStore {

    /** 整批写入一份报告的全部证据行。列表必须按 {@code evidenceNo} 升序。 */
    void insertAll(List<AiEvidence> evidences);

    /** 某份报告的全部证据，按 {@code evidenceNo} 升序。没有证据时返回空列表。 */
    List<AiEvidence> listByReport(long reportId);
}
