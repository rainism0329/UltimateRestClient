package com.phil.rest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.Query;
import com.phil.rest.model.ApiDefinition;
import com.phil.rest.model.RestParam;

import java.util.*;

public class SpringScannerService {

    private final Project project;
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public SpringScannerService(Project project) {
        this.project = project;
    }

    public List<ApiDefinition> scanCurrentProject() {
        List<ApiDefinition> apis = new ArrayList<>();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

        // 查找所有 @RestController
        PsiClass restController = JavaPsiFacade.getInstance(project)
                .findClass("org.springframework.web.bind.annotation.RestController", GlobalSearchScope.allScope(project));

        if (restController == null) return apis;

        Query<PsiClass> query = AnnotatedElementsSearch.searchPsiClasses(restController, scope);

        for (PsiClass controllerClass : query) {
            String baseUrl = extractPathFromAnnotation(controllerClass, "org.springframework.web.bind.annotation.RequestMapping");

            // [新增] 获取当前 Controller 所属的 Module
            Module module = ModuleUtil.findModuleForPsiElement(controllerClass);
            String moduleName = (module != null) ? module.getName() : "Main";

            for (PsiMethod method : controllerClass.getMethods()) {
                String className = controllerClass.getQualifiedName();
                if (className == null) className = controllerClass.getName();

                // 传入 moduleName，scan 阶段默认 resolveBody = true (或者 false 取决于你需要多快，建议 false，点击时再解析 body)
                // 这里为了缓存完整性，可以设为 true，但为了速度建议设为 true 并优化解析逻辑，或者设为 false
                // 根据之前的 LineMarker 逻辑，这里是全量扫描，为了性能，Body 可以先给 "{}"，点击时再生成
                // 但为了缓存能直接用，这里还是设为 true 比较好，只要 DTO 解析不递归太深
                ApiDefinition api = parseMethod(method, baseUrl, className, moduleName, true);
                if (api != null) {
                    apis.add(api);
                }
            }
        }
        return apis;
    }

    /**
     * 解析单个方法 (用于 LineMarker 跳转)
     */
    public ApiDefinition parseSingleMethod(PsiMethod method, boolean resolveBody) {
        PsiClass controllerClass = method.getContainingClass();
        if (controllerClass == null) return null;

        String baseUrl = extractPathFromAnnotation(controllerClass, "org.springframework.web.bind.annotation.RequestMapping");
        String className = controllerClass.getQualifiedName();
        if (className == null) className = controllerClass.getName();

        // [新增] 获取 Module
        Module module = ModuleUtil.findModuleForPsiElement(controllerClass);
        String moduleName = (module != null) ? module.getName() : "Main";

        return parseMethod(method, baseUrl, className, moduleName, resolveBody);
    }

    // --- 核心解析逻辑 ---

    private ApiDefinition parseMethod(PsiMethod method, String baseUrl, String className, String moduleName, boolean resolveBody) {
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

                // [修改] 构造函数传入 moduleName
                ApiDefinition api = new ApiDefinition(mapping[1], fullUrl, className, method.getName(), moduleName);
                parseParameters(method, api, resolveBody);
                return api;
            }
        }
        return null;
    }

    private void parseParameters(PsiMethod method, ApiDefinition api, boolean resolveBody) {
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

            // 3. @RequestBody
            PsiAnnotation requestBody = parameter.getAnnotation("org.springframework.web.bind.annotation.RequestBody");
            if (requestBody != null) {
                String jsonBody = "{}";
                if (resolveBody) {
                    jsonBody = generateJsonBody(parameter.getType());
                }
                api.addParam(new RestParam("body", jsonBody, RestParam.ParamType.BODY, paramType));
                continue;
            }

            // 4. Simple Types (默认作为 Query Param)
            if (isSimpleType(parameter.getType())) {
                api.addParam(new RestParam(paramName, "", RestParam.ParamType.QUERY, paramType));
            }
        }
    }

    // --- DTO 智能解析器 ---

    private String generateJsonBody(PsiType type) {
        try {
            Object result = parseTypeRecursively(type, 0, new HashSet<>());
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Object parseTypeRecursively(PsiType type, int depth, Set<String> visited) {
        if (depth > 5) return null; // 防止无限递归
        if (isSimpleType(type)) return getDefaultValueForSimpleType(type);

        if (type instanceof PsiArrayType) {
            PsiType componentType = ((PsiArrayType) type).getComponentType();
            return Collections.singletonList(parseTypeRecursively(componentType, depth + 1, visited));
        }

        PsiClass psiClass = PsiUtil.resolveClassInClassTypeOnly(type);
        if (psiClass != null && isCollection(psiClass)) {
            PsiType innerType = PsiUtil.extractIterableTypeParameter(type, false);
            if (innerType != null) {
                return Collections.singletonList(parseTypeRecursively(innerType, depth + 1, visited));
            }
            return Collections.emptyList();
        }

        if (psiClass != null && isMap(psiClass)) {
            return Collections.singletonMap("key", "value");
        }

        if (psiClass != null && psiClass.isEnum()) {
            PsiField[] fields = psiClass.getFields();
            for (PsiField field : fields) {
                if (field instanceof PsiEnumConstant) return field.getName();
            }
            return "";
        }

        if (psiClass != null) {
            String qName = psiClass.getQualifiedName();
            if (qName != null && qName.startsWith("java.")) return null;
            if (qName != null && visited.contains(qName)) return null; // 循环引用检查

            Set<String> newVisited = new HashSet<>(visited);
            if (qName != null) newVisited.add(qName);

            Map<String, Object> map = new LinkedHashMap<>();
            for (PsiField field : psiClass.getAllFields()) {
                if (field.hasModifierProperty(PsiModifier.STATIC) ||
                        field.hasModifierProperty(PsiModifier.FINAL) ||
                        field.hasModifierProperty(PsiModifier.TRANSIENT)) continue;
                map.put(field.getName(), parseTypeRecursively(field.getType(), depth + 1, newVisited));
            }
            return map;
        }
        return new Object();
    }

    private boolean isSimpleType(PsiType type) { return isSimpleTypeStr(type.getPresentableText()); }
    private boolean isCollection(PsiClass psiClass) { return inheritanceCheck(psiClass, "java.util.Collection"); }
    private boolean isMap(PsiClass psiClass) { return inheritanceCheck(psiClass, "java.util.Map"); }
    private boolean inheritanceCheck(PsiClass psiClass, String targetFqn) {
        if (psiClass == null) return false;
        if (targetFqn.equals(psiClass.getQualifiedName())) return true;
        for (PsiClass sup : psiClass.getSupers()) if (inheritanceCheck(sup, targetFqn)) return true;
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
        return type.equals("String") || type.equals("int") || type.equals("Integer") ||
                type.equals("long") || type.equals("Long") || type.equals("double") ||
                type.equals("Double") || type.equals("float") || type.equals("Float") ||
                type.equals("boolean") || type.equals("Boolean") || type.equals("BigDecimal") ||
                type.equals("Date") || type.equals("LocalDate") || type.equals("LocalDateTime");
    }

    private String extractAttributeValue(PsiAnnotation annotation, String attribute) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue(attribute);
        if (value != null && !"".equals(value.getText())) return value.getText().replace("\"", "");
        return "";
    }

    private String extractPathFromAnnotation(PsiModifierListOwner owner, String annotationFqn) {
        PsiAnnotation annotation = owner.getAnnotation(annotationFqn);
        return extractValueFromAnnotation(annotation);
    }

    private String extractValueFromAnnotation(PsiAnnotation annotation) {
        if (annotation == null) return "";
        PsiAnnotationMemberValue valueAttr = annotation.findAttributeValue("value");
        if (valueAttr != null && !"".equals(valueAttr.getText())) return valueAttr.getText().replace("\"", "");
        PsiAnnotationMemberValue pathAttr = annotation.findAttributeValue("path");
        if (pathAttr != null) return pathAttr.getText().replace("\"", "");
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