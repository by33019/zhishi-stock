package cn.zhishi.stock.news.domain;

/**
 * 关联解析所需的证券 / 板块目录的来源端口。
 *
 * <p>为什么要有这个端口，而不是让采集服务直接依赖行情域的四个端口
 * （{@code SecurityMasterProvider} + {@code SecurityIdentityProvider} +
 * {@code SectorProvider} + {@code SectorIdentityProvider}）：
 * 那会让采集服务的构造参数涨到十一个，而其中四个只是"把目录拼出来"这一件事的零件。
 * 目录是**一次全取**的只读快照，装配细节属于集成层，不该渗进用例层。
 *
 * <p>目录在每次采集时重新加载：证券与板块主数据会变（新上市、新板块），
 * 缓存它就要处理失效，而重新加载的成本与一次 STK-01 搜索同量级。
 */
public interface RelationCatalogProvider {

    /** 加载当前可被匹配的全部证券与板块。 */
    RelationCatalog load();
}
