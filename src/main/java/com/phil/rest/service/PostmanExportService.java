package com.phil.rest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.phil.rest.model.ApiDefinition;
import com.phil.rest.model.CollectionNode;
import com.phil.rest.model.RestParam;
import com.phil.rest.model.SavedRequest;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class PostmanExportService {

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * 导出 Collections (Saved Requests)
     */
    public void exportCollections(List<CollectionNode> nodes, File file) throws IOException {
        ObjectNode root = mapper.createObjectNode();

        // Postman Info Header
        ObjectNode info = root.putObject("info");
        info.put("name", "UltimateREST Export");
        info.put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json");

        ArrayNode itemArray = root.putArray("item");
        for (CollectionNode node : nodes) {
            itemArray.add(convertNode(node));
        }

        mapper.writeValue(file, root);
    }

    /**
     * 导出 Live APIs (ApiDefinition)
     * 将其扁平化导出，或者按 Controller 分组导出
     */
    public void exportLiveApis(String groupName, List<ApiDefinition> apis, File file) throws IOException {
        ObjectNode root = mapper.createObjectNode();

        ObjectNode info = root.putObject("info");
        info.put("name", groupName); // 使用项目名或 Controller 名
        info.put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json");

        ArrayNode itemArray = root.putArray("item");

        // 简单起见，Live 模式我们直接扁平化生成 Request
        for (ApiDefinition api : apis) {
            itemArray.add(convertApiToPostmanItem(api));
        }

        mapper.writeValue(file, root);
    }

    // --- 转换逻辑: CollectionNode -> Postman Item ---
    private ObjectNode convertNode(CollectionNode node) {
        ObjectNode item = mapper.createObjectNode();
        item.put("name", node.getName());

        if (node.isFolder()) {
            ArrayNode children = item.putArray("item");
            for (CollectionNode child : node.getChildren()) {
                children.add(convertNode(child));
            }
        } else {
            // 是请求
            item.set("request", buildRequestNode(node.getRequest()));
        }
        return item;
    }

    // --- 转换逻辑: ApiDefinition -> Postman Item ---
    private ObjectNode convertApiToPostmanItem(ApiDefinition api) {
        ObjectNode item = mapper.createObjectNode();
        item.put("name", api.getMethodName()); // Item 名字

        // 构造临时的 SavedRequest 方便复用逻辑
        SavedRequest tempReq = new SavedRequest();
        tempReq.setMethod(api.getMethod());
        tempReq.setUrl(api.getUrl()); // 注意：这里通常是相对路径，导出时可能需要补全 host

        // 转换参数
        for (RestParam p : api.getParams()) {
            if (p.getType() == RestParam.ParamType.QUERY) {
                tempReq.getParams().add(p);
            } else if (p.getType() == RestParam.ParamType.HEADER) {
                tempReq.getHeaders().add(p);
            } else if (p.getType() == RestParam.ParamType.BODY) {
                tempReq.setBodyContent(p.getValue());
            }
        }

        item.set("request", buildRequestNode(tempReq));
        return item;
    }

    // --- 核心：构建 Postman Request 节点 ---
    private ObjectNode buildRequestNode(SavedRequest req) {
        ObjectNode request = mapper.createObjectNode();
        request.put("method", req.getMethod().toUpperCase());

        // 1. Header
        ArrayNode headerArray = request.putArray("header");
        for (RestParam h : req.getHeaders()) {
            ObjectNode hNode = headerArray.addObject();
            hNode.put("key", h.getName());
            hNode.put("value", h.getValue());
            hNode.put("type", "text");
        }

        // 2. Body
        ObjectNode body = request.putObject("body");
        if (req.getBodyContent() != null && !req.getBodyContent().isBlank()) {
            body.put("mode", "raw");
            ObjectNode raw = body.putObject("raw");
            raw.put("language", "json");
            body.put("raw", req.getBodyContent());
        } else {
            body.put("mode", "none");
        }

        // 3. URL
        ObjectNode url = request.putObject("url");
        // 简单处理：直接把完整 URL 塞给 raw
        // Postman 标准格式其实需要拆分 host, path, query
        url.put("raw", req.getUrl());
        // 如果需要更精细，可以解析 req.getUrl() 并拆分 host 和 path
        // 也可以把 Query Param 塞进去
        ArrayNode queryArray = url.putArray("query");
        for (RestParam p : req.getParams()) {
            ObjectNode q = queryArray.addObject();
            q.put("key", p.getName());
            q.put("value", p.getValue());
        }

        return request;
    }
}