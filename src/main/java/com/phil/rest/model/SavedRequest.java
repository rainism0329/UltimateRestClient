package com.phil.rest.model;

import java.util.ArrayList;
import java.util.List;

// 这个类用于持久化保存，类似 Postman 的 Item
public class SavedRequest {
    private String id;
    private String name; // 显示的名称，比如 "创建用户-测试1"
    private String method;
    private String url;
    private String bodyType; // "json", "form-data", etc.
    private String bodyContent;

    // 我们复用之前定义的 RestParam，但要确保它有无参构造函数以便序列化
    private List<RestParam> params = new ArrayList<>();
    private List<RestParam> headers = new ArrayList<>();

    public SavedRequest() {}

    public SavedRequest(String name, String method, String url) {
        this.name = name;
        this.method = method;
        this.url = url;
    }

    // Getters Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getBodyContent() { return bodyContent; }
    public void setBodyContent(String bodyContent) { this.bodyContent = bodyContent; }
    public List<RestParam> getParams() { return params; }
    public void setParams(List<RestParam> params) { this.params = params; }
    public List<RestParam> getHeaders() { return headers; }
    public void setHeaders(List<RestParam> headers) { this.headers = headers; }

    @Override
    public String toString() {
        return name; // 树节点显示的名字
    }
}