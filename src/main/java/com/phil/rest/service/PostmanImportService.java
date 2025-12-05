package com.phil.rest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.project.Project;
import com.phil.rest.model.CollectionNode;
import com.phil.rest.model.RestEnv;
import com.phil.rest.model.RestParam;
import com.phil.rest.model.SavedRequest;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class PostmanImportService {

    /**
     * 智能导入入口：自动识别是 Collection 文件还是 Dump (Backup) 文件
     */
    public String importDump(File jsonFile, Project project) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(jsonFile);

        int colCount = 0;
        int envCount = 0;

        // --- 1. 尝试作为 Dump 文件解析 (包含 collections, environments, globals) ---

        // A. 导入 Environments
        JsonNode envsNode = root.get("environments");
        if (envsNode != null && envsNode.isArray()) {
            EnvService envService = EnvService.getInstance(project);
            for (JsonNode envNode : envsNode) {
                RestEnv env = parseEnv(envNode);
                if (env != null) {
                    envService.addEnv(env);
                    envCount++;
                }
            }
        }

        // B. 导入 Globals (作为名为 "Globals" 的环境)
        JsonNode globalsNode = root.get("globals");
        if (globalsNode != null) {
            RestEnv importedGlobals = parseEnv(globalsNode);
            if (importedGlobals != null) {
                // [核心修改] 将导入的全局变量覆盖/合并到当前的 GlobalEnv
                EnvService.getInstance(project).getGlobalEnv().setVariables(importedGlobals.getVariables());
                // 注意：不计入 envCount，因为它不是一个新的 Environment 选项
            }
        }

        // C. 导入 Collections (Dump 格式)
        JsonNode collectionsNode = root.get("collections");
        if (collectionsNode != null && collectionsNode.isArray()) {
            CollectionService colService = CollectionService.getInstance(project);
            List<CollectionNode> dumpRoots = parseDumpCollections(collectionsNode);
            for (CollectionNode node : dumpRoots) {
                colService.addRootNode(node);
                colCount++;
            }
        }

        // --- 2. 如果上面都没命中，尝试作为单个 Collection 导出文件解析 ---
        if (colCount == 0 && envCount == 0) {
            // 标准 v2.1 格式
            List<CollectionNode> nodes = parseStandardCollectionNodes(root);
            if (!nodes.isEmpty()) {
                String name = root.path("info").path("name").asText(jsonFile.getName().replace(".json", ""));
                CollectionNode folder = CollectionNode.createFolder(name);
                folder.setChildren(nodes);
                CollectionService.getInstance(project).addRootNode(folder);
                return "Imported 1 Collection (Standard Format).";
            }
        }

        return String.format("Imported %d Collections and %d Environments (Dump Format).", colCount, envCount);
    }

    // --- Dump 格式解析逻辑 (扁平结构 -> 树状结构) ---
    private List<CollectionNode> parseDumpCollections(JsonNode collectionsNode) {
        List<CollectionNode> roots = new ArrayList<>();

        for (JsonNode colNode : collectionsNode) {
            String colName = colNode.path("name").asText("Imported Collection");

            // 1. 预加载所有 Requests (Map ID -> Request)
            Map<String, SavedRequest> requestMap = new HashMap<>();
            for (JsonNode rNode : colNode.path("requests")) {
                requestMap.put(rNode.path("id").asText(), parseDumpRequest(rNode));
            }

            // 2. 预加载所有 Folders (Map ID -> FolderNode)
            Map<String, CollectionNode> folderMap = new HashMap<>();
            JsonNode foldersNode = colNode.path("folders");
            for (JsonNode fNode : foldersNode) {
                folderMap.put(fNode.path("id").asText(), CollectionNode.createFolder(fNode.path("name").asText()));
            }

            // 3. 构建文件夹内容 (根据 folder 的 order 数组)
            for (JsonNode fNode : foldersNode) {
                String fId = fNode.path("id").asText();
                CollectionNode folder = folderMap.get(fId);
                if (folder != null) {
                    JsonNode orderNode = fNode.path("order"); // 文件夹内的排序
                    if (orderNode.isArray()) {
                        for (JsonNode idNode : orderNode) {
                            String childId = idNode.asText();
                            // 暂时只处理文件夹内的请求 (Dump 里的文件夹一般只有请求，很少再套文件夹)
                            if (requestMap.containsKey(childId)) {
                                SavedRequest req = requestMap.get(childId);
                                folder.addChild(CollectionNode.createRequest(req.getName(), req));
                            }
                        }
                    }
                }
            }

            // 4. 构建 Collection 根目录内容 (根据 collection 的 order 数组)
            CollectionNode root = CollectionNode.createFolder(colName);
            JsonNode rootOrder = colNode.path("order");
            if (rootOrder.isArray()) {
                for (JsonNode idNode : rootOrder) {
                    String childId = idNode.asText();
                    if (folderMap.containsKey(childId)) {
                        // 是文件夹
                        root.addChild(folderMap.get(childId));
                    } else if (requestMap.containsKey(childId)) {
                        // 是根目录下的请求
                        SavedRequest req = requestMap.get(childId);
                        root.addChild(CollectionNode.createRequest(req.getName(), req));
                    }
                }
            }
            roots.add(root);
        }
        return roots;
    }

    private SavedRequest parseDumpRequest(JsonNode rNode) {
        SavedRequest req = new SavedRequest();
        req.setName(rNode.path("name").asText("Untitled"));
        req.setMethod(rNode.path("method").asText("GET"));
        req.setUrl(rNode.path("url").asText("")); // Dump 里的 URL 通常包含 {{base_url}}

        // Headers
        List<RestParam> headers = new ArrayList<>();
        JsonNode headersNode = rNode.get("headers");
        if (headersNode != null && headersNode.isArray()) { // Dump 里的 header 是数组对象
            for (JsonNode h : headersNode) {
                headers.add(new RestParam(h.path("key").asText(), h.path("value").asText(), RestParam.ParamType.HEADER, "String"));
            }
        } else { // 或者是字符串? 兼容旧版
            // 暂略，一般是数组
        }
        req.setHeaders(headers);

        // Body (Dump 里的 dataMode)
        String dataMode = rNode.path("dataMode").asText();
        if ("raw".equals(dataMode)) {
            req.setBodyType("raw (json)");
            req.setBodyContent(rNode.path("rawModeData").asText());
        } else if ("params".equals(dataMode) || "urlencoded".equals(dataMode)) {
            // 表单数据，我们需要把它转成 key=value 字符串存入 bodyContent
            req.setBodyType("x-www-form-urlencoded");
            StringBuilder sb = new StringBuilder();
            JsonNode dataNode = rNode.path("data");
            if (dataNode.isArray()) {
                for (JsonNode d : dataNode) {
                    if (sb.length() > 0) sb.append("&");
                    String k = d.path("key").asText();
                    String v = d.path("value").asText();
                    sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                            .append("=")
                            .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
                }
            }
            req.setBodyContent(sb.toString());
        }

        return req;
    }

    // --- Standard v2.1 Import Logic (旧逻辑保留) ---
    public List<CollectionNode> importCollection(File jsonFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(jsonFile);
        return parseStandardCollectionNodes(root);
    }

    private List<CollectionNode> parseStandardCollectionNodes(JsonNode root) {
        JsonNode itemsNode = root.get("item");
        if (itemsNode == null || !itemsNode.isArray()) {
            return Collections.emptyList();
        }
        List<CollectionNode> result = new ArrayList<>();
        for (JsonNode itemNode : itemsNode) {
            result.add(parseStandardItem(itemNode));
        }
        return result;
    }

    private CollectionNode parseStandardItem(JsonNode itemNode) {
        String name = itemNode.path("name").asText("Untitled");
        if (itemNode.has("item") && itemNode.get("item").isArray()) {
            CollectionNode folder = CollectionNode.createFolder(name);
            for (JsonNode childNode : itemNode.get("item")) {
                folder.addChild(parseStandardItem(childNode));
            }
            return folder;
        } else {
            SavedRequest request = parseStandardRequest(itemNode.path("request"), name);
            return CollectionNode.createRequest(name, request);
        }
    }

    private SavedRequest parseStandardRequest(JsonNode requestNode, String defaultName) {
        // ... (保持你原有的 Standard Request 解析逻辑不变) ...
        // 为了完整性，这里简写，请保留你原来的 parseRequest 代码
        SavedRequest req = new SavedRequest();
        req.setName(defaultName);
        req.setMethod(requestNode.path("method").asText("GET"));

        // URL
        JsonNode urlNode = requestNode.get("url");
        if (urlNode != null) {
            if (urlNode.isTextual()) req.setUrl(urlNode.asText());
            else if (urlNode.isObject()) req.setUrl(urlNode.path("raw").asText());
        }

        // Headers
        JsonNode hNode = requestNode.path("header");
        if(hNode.isArray()) {
            List<RestParam> list = new ArrayList<>();
            for(JsonNode h : hNode) list.add(new RestParam(h.path("key").asText(), h.path("value").asText(), RestParam.ParamType.HEADER, "String"));
            req.setHeaders(list);
        }

        // Body (Raw)
        JsonNode bNode = requestNode.path("body");
        if("raw".equals(bNode.path("mode").asText())) {
            req.setBodyContent(bNode.path("raw").asText());
        }

        return req;
    }

    // --- Helper: Environment Parsing ---
    private RestEnv parseEnv(JsonNode envNode) {
        String name = envNode.path("name").asText();
        if (name.isEmpty()) return null;

        RestEnv env = new RestEnv(name);
        Map<String, String> vars = new HashMap<>();

        JsonNode valuesNode = envNode.get("values");
        if (valuesNode != null && valuesNode.isArray()) {
            for (JsonNode v : valuesNode) {
                if (v.path("enabled").asBoolean(true)) {
                    String key = v.path("key").asText();
                    String value = v.path("value").asText();
                    if (!key.isEmpty()) {
                        vars.put(key, value);
                    }
                }
            }
        }
        env.setVariables(vars);
        return env;
    }
}