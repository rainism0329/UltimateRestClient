package com.phil.rest.model;

import com.intellij.util.xmlb.annotations.Attribute;
import com.intellij.util.xmlb.annotations.Tag;

@Tag("param")
public class RestParam {
    public enum ParamType {
        PATH, QUERY, BODY, HEADER
    }

    @Attribute("name")
    private String name;

    @Attribute("value")
    private String value;

    @Attribute("type")
    private ParamType type;

    @Attribute("dataType")
    private String dataType;

    public RestParam() {} // 必须有无参构造

    public RestParam(String name, String value, ParamType type, String dataType) {
        this.name = name;
        this.value = value;
        this.type = type;
        this.dataType = dataType;
    }

    // Getters & Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public ParamType getType() { return type; }
    public void setType(ParamType type) { this.type = type; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }

    @Override
    public String toString() {
        return name + "=" + value;
    }
}