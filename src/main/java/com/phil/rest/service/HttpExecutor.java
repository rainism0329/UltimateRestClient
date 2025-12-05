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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

    public CompletableFuture<RestResponse> executeAsync(
            String method,
            String url,
            String body,
            List<RestParam> headers,
            List<RestParam> multipartParams,
            long timeoutSeconds
    ) {
        if (!url.startsWith("http")) url = "http://" + url;

        // [Fix 1] 自动编码 URL 中的空格，防止 URI.create 报错
        // 这是一个简单而有效的修复，涵盖了 99% 的用户场景
        url = url.replace(" ", "%20");

        long finalTimeout = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
        long startTime = System.currentTimeMillis();

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(finalTimeout));

            // ... (Body 和 Header 逻辑保持不变，直接复用) ...
            HttpRequest.BodyPublisher bodyPublisher;
            if (multipartParams != null && !multipartParams.isEmpty()) {
                MultipartBodyPublisher multipartBuilder = new MultipartBodyPublisher();
                for (RestParam param : multipartParams) {
                    if ("File".equals(param.getDataType())) {
                        multipartBuilder.addPart(param.getName(), Path.of(param.getValue()));
                    } else {
                        multipartBuilder.addPart(param.getName(), param.getValue());
                    }
                }
                bodyPublisher = multipartBuilder.buildSimple();
                builder.header("Content-Type", "multipart/form-data; boundary=" + multipartBuilder.getBoundary());
            } else {
                boolean hasContentType = headers.stream().anyMatch(h -> "Content-Type".equalsIgnoreCase(h.getName()));
                if (!hasContentType && body != null && !body.isBlank()) {
                    builder.header("Content-Type", "application/json");
                }
                bodyPublisher = (body != null && !body.isBlank())
                        ? HttpRequest.BodyPublishers.ofString(body)
                        : HttpRequest.BodyPublishers.noBody();
            }

            for (RestParam header : headers) {
                if (header.getName() != null && !header.getName().isBlank()) {
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

            return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
                    .thenApply(response -> {
                        long duration = System.currentTimeMillis() - startTime;
                        byte[] rawBytes = response.body();
                        String bodyString = new String(rawBytes, StandardCharsets.UTF_8);
                        return new RestResponse(response.statusCode(), bodyString, rawBytes, response.headers().map(), duration);
                    })
                    .orTimeout(finalTimeout, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        long duration = System.currentTimeMillis() - startTime;
                        String errorMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        return new RestResponse(0, "Error: " + errorMsg, new byte[0], Map.of(), duration);
                    });

        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                    new RestResponse(0, "Build Error: " + e.getMessage(), new byte[0], Map.of(), 0)
            );
        }
    }

    public RestResponse execute(String method, String url, String body, List<RestParam> headers, List<RestParam> multipartParams) {
        try {
            return executeAsync(method, url, body, headers, multipartParams, 30).get();
        } catch (Exception e) {
            return new RestResponse(0, "Error: " + e.getMessage(), new byte[0], Map.of(), 0);
        }
    }
}