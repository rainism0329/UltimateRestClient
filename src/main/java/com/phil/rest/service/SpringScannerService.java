package com.phil.rest.service;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.util.Query;
import com.phil.rest.model.ApiDefinition;
import com.phil.rest.model.RestParam;

import java.util.ArrayList;
import java.util.List;

public class SpringScannerService {

    private final Project project;

    public SpringScannerService(Project project) {
        this.project = project;
    }

    // 原有的扫描全项目方法
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

    // *** 新增：给 LineMarker 调用的单方法解析 ***
    public ApiDefinition parseSingleMethod(PsiMethod method) {
        PsiClass controllerClass = method.getContainingClass();
        if (controllerClass == null) return null;

        // 检查类上是否有 RestController 或 Controller 注解 (可选，视需求而定)
        // 这里简单处理：只要方法上有 Mapping 注解就尝试解析

        String baseUrl = extractPathFromAnnotation(controllerClass, "org.springframework.web.bind.annotation.RequestMapping");
        return parseMethod(method, baseUrl, controllerClass.getName());
    }

    // 内部复用逻辑
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
                parseParameters(method, api); // 复用之前的参数解析逻辑
                return api;
            }
        }
        return null;
    }

    // ... (parseParameters, isSimpleType, extractPathFromAnnotation 等辅助方法保持不变，请保留原有的) ...
    // 为了节省篇幅，这里假设你保留了之前的 parseParameters 等私有方法。
    // 如果没有，请把上次给你的代码里对应的 private 方法贴回来。

    private void parseParameters(PsiMethod method, ApiDefinition api) {
        PsiParameter[] parameters = method.getParameterList().getParameters();

        for (PsiParameter parameter : parameters) {
            String paramName = parameter.getName();
            String paramType = parameter.getType().getPresentableText();

            PsiAnnotation requestParam = parameter.getAnnotation("org.springframework.web.bind.annotation.RequestParam");
            if (requestParam != null) {
                String nameFromAnno = extractAttributeValue(requestParam, "value");
                if (nameFromAnno.isEmpty()) nameFromAnno = extractAttributeValue(requestParam, "name");
                String finalName = nameFromAnno.isEmpty() ? paramName : nameFromAnno;
                api.addParam(new RestParam(finalName, "", RestParam.ParamType.QUERY, paramType));
                continue;
            }

            PsiAnnotation pathVar = parameter.getAnnotation("org.springframework.web.bind.annotation.PathVariable");
            if (pathVar != null) {
                String nameFromAnno = extractAttributeValue(pathVar, "value");
                String finalName = nameFromAnno.isEmpty() ? paramName : nameFromAnno;
                api.addParam(new RestParam(finalName, "1", RestParam.ParamType.PATH, paramType));
                continue;
            }

            PsiAnnotation requestBody = parameter.getAnnotation("org.springframework.web.bind.annotation.RequestBody");
            if (requestBody != null) {
                api.addParam(new RestParam("body", "{}", RestParam.ParamType.BODY, paramType));
                continue;
            }

            if (isSimpleType(paramType)) {
                api.addParam(new RestParam(paramName, "", RestParam.ParamType.QUERY, paramType));
            }
        }
    }

    private boolean isSimpleType(String type) {
        return type.equals("String") || type.equals("int") || type.equals("Integer")
                || type.equals("Long") || type.equals("boolean") || type.equals("Boolean");
    }

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