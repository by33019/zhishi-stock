package cn.zhishi.stock.market.domain;

import java.util.List;

/** 搜索建议结果。 */
public record SecuritySearchResult(List<SecuritySearchMatch> items) {

    public SecuritySearchResult {
        items = List.copyOf(items);
    }
}
