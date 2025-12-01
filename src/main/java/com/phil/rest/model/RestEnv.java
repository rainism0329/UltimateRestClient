package com.phil.rest.model;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RestEnv {
    private String id;
    private String name;
    private Map<String, String> variables = new HashMap<>();

    public RestEnv() {
        this.id = UUID.randomUUID().toString();
    }

    public RestEnv(String name) {
        this();
        this.name = name;
    }

    // Getters & Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Map<String, String> getVariables() { return variables; }
    public void setVariables(Map<String, String> variables) { this.variables = variables; }

    @Override
    public String toString() { return name; }
}