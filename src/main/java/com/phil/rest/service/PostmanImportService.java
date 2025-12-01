package com.phil.rest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phil.rest.model.CollectionNode;
import com.phil.rest.model.RestParam;
import com.phil.rest.model.SavedRequest;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class PostmanImportService {

    public List<CollectionNode> importCollection(File jsonFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(jsonFile);

        // Postman v2.1 结构: root -> item[]
        JsonNode itemsNode = root.get("item");
        if (itemsNode == null || !itemsNode.isArray()) {
            return Collections.emptyList();
        }

        List<CollectionNode> result = new ArrayList<>();
        // 递归解析 item
        for (JsonNode itemNode : itemsNode) {
            result.add(parseItem(itemNode));
        }

        // 如果 Collection 本身有名字，我们可以把它作为一个根文件夹包起来（可选）
        // String collectionName = root.path("info").path("name").asText("Imported Collection");
        // 但为了直接看到内容，我们直接返回顶层列表

        return result;
    }

    private CollectionNode parseItem(JsonNode itemNode) {
        String name = itemNode.path("name").asText("Untitled");

        // 判断是文件夹还是请求
        // 如果有 "item" 字段且是数组，则是文件夹
        if (itemNode.has("item") && itemNode.get("item").isArray()) {
            CollectionNode folder = CollectionNode.createFolder(name);
            for (JsonNode childNode : itemNode.get("item")) {
                folder.addChild(parseItem(childNode));
            }
            return folder;
        } else {
            // 是请求
            SavedRequest request = parseRequest(itemNode.path("request"), name);
            return CollectionNode.createRequest(name, request);
        }
    }

    private SavedRequest parseRequest(JsonNode requestNode, String defaultName) {
        SavedRequest req = new SavedRequest();
        req.setName(defaultName);

        // 1. Method
        req.setMethod(requestNode.path("method").asText("GET"));

        // 2. URL (Postman 的 URL 可能是字符串，也可能是对象)
        JsonNode urlNode = requestNode.get("url");
        if (urlNode != null) {
            if (urlNode.isTextual()) {
                req.setUrl(urlNode.asText());
            } else if (urlNode.isObject()) {
                // 如果是对象，通常取 raw
                String raw = urlNode.path("raw").asText();
                req.setUrl(raw);

                // 解析 Query Params
                JsonNode queryNode = urlNode.path("query");
                if (queryNode.isArray()) {
                    List<RestParam> params = new ArrayList<>();
                    for (JsonNode q : queryNode) {
                        if (!q.path("disabled").asBoolean(false)) {
                            params.add(new RestParam(
                                    q.path("key").asText(),
                                    q.path("value").asText(),
                                    RestParam.ParamType.QUERY,
                                    "String"
                            ));
                        }
                    }
                    req.setParams(params);
                }
            }
        }

        // 3. Headers
        JsonNode headerNode = requestNode.path("header");
        if (headerNode.isArray()) {
            List<RestParam> headers = new ArrayList<>();
            for (JsonNode h : headerNode) {
                if (!h.path("disabled").asBoolean(false)) {
                    headers.add(new RestParam(
                            h.path("key").asText(),
                            h.path("value").asText(),
                            RestParam.ParamType.HEADER,
                            "String"
                    ));
                }
            }
            req.setHeaders(headers);
        }

        // 4. Body
        JsonNode bodyNode = requestNode.path("body");
        String mode = bodyNode.path("mode").asText();
        if ("raw".equals(mode)) {
            req.setBodyContent(bodyNode.path("raw").asText());
        } else if ("graphql".equals(mode)) {
            req.setBodyContent(bodyNode.path("graphql").path("query").asText());
        }
        // TODO: 支持 urlencoded 和 formdata (需要更复杂的模型支持，目前暂略)

        return req;
    }
}