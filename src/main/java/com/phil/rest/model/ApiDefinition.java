package com.phil.rest.model;

public class ApiDefinition {
    private String method;      // GET, POST, etc.
    private String url;         // /api/v1/users
    private String className;   // UserController
    private String methodName;  // getUserById

    public ApiDefinition(String method, String url, String className, String methodName) {
        this.method = method;
        this.url = url;
        this.className = className;
        this.methodName = methodName;
    }

    // Getters and Setters
    public String getMethod() { return method; }
    public String getUrl() { return url; }
    public String getClassName() { return className; }
    public String getMethodName() { return methodName; }

    @Override
    public String toString() {
        return String.format("[%s] %s -> %s#%s", method, url, className, methodName);
    }
}