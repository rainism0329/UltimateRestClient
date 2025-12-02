package com.phil.rest.model;

import com.intellij.util.xmlb.annotations.Attribute;
import com.intellij.util.xmlb.annotations.MapAnnotation;
import com.intellij.util.xmlb.annotations.Tag;
import com.intellij.util.xmlb.annotations.XCollection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag("request")
public class SavedRequest {
    @Attribute("id")
    private String id;

    @Attribute("name")
    private String name;

    @Attribute("method")
    private String method;

    @Attribute("url")
    private String url;

    @Attribute("bodyType")
    private String bodyType;

    @Tag("bodyContent") // 内容较长，用 Tag 包裹
    private String bodyContent;

    @Attribute("authType")
    private String authType = "noauth";

    // Map 序列化配置
    @Tag("authContent")
    @MapAnnotation(surroundWithTag = false, entryTagName = "entry", keyAttributeName = "key", valueAttributeName = "value")
    private Map<String, String> authContent = new HashMap<>();

    @Tag("params")
    @XCollection(style = XCollection.Style.v2)
    private List<RestParam> params = new ArrayList<>();

    @Tag("headers")
    @XCollection(style = XCollection.Style.v2)
    private List<RestParam> headers = new ArrayList<>();

    public SavedRequest() {}

    public SavedRequest(String name, String method, String url) {
        this.name = name;
        this.method = method;
        this.url = url;
    }

    // Getters & Setters
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
    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }
    public Map<String, String> getAuthContent() { return authContent; }
    public void setAuthContent(Map<String, String> authContent) { this.authContent = authContent; }

    public String getBodyType() {
        return bodyType;
    }

    public void setBodyType(String bodyType) {
        this.bodyType = bodyType;
    }

    @Override
    public String toString() { return name; }
}