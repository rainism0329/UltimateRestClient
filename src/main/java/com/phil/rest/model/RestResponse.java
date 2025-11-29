package com.phil.rest.model;

import java.util.List;
import java.util.Map;

public class RestResponse {
    private final int statusCode;
    private final String body;
    private final Map<String, List<String>> headers;
    private final long durationMs;

    public RestResponse(int statusCode, String body, Map<String, List<String>> headers, long durationMs) {
        this.statusCode = statusCode;
        this.body = body;
        this.headers = headers;
        this.durationMs = durationMs;
    }

    public int getStatusCode() { return statusCode; }
    public String getBody() { return body; }
    public Map<String, List<String>> getHeaders() { return headers; }
    public long getDurationMs() { return durationMs; }

    // 简单的格式化输出，用于在 UI 上展示头信息
    public String getHeadersString() {
        StringBuilder sb = new StringBuilder();
        headers.forEach((k, v) -> sb.append(k).append(": ").append(String.join(",", v)).append("\n"));
        return sb.toString();
    }
}