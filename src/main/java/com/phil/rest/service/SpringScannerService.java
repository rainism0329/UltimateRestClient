package com.phil.rest.service;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.util.Query;
import com.phil.rest.model.ApiDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class SpringScannerService {

    private final Project project;

    public SpringScannerService(Project project) {
        this.project = project;
    }

    public List<ApiDefinition> scanCurrentProject() {
        List<ApiDefinition> apis = new ArrayList<>();

        // 1. 获取全局搜索范围 (整个项目)
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

        // 2. 查找 @RestController 注解的 PsiClass (IDEA 认为注解也是一种类)
        PsiClass restControllerAnnotation = JavaPsiFacade.getInstance(project)
                .findClass("org.springframework.web.bind.annotation.RestController", GlobalSearchScope.allScope(project));

        if (restControllerAnnotation == null) {
            // 如果项目中没有 Spring Web 依赖，可能找不到这个类
            return apis;
        }

        // 3. 搜索所有使用了 @RestController 的类
        Query<PsiClass> query = AnnotatedElementsSearch.searchPsiClasses(restControllerAnnotation, scope);

        for (PsiClass controllerClass : query) {
            // 4. 提取类级别的 Base URL (例如 @RequestMapping("/api/v1"))
            String baseUrl = extractPathFromAnnotation(controllerClass, "org.springframework.web.bind.annotation.RequestMapping");

            // 5. 遍历该类的所有方法
            for (PsiMethod method : controllerClass.getMethods()) {
                // 尝试解析各种映射注解
                ApiDefinition api = parseMethod(method, baseUrl, controllerClass.getName());
                if (api != null) {
                    apis.add(api);
                }
            }
        }
        return apis;
    }

    private ApiDefinition parseMethod(PsiMethod method, String baseUrl, String className) {
        // 映射注解与其对应的 HTTP 方法
        // 这里只是简单演示，后续可以用 Map 优化
        String[][] mappings = {
                {"org.springframework.web.bind.annotation.GetMapping", "GET"},
                {"org.springframework.web.bind.annotation.PostMapping", "POST"},
                {"org.springframework.web.bind.annotation.PutMapping", "PUT"},
                {"org.springframework.web.bind.annotation.DeleteMapping", "DELETE"},
                {"org.springframework.web.bind.annotation.RequestMapping", "ALL"} // 简化处理
        };

        for (String[] mapping : mappings) {
            String annotationName = mapping[0];
            String httpMethod = mapping[1];

            PsiAnnotation annotation = method.getAnnotation(annotationName);
            if (annotation != null) {
                // 提取方法上的路径
                String methodPath = extractValueFromAnnotation(annotation);
                // 拼接完整 URL
                String fullUrl = combinePaths(baseUrl, methodPath);

                return new ApiDefinition(httpMethod, fullUrl, className, method.getName());
            }
        }
        return null;
    }

    // 辅助方法：从注解中提取 value 或 path 属性
    private String extractPathFromAnnotation(PsiModifierListOwner owner, String annotationFqn) {
        PsiAnnotation annotation = owner.getAnnotation(annotationFqn);
        return extractValueFromAnnotation(annotation);
    }

    private String extractValueFromAnnotation(PsiAnnotation annotation) {
        if (annotation == null) return "";

        // 尝试获取 "value" 属性
        PsiAnnotationMemberValue valueAttr = annotation.findAttributeValue("value");
        if (valueAttr != null && !"".equals(valueAttr.getText())) {
            // getText() 会返回带引号的字符串 (例如 "/api")，需要去除引号
            return valueAttr.getText().replace("\"", "");
        }

        // 尝试获取 "path" 属性 (Spring 别名)
        PsiAnnotationMemberValue pathAttr = annotation.findAttributeValue("path");
        if (pathAttr != null) {
            return pathAttr.getText().replace("\"", "");
        }

        return "";
    }

    private String combinePaths(String base, String sub) {
        // 简单的路径拼接逻辑，处理斜杠
        if (base == null) base = "";
        if (sub == null) sub = "";

        if (!base.startsWith("/")) base = "/" + base;
        if (!sub.startsWith("/") && !sub.isEmpty()) sub = "/" + sub;

        return (base + sub).replace("//", "/");
    }
}