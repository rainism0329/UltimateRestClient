package com.phil.rest.service;

import com.phil.rest.model.RestParam;
import com.phil.rest.model.RestResponse;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.CookieManager;
import java.net.CookiePolicy;
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
        // 禁用 Hostname 验证 (支持 IP 直连 HTTPS)
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
    }

    // [完美 Cookie 支持]
    // 1. 全局单例 CookieManager: 保证 Cookie 在不同请求间共享
    // 2. ACCEPT_ALL: 允许接收所有 Cookie (包括第三方)，这对开发测试最友好
    private static final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

    // 使用静态单例 Client，复用连接池和 CookieStore
    private static final HttpClient client = createInsecureClient();

    /**
     * 清除所有 Cookie (模拟退出登录/清空缓存)
     */
    public static void clearCookies() {
        cookieManager.getCookieStore().removeAll();
    }

    private static HttpClient createInsecureClient() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());

            return HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(15))
                    .sslContext(sslContext)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .cookieHandler(cookieManager) // [关键] 注入 Cookie 管理器
                    .build();

        } catch (Exception e) {
            System.err.println("Failed to create insecure HttpClient: " + e.getMessage());
            // 降级，但也带上 Cookie 支持
            return HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(10))
                    .cookieHandler(cookieManager)
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

            // 1. Content-Type 自动补全
            boolean hasContentType = headers.stream().anyMatch(h -> "Content-Type".equalsIgnoreCase(h.getName()));
            if (!hasContentType) {
                if (body != null && !body.isBlank()) {
                    builder.header("Content-Type", "application/json");
                }
            }

            // 2. 填充 Headers
            for (RestParam header : headers) {
                if (header.getName() != null && !header.getName().isBlank()) {
                    String value = header.getValue() == null ? "" : header.getValue();
                    try {
                        builder.header(header.getName(), value);
                    } catch (IllegalArgumentException e) {
                        // ignore invalid headers
                    }
                }
            }

            // 3. Body
            HttpRequest.BodyPublisher bodyPublisher = (body != null && !body.isBlank())
                    ? HttpRequest.BodyPublishers.ofString(body)
                    : HttpRequest.BodyPublishers.noBody();

            switch (method.toUpperCase()) {
                case "GET": builder.GET(); break;
                case "DELETE": builder.DELETE(); break;
                case "POST": builder.POST(bodyPublisher); break;
                case "PUT": builder.PUT(bodyPublisher); break;
                case "PATCH": builder.method("PATCH", bodyPublisher); break;
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
            return new RestResponse(0, "Request Failed: " + e.toString(), Map.of(), duration);
        }
    }
}