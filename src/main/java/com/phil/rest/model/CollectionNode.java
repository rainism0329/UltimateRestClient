package com.phil.rest.model;

import com.intellij.util.xmlb.annotations.Attribute;
import com.intellij.util.xmlb.annotations.Property;
import com.intellij.util.xmlb.annotations.Tag;
import com.intellij.util.xmlb.annotations.XCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Tag("node") // 在 XML 中显示为 <node>
public class CollectionNode {

    @Attribute("id") // 作为属性保存: <node id="...">
    private String id;

    @Attribute("name")
    private String name;

    @Attribute("isFolder")
    private boolean isFolder;

    @Property(surroundWithTag = false) // 直接展开，不包裹多余的 tag
    private SavedRequest request;

    // 关键点：显式告诉 IDEA 这是一个集合，并且元素的 tag 是 "node" (递归)
    @Tag("children")
    @XCollection(propertyElementName = "children", elementName = "node", style = XCollection.Style.v2)
    private List<CollectionNode> children = new ArrayList<>();

    public CollectionNode() {
        // 必须初始化 ID，防止反序列化时为空
        if (this.id == null) {
            this.id = UUID.randomUUID().toString();
        }
    }

    public static CollectionNode createFolder(String name) {
        CollectionNode node = new CollectionNode();
        node.id = UUID.randomUUID().toString();
        node.name = name;
        node.isFolder = true;
        return node;
    }

    public static CollectionNode createRequest(String name, SavedRequest request) {
        CollectionNode node = new CollectionNode();
        node.id = UUID.randomUUID().toString();
        node.name = name;
        node.isFolder = false;
        node.request = request;
        return node;
    }

    // Getters & Setters (必须有)
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
        if (this.children == null) this.children = new ArrayList<>();
        this.children.add(node);
    }

    @Override
    public String toString() { return name; }
}