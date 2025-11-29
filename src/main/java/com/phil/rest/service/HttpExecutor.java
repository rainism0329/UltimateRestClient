package com.phil.rest.service;

import com.phil.rest.model.RestResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public class HttpExecutor {

    private static final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public RestResponse execute(String method, String url) {
        // 确保 URL 包含协议
        if (!url.startsWith("http")) {
            url = "http://" + url; // 默认补全 http (本地调试常用)
        }

        long startTime = System.currentTimeMillis();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30));

            // 根据 Method 构建请求 (暂时只处理无 Body 的情况，后续再加 POST Body)
            switch (method.toUpperCase()) {
                case "GET": builder.GET(); break;
                case "DELETE": builder.DELETE(); break;
                case "POST": builder.POST(HttpRequest.BodyPublishers.noBody()); break; // 暂无 Body
                case "PUT": builder.PUT(HttpRequest.BodyPublishers.noBody()); break;   // 暂无 Body
                default: builder.method(method, HttpRequest.BodyPublishers.noBody());
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
            // 发生异常时，返回一个错误的响应对象
            long duration = System.currentTimeMillis() - startTime;
            return new RestResponse(
                    0,
                    "Error: " + e.getMessage(),
                    Map.of(),
                    duration
            );
        }
    }
}