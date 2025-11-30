package com.phil.rest.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CollectionNode {
    private String id;
    private String name;
    private boolean isFolder;
    private SavedRequest request; // 如果是请求，这里存详情；如果是文件夹，这里为 null
    private List<CollectionNode> children;

    // 无参构造 (反序列化必须)
    public CollectionNode() {
        this.children = new ArrayList<>();
    }

    // 创建文件夹的工厂方法
    public static CollectionNode createFolder(String name) {
        CollectionNode node = new CollectionNode();
        node.id = UUID.randomUUID().toString();
        node.name = name;
        node.isFolder = true;
        return node;
    }

    // 创建请求的工厂方法
    public static CollectionNode createRequest(String name, SavedRequest request) {
        CollectionNode node = new CollectionNode();
        node.id = UUID.randomUUID().toString();
        node.name = name;
        node.isFolder = false;
        node.request = request;
        return node;
    }

    // Getters & Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isFolder() { return isFolder; }
    public void setFolder(boolean folder) { isFolder = folder; }
    public SavedRequest getRequest() { return request; }
    public void setRequest(SavedRequest request) { this.request = request; }
    public List<CollectionNode> getChildren() { return children; }
    public void setChildren(List<CollectionNode> children) { this.children = children; }

    public void addChild(CollectionNode node) {
        this.children.add(node);
    }

    @Override
    public String toString() {
        return name; // 树节点显示的文本
    }
}