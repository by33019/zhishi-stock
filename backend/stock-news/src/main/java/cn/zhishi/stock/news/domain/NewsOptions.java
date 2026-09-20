package cn.zhishi.stock.news.domain;

import java.util.List;

/**
 * 受控筛选项（NEWS-04）。
 *
 * <p>前端不应自己拼枚举取值：那会让"服务端新增一种资讯类型、前端筛选项少一个"
 * 这类不一致只能靠人工同步。这里把可选项与**筛选规则说明**一起给出。
 *
 * @param filterRules 当前生效的筛选规则（自然语言）。契约把它列为返回字段，
 *                    它同时是前端文案的依据——用户看到"为什么列表里只有这几条"时
 *                    需要一句可读的解释，而不是去读接口文档。
 */
public record NewsOptions(
        List<String> newsTypes,
        List<String> sourceTypes,
        NewsTimeRange availableTimeRange,
        String filterRules) {

    public NewsOptions {
        newsTypes = List.copyOf(newsTypes);
        sourceTypes = List.copyOf(sourceTypes);
    }
}
