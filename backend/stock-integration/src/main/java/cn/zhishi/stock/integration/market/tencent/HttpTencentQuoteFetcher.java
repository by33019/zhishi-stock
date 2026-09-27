package cn.zhishi.stock.integration.market.tencent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.Duration;

/**
 * {@link TencentQuoteFetcher} 的 JDK HttpClient 实现。
 *
 * <h2>GBK 是腾讯的既定编码</h2>
 * 响应体不带 charset 头且实为 GBK——按 UTF-8 解码会把所有证券名称变成乱码。
 * 名称进的是 QuoteSnapshot 的展示字段，乱码等于把错误的数据渲染给了用户。
 *
 * <h2>超时与失败语义</h2>
 * 单次请求 5 秒超时；HTTP 非 2xx 与 IO 异常都以运行时异常上抛，
 * 由 Provider 的调用方（定时任务或查询入口）按自己的降级策略处置——
 * 这里不做重试，避免把频控放大成封禁。
 */
public class HttpTencentQuoteFetcher implements TencentQuoteFetcher {

    private static final Charset GBK = Charset.forName("GBK");
    private static final String ENDPOINT = "https://qt.gtimg.cn/q=";

    private final HttpClient client;

    public HttpTencentQuoteFetcher() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    public HttpTencentQuoteFetcher(HttpClient client) {
        this.client = client;
    }

    @Override
    public String fetch(String codesCsv) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT + codesCsv))
                .timeout(Duration.ofSeconds(5))
                .header("Accept-Charset", "GBK")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response =
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("腾讯行情返回 HTTP " + response.statusCode());
            }
            return new String(response.body(), GBK);
        } catch (IOException exception) {
            throw new IllegalStateException("腾讯行情请求失败：" + codesCsv, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("腾讯行情请求被中断", exception);
        }
    }
}
