package com.phil.rest.service;

import com.phil.rest.model.RestParam;
import com.phil.rest.model.RestResponse;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class HttpExecutor {

    static {
        // [关键] 禁用 Hostname 验证
        // Java 11+ HttpClient 默认非常严格，这是官方提供的系统属性开关
        // 允许请求 IP 地址即使证书是域名的，或者域名不匹配的情况
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
    }

    // 使用静态单例，复用连接池
    private static final HttpClient client = createInsecureClient();

    private static HttpClient createInsecureClient() {
        try {
            // 1. 创建一个“盲目信任”所有证书的 TrustManager
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };

            // 2. 初始化 SSLContext
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());

            // 3. 构建 HttpClient
            return HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(15)) // 稍微放宽超时时间
                    .sslContext(sslContext)                 // [关键] 注入不安全的 SSL 上下文
                    .followRedirects(HttpClient.Redirect.NORMAL) // [关键] 自动跟随重定向 (301/302)
                    .build();

        } catch (Exception e) {
            // 理论上不应该发生，如果发生了就降级到默认客户端
            System.err.println("Failed to create insecure HttpClient: " + e.getMessage());
            return HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        }
    }

    public RestResponse execute(String method, String url, String body, List<RestParam> headers) {
        if (!url.startsWith("http")) {
            url = "http://" + url;
        }

        long startTime = System.currentTimeMillis();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30));

            // 1. 设置默认 Content-Type
            boolean hasContentType = headers.stream().anyMatch(h -> "Content-Type".equalsIgnoreCase(h.getName()));
            if (!hasContentType) {
                // 如果有 Body 且没设置类型，默认给 JSON；没 Body 就不给
                if (body != null && !body.isBlank()) {
                    builder.header("Content-Type", "application/json");
                }
            }

            // 2. 填充用户 Headers
            for (RestParam header : headers) {
                if (header.getName() != null && !header.getName().isBlank()) {
                    String value = header.getValue() == null ? "" : header.getValue();
                    try {
                        builder.header(header.getName(), value);
                    } catch (IllegalArgumentException e) {
                        // 忽略非法 Header (比如包含换行符的)
                    }
                }
            }

            // 3. 构建 Body Publisher
            HttpRequest.BodyPublisher bodyPublisher = (body != null && !body.isBlank())
                    ? HttpRequest.BodyPublishers.ofString(body)
                    : HttpRequest.BodyPublishers.noBody();

            switch (method.toUpperCase()) {
                case "GET": builder.GET(); break;
                case "DELETE": builder.DELETE(); break;
                case "POST": builder.POST(bodyPublisher); break;
                case "PUT": builder.PUT(bodyPublisher); break;
                case "PATCH":
                    // Java HttpClient 原生支持 PATCH (Java 11+)
                    // 如果是老版本可能需要 method("PATCH", ...)
                    builder.method("PATCH", bodyPublisher);
                    break;
                case "HEAD": builder.method("HEAD", HttpRequest.BodyPublishers.noBody()); break;
                case "OPTIONS": builder.method("OPTIONS", HttpRequest.BodyPublishers.noBody()); break;
                default: builder.method(method, bodyPublisher);
            }

            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            long duration = System.currentTimeMillis() - startTime;

            return new RestResponse(
                    response.statusCode(),
                    response.body(),
                    response.headers().map(),
                    duration
            );

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            // 返回 0 状态码表示本地错误
            return new RestResponse(0, "Request Failed: " + e.toString(), Map.of(), duration);
        }
    }
}