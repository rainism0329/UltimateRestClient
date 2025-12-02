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
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class HttpExecutor {

    static {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
    }

    private static final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private static final HttpClient client = createInsecureClient();

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
                    .cookieHandler(cookieManager)
                    .build();
        } catch (Exception e) {
            return HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(10))
                    .cookieHandler(cookieManager)
                    .build();
        }
    }

    // [重构] 增加 multipartParams 参数
    // 如果这个 list 不为空，则 body 参数被忽略，转而构建 Multipart
    public RestResponse execute(String method, String url, String body, List<RestParam> headers, List<RestParam> multipartParams) {
        if (!url.startsWith("http")) url = "http://" + url;

        long startTime = System.currentTimeMillis();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30));

            // 构建 Body Publisher
            HttpRequest.BodyPublisher bodyPublisher;

            if (multipartParams != null && !multipartParams.isEmpty()) {
                // --- Multipart 模式 ---
                MultipartBodyPublisher multipartBuilder = new MultipartBodyPublisher();
                for (RestParam param : multipartParams) {
                    if ("File".equals(param.getDataType())) {
                        // 是文件
                        multipartBuilder.addPart(param.getName(), Path.of(param.getValue()));
                    } else {
                        // 是文本
                        multipartBuilder.addPart(param.getName(), param.getValue());
                    }
                }
                bodyPublisher = multipartBuilder.buildSimple();

                // 必须显式设置 Content-Type 并带上 boundary
                builder.header("Content-Type", "multipart/form-data; boundary=" + multipartBuilder.getBoundary());
            } else {
                // --- 普通模式 ---
                // 1. Content-Type 自动补全
                boolean hasContentType = headers.stream().anyMatch(h -> "Content-Type".equalsIgnoreCase(h.getName()));
                if (!hasContentType && body != null && !body.isBlank()) {
                    builder.header("Content-Type", "application/json");
                }

                bodyPublisher = (body != null && !body.isBlank())
                        ? HttpRequest.BodyPublishers.ofString(body)
                        : HttpRequest.BodyPublishers.noBody();
            }

            // 2. 填充 Headers (注意不要覆盖 Multipart 的 Content-Type)
            for (RestParam header : headers) {
                if (header.getName() != null && !header.getName().isBlank()) {
                    // 如果是 Multipart 模式，跳过用户自定义的 Content-Type，防止覆盖 boundary
                    if (multipartParams != null && !multipartParams.isEmpty() && "Content-Type".equalsIgnoreCase(header.getName())) {
                        continue;
                    }
                    try {
                        builder.header(header.getName(), header.getValue() == null ? "" : header.getValue());
                    } catch (Exception e) {}
                }
            }

            switch (method.toUpperCase()) {
                case "GET": builder.GET(); break;
                case "DELETE": builder.DELETE(); break;
                case "POST": builder.POST(bodyPublisher); break;
                case "PUT": builder.PUT(bodyPublisher); break;
                case "PATCH": builder.method("PATCH", bodyPublisher); break;
                default: builder.method(method, bodyPublisher);
            }

            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            long duration = System.currentTimeMillis() - startTime;

            return new RestResponse(response.statusCode(), response.body(), response.headers().map(), duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            return new RestResponse(0, "Request Failed: " + e.toString(), Map.of(), duration);
        }
    }

    // 重载旧方法，保持兼容
    public RestResponse execute(String method, String url, String body, List<RestParam> headers) {
        return execute(method, url, body, headers, null);
    }
}