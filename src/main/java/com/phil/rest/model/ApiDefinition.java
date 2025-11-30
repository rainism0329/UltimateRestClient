package com.phil.rest.model;

import java.util.ArrayList;
import java.util.List;

public class ApiDefinition {
    private String method;
    private String url;
    private String className;
    private String methodName;
    private List<RestParam> params; // 新增：参数列表

    public ApiDefinition(String method, String url, String className, String methodName) {
        this.method = method;
        this.url = url;
        this.className = className;
        this.methodName = methodName;
        this.params = new ArrayList<>();
    }

    public List<RestParam> getParams() { return params; }

    public void addParam(RestParam param) {
        this.params.add(param);
    }

    public String getMethod() { return method; }
    public String getUrl() { return url; }
    public String getClassName() { return className; }
    public String getMethodName() { return methodName; }

    @Override
    public String toString() {
        return String.format("[%s] %s", method, url);
    }
}