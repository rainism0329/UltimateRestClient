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

    // 修改点：增加 body 参数
    public RestResponse execute(String method, String url, String body) {
        if (!url.startsWith("http")) {
            url = "http://" + url;
        }

        long startTime = System.currentTimeMillis();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json") // 默认发送 JSON
                    .timeout(Duration.ofSeconds(30));

            // 构建 BodyPublisher
            HttpRequest.BodyPublisher bodyPublisher = (body != null && !body.isBlank())
                    ? HttpRequest.BodyPublishers.ofString(body)
                    : HttpRequest.BodyPublishers.noBody();

            // 根据 Method 构建请求
            switch (method.toUpperCase()) {
                case "GET": builder.GET(); break;
                case "DELETE": builder.DELETE(); break;
                // POST 和 PUT 使用传入的 body
                case "POST": builder.POST(bodyPublisher); break;
                case "PUT": builder.PUT(bodyPublisher); break;
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
            return new RestResponse(0, "Error: " + e.getMessage(), Map.of(), duration);
        }
    }
}