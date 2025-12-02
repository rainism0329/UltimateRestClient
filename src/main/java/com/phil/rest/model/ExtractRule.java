package com.phil.rest.model;

import com.intellij.util.xmlb.annotations.Attribute;
import com.intellij.util.xmlb.annotations.Tag;

@Tag("extract")
public class ExtractRule {

    @Attribute("variable")
    private String variable; // 存入的环境变量名 (例如: access_token)

    @Attribute("path")
    private String path;     // JSON 路径 (例如: data.token)

    public ExtractRule() {}

    public ExtractRule(String variable, String path) {
        this.variable = variable;
        this.path = path;
    }

    public String getVariable() { return variable; }
    public void setVariable(String variable) { this.variable = variable; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
}