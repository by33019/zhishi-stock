package cn.zhishi.stock.integration.market.tencent;

/**
 * 腾讯行情的抓取端口：一批代码（逗号分隔，如 {@code sh600000,sz000001}）进、
 * 响应原文出。解码与 HTTP 细节都封在实现里。
 *
 * <h2>为什么是个端口而不是直接调 HTTP</h2>
 * 解析器是纯函数（可用真实响应夹具全量断言），Provider 的编排逻辑（分片、
 * 换算、缓存）用假抓取器测试——真网络的不可达、频控、半截响应都不该
 * 拖着测试一起抖。
 */
public interface TencentQuoteFetcher {

    /**
     * 抓取一批代码的行情原文（GBK 解码后的文本）。
     *
     * @return 原文；调用方不区分"请求失败"与"空响应"以外的细节，
     *         实现方把网络异常翻译成运行时异常上抛。
     */
    String fetch(String codesCsv);
}
