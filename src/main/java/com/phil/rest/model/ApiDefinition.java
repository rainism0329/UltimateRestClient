package com.phil.rest.model;

import com.intellij.util.xmlb.annotations.Attribute;
import com.intellij.util.xmlb.annotations.Tag;
import com.intellij.util.xmlb.annotations.XCollection;

import java.util.ArrayList;
import java.util.List;

@Tag("api")
public class ApiDefinition {
    @Attribute("method")
    private String method;

    @Attribute("url")
    private String url;

    @Attribute("className")
    private String className;

    @Attribute("methodName")
    private String methodName;

    @Attribute("moduleName") // [新增] 模块名称
    private String moduleName;

    @Tag("params")
    @XCollection(style = XCollection.Style.v2)
    private List<RestParam> params = new ArrayList<>();

    // [必须] 无参构造函数用于序列化
    public ApiDefinition() {}

    public ApiDefinition(String method, String url, String className, String methodName, String moduleName) {
        this.method = method;
        this.url = url;
        this.className = className;
        this.methodName = methodName;
        this.moduleName = moduleName;
    }

    // Getters & Setters
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }
    public String getModuleName() { return moduleName; } // [新增]
    public void setModuleName(String moduleName) { this.moduleName = moduleName; }
    public List<RestParam> getParams() { return params; }
    public void setParams(List<RestParam> params) { this.params = params; }

    public void addParam(RestParam param) {
        this.params.add(param);
    }

    @Override
    public String toString() {
        return String.format("[%s] %s", method, url);
    }
}