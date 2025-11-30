package com.phil.rest.model;

public class RestParam {
    // 参数类型枚举
    public enum ParamType {
        PATH,       // @PathVariable: /api/users/{id}
        QUERY,      // @RequestParam: /api/users?name=phil
        BODY,       // @RequestBody:  JSON Body
        HEADER      // @RequestHeader
    }

    private String name;
    private String value; // 默认值或示例值
    private ParamType type;
    private String dataType; // String, Integer, UserDTO...

    public RestParam(String name, String value, ParamType type, String dataType) {
        this.name = name;
        this.value = value;
        this.type = type;
        this.dataType = dataType;
    }

    // Getters and Setters
    public String getName() { return name; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public ParamType getType() { return type; }
    public String getDataType() { return dataType; }

    @Override
    public String toString() {
        return name + "=" + value + " (" + type + ")";
    }
}