package com.phil.rest.service;

import com.phil.rest.model.RestParam;
import com.phil.rest.model.RestResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class HttpExecutor {

    private static final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // 修改：增加 headers 参数
    public RestResponse execute(String method, String url, String body, List<RestParam> headers) {
        if (!url.startsWith("http")) {
            url = "http://" + url;
        }

        long startTime = System.currentTimeMillis();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30));

            // 1. 设置默认 Content-Type (如果用户没填)
            boolean hasContentType = headers.stream().anyMatch(h -> h.getName().equalsIgnoreCase("Content-Type"));
            if (!hasContentType) {
                builder.header("Content-Type", "application/json");
            }

            // 2. 填充用户 Headers
            for (RestParam header : headers) {
                if (header.getName() != null && !header.getName().isBlank()) {
                    // HttpClient 不允许 value 为 null，处理一下
                    String value = header.getValue() == null ? "" : header.getValue();
                    builder.header(header.getName(), value);
                }
            }

            // 3. 构建 Body
            HttpRequest.BodyPublisher bodyPublisher = (body != null && !body.isBlank())
                    ? HttpRequest.BodyPublishers.ofString(body)
                    : HttpRequest.BodyPublishers.noBody();

            switch (method.toUpperCase()) {
                case "GET": builder.GET(); break;
                case "DELETE": builder.DELETE(); break;
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