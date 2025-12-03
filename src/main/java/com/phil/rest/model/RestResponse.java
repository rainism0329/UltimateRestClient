package com.phil.rest.model;

import java.util.List;
import java.util.Map;

public class RestResponse {
    private final int statusCode;
    private final String body;        // 用于显示文本/JSON
    private final byte[] rawBody;     // [新增] 用于显示图片/文件
    private final Map<String, List<String>> headers;
    private final long durationMs;

    // [修改] 构造函数增加 byte[] rawBody
    public RestResponse(int statusCode, String body, byte[] rawBody, Map<String, List<String>> headers, long durationMs) {
        this.statusCode = statusCode;
        this.body = body;
        this.rawBody = rawBody;
        this.headers = headers;
        this.durationMs = durationMs;
    }

    public int getStatusCode() { return statusCode; }
    public String getBody() { return body; }
    public byte[] getRawBody() { return rawBody; } // [新增] Getter
    public Map<String, List<String>> getHeaders() { return headers; }
    public long getDurationMs() { return durationMs; }

    public String getHeadersString() {
        StringBuilder sb = new StringBuilder();
        headers.forEach((k, v) -> sb.append(k).append(": ").append(String.join(",", v)).append("\n"));
        return sb.toString();
    }
}