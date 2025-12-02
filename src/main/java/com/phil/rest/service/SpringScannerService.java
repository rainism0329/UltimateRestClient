package com.phil.rest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.util.PsiTypesUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.Query;
import com.phil.rest.model.ApiDefinition;
import com.phil.rest.model.RestParam;

import java.util.*;

public class SpringScannerService {

    private final Project project;
    // 引入 Jackson 用于最终生成漂亮的 JSON 字符串
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public SpringScannerService(Project project) {
        this.project = project;
    }

    // --- 公开 API ---

    public List<ApiDefinition> scanCurrentProject() {
        List<ApiDefinition> apis = new ArrayList<>();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

        PsiClass restController = JavaPsiFacade.getInstance(project)
                .findClass("org.springframework.web.bind.annotation.RestController", GlobalSearchScope.allScope(project));

        if (restController == null) return apis;

        Query<PsiClass> query = AnnotatedElementsSearch.searchPsiClasses(restController, scope);

        for (PsiClass controllerClass : query) {
            String baseUrl = extractPathFromAnnotation(controllerClass, "org.springframework.web.bind.annotation.RequestMapping");

            for (PsiMethod method : controllerClass.getMethods()) {
                ApiDefinition api = parseMethod(method, baseUrl, controllerClass.getName());
                if (api != null) {
                    apis.add(api);
                }
            }
        }
        return apis;
    }

    public ApiDefinition parseSingleMethod(PsiMethod method) {
        PsiClass controllerClass = method.getContainingClass();
        if (controllerClass == null) return null;

        String baseUrl = extractPathFromAnnotation(controllerClass, "org.springframework.web.bind.annotation.RequestMapping");
        return parseMethod(method, baseUrl, controllerClass.getName());
    }

    // --- 核心解析逻辑 ---

    private ApiDefinition parseMethod(PsiMethod method, String baseUrl, String className) {
        String[][] mappings = {
                {"org.springframework.web.bind.annotation.GetMapping", "GET"},
                {"org.springframework.web.bind.annotation.PostMapping", "POST"},
                {"org.springframework.web.bind.annotation.PutMapping", "PUT"},
                {"org.springframework.web.bind.annotation.DeleteMapping", "DELETE"},
                {"org.springframework.web.bind.annotation.RequestMapping", "ALL"},
                {"org.springframework.web.bind.annotation.PatchMapping", "PATCH"}
        };

        for (String[] mapping : mappings) {
            PsiAnnotation annotation = method.getAnnotation(mapping[0]);
            if (annotation != null) {
                String methodPath = extractValueFromAnnotation(annotation);
                String fullUrl = combinePaths(baseUrl, methodPath);

                ApiDefinition api = new ApiDefinition(mapping[1], fullUrl, className, method.getName());
                parseParameters(method, api);
                return api;
            }
        }
        return null;
    }

    private void parseParameters(PsiMethod method, ApiDefinition api) {
        PsiParameter[] parameters = method.getParameterList().getParameters();

        for (PsiParameter parameter : parameters) {
            String paramName = parameter.getName();
            String paramType = parameter.getType().getPresentableText();

            // 1. @RequestParam
            PsiAnnotation requestParam = parameter.getAnnotation("org.springframework.web.bind.annotation.RequestParam");
            if (requestParam != null) {
                String nameFromAnno = extractAttributeValue(requestParam, "value");
                if (nameFromAnno.isEmpty()) nameFromAnno = extractAttributeValue(requestParam, "name");
                String finalName = nameFromAnno.isEmpty() ? paramName : nameFromAnno;
                api.addParam(new RestParam(finalName, "", RestParam.ParamType.QUERY, paramType));
                continue;
            }

            // 2. @PathVariable
            PsiAnnotation pathVar = parameter.getAnnotation("org.springframework.web.bind.annotation.PathVariable");
            if (pathVar != null) {
                String nameFromAnno = extractAttributeValue(pathVar, "value");
                String finalName = nameFromAnno.isEmpty() ? paramName : nameFromAnno;
                api.addParam(new RestParam(finalName, "1", RestParam.ParamType.PATH, paramType));
                continue;
            }

            // 3. @RequestBody [核心升级]
            PsiAnnotation requestBody = parameter.getAnnotation("org.springframework.web.bind.annotation.RequestBody");
            if (requestBody != null) {
                // 此时不给空 JSON，而是解析类型生成 Mock 数据
                String jsonBody = generateJsonBody(parameter.getType());
                api.addParam(new RestParam("body", jsonBody, RestParam.ParamType.BODY, paramType));
                continue;
            }

            // 4. Simple Types (无注解参数，默认为 Query)
            if (isSimpleType(parameter.getType())) {
                api.addParam(new RestParam(paramName, "", RestParam.ParamType.QUERY, paramType));
            }
        }
    }

    // --- DTO 智能解析器 (Smart DTO Parser) ---

    private String generateJsonBody(PsiType type) {
        try {
            // 使用 Map 构建对象结构，最后转 JSON
            Object result = parseTypeRecursively(type, 0, new HashSet<>());
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{}"; // 降级处理
        }
    }

    /**
     * 递归解析 PsiType 为 Java 对象 (Map, List, String, Integer...)
     * @param type 当前类型
     * @param depth 递归深度 (防止死循环)
     * @param visited 这里的 visited 记录的是全限定类名，用于防止循环引用 (A -> B -> A)
     */
    private Object parseTypeRecursively(PsiType type, int depth, Set<String> visited) {
        if (depth > 5) return null; // 深度限制

        // 1. 基础类型与包装类
        if (isSimpleType(type)) {
            return getDefaultValueForSimpleType(type);
        }

        // 2. 数组类型 (e.g. String[], User[])
        if (type instanceof PsiArrayType) {
            PsiType componentType = ((PsiArrayType) type).getComponentType();
            return Collections.singletonList(parseTypeRecursively(componentType, depth + 1, visited));
        }

        // 3. 集合类型 (Collection, List, Set)
        PsiClass psiClass = PsiUtil.resolveClassInClassTypeOnly(type);
        if (psiClass != null && isCollection(psiClass)) {
            // 尝试获取泛型参数 List<User> -> User
            PsiType innerType = PsiUtil.extractIterableTypeParameter(type, false);
            if (innerType != null) {
                return Collections.singletonList(parseTypeRecursively(innerType, depth + 1, visited));
            }
            return Collections.emptyList();
        }

        // 4. Map 类型
        if (psiClass != null && isMap(psiClass)) {
            return Collections.singletonMap("key", "value");
        }

        // 5. 枚举类型
        if (psiClass != null && psiClass.isEnum()) {
            PsiField[] fields = psiClass.getFields();
            for (PsiField field : fields) {
                if (field instanceof PsiEnumConstant) {
                    return field.getName(); // 返回第一个枚举值
                }
            }
            return "";
        }

        // 6. 普通对象 (POJO / DTO)
        if (psiClass != null) {
            String qName = psiClass.getQualifiedName();
            if (qName != null && qName.startsWith("java.")) {
                return null; // 忽略 java.* 内部类 (如 Class, Object)
            }

            // 循环引用检测
            if (qName != null && visited.contains(qName)) {
                return null;
            }
            Set<String> newVisited = new HashSet<>(visited);
            if (qName != null) newVisited.add(qName);

            Map<String, Object> map = new LinkedHashMap<>();
            // 获取所有字段 (包含父类字段其实更复杂，这里暂只取当前类，或者用 getAllFields 但要注意性能)
            for (PsiField field : psiClass.getAllFields()) {
                if (field.hasModifierProperty(PsiModifier.STATIC) ||
                        field.hasModifierProperty(PsiModifier.FINAL) ||
                        field.hasModifierProperty(PsiModifier.TRANSIENT)) {
                    continue;
                }
                map.put(field.getName(), parseTypeRecursively(field.getType(), depth + 1, newVisited));
            }
            return map;
        }

        return new Object(); // fallback
    }

    private boolean isSimpleType(PsiType type) {
        String name = type.getPresentableText();
        return isSimpleTypeStr(name);
    }

    // 辅助方法：判断是否为集合
    private boolean isCollection(PsiClass psiClass) {
        return inheritanceCheck(psiClass, "java.util.Collection");
    }

    // 辅助方法：判断是否为 Map
    private boolean isMap(PsiClass psiClass) {
        return inheritanceCheck(psiClass, "java.util.Map");
    }

    private boolean inheritanceCheck(PsiClass psiClass, String targetFqn) {
        if (psiClass == null) return false;
        if (targetFqn.equals(psiClass.getQualifiedName())) return true;
        for (PsiClass sup : psiClass.getSupers()) {
            if (inheritanceCheck(sup, targetFqn)) return true;
        }
        return false;
    }

    private Object getDefaultValueForSimpleType(PsiType type) {
        String typeName = type.getPresentableText();
        switch (typeName.toLowerCase()) {
            case "int": case "integer": return 0;
            case "long": return 0L;
            case "double": case "float": case "bigdecimal": return 0.0;
            case "boolean": return true;
            case "string": return "";
            case "date": case "localdate": case "localdatetime": return "2025-01-01 12:00:00";
            default: return "";
        }
    }

    private boolean isSimpleTypeStr(String type) {
        // 包含常见的基础类型、包装类、String、Date
        return type.equals("String") || type.equals("int") || type.equals("Integer")
                || type.equals("long") || type.equals("Long")
                || type.equals("double") || type.equals("Double")
                || type.equals("float") || type.equals("Float")
                || type.equals("boolean") || type.equals("Boolean")
                || type.equals("BigDecimal")
                || type.equals("Date") || type.equals("LocalDate") || type.equals("LocalDateTime");
    }

    // --- Annotation Helpers (保持不变) ---

    private String extractAttributeValue(PsiAnnotation annotation, String attribute) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue(attribute);
        if (value != null && !"".equals(value.getText())) {
            return value.getText().replace("\"", "");
        }
        return "";
    }

    private String extractPathFromAnnotation(PsiModifierListOwner owner, String annotationFqn) {
        PsiAnnotation annotation = owner.getAnnotation(annotationFqn);
        return extractValueFromAnnotation(annotation);
    }

    private String extractValueFromAnnotation(PsiAnnotation annotation) {
        if (annotation == null) return "";
        PsiAnnotationMemberValue valueAttr = annotation.findAttributeValue("value");
        if (valueAttr != null && !"".equals(valueAttr.getText())) {
            return valueAttr.getText().replace("\"", "");
        }
        PsiAnnotationMemberValue pathAttr = annotation.findAttributeValue("path");
        if (pathAttr != null) {
            return pathAttr.getText().replace("\"", "");
        }
        return "";
    }

    private String combinePaths(String base, String sub) {
        if (base == null) base = "";
        if (sub == null) sub = "";
        if (!base.startsWith("/")) base = "/" + base;
        if (!sub.startsWith("/") && !sub.isEmpty()) sub = "/" + sub;
        return (base + sub).replace("//", "/");
    }
}