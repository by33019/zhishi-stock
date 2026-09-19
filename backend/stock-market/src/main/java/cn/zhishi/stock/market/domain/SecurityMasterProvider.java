package cn.zhishi.stock.market.domain;

import java.util.List;

/**
 * 证券主数据的数据来源端口。
 *
 * <p>当前唯一实现是确定性模拟实现；真实主数据源就位后只需替换实现类，应用层不改动。
 */
@FunctionalInterface
public interface SecurityMasterProvider {

    /** 返回指定市场的全部证券主数据；市场不受支持时返回空列表。 */
    List<SecuritySummary> findAll(String marketCode);
}
