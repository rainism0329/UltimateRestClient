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

            for (PsiMethod method : controllerClass.getMethods()) {
                ApiDefinition api = parseMethod(method, baseUrl, controllerClass.getName());
                if (api != null) {
                    apis.add(api);
                }
            }
        }
        return apis;
    }

    private ApiDefinition parseMethod(PsiMethod method, String baseUrl, String className) {
        String[][] mappings = {
                {"org.springframework.web.bind.annotation.GetMapping", "GET"},
                {"org.springframework.web.bind.annotation.PostMapping", "POST"},
                {"org.springframework.web.bind.annotation.PutMapping", "PUT"},
                {"org.springframework.web.bind.annotation.DeleteMapping", "DELETE"},
                {"org.springframework.web.bind.annotation.RequestMapping", "ALL"}
        };

        for (String[] mapping : mappings) {
            PsiAnnotation annotation = method.getAnnotation(mapping[0]);
            if (annotation != null) {
                String methodPath = extractValueFromAnnotation(annotation);
                String fullUrl = combinePaths(baseUrl, methodPath);

                ApiDefinition api = new ApiDefinition(mapping[1], fullUrl, className, method.getName());

                // *** 核心升级：解析参数 ***
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
            String paramType = parameter.getType().getPresentableText(); // e.g. "String", "int", "UserDTO"

            // 1. 处理 @RequestParam (Query Param)
            PsiAnnotation requestParam = parameter.getAnnotation("org.springframework.web.bind.annotation.RequestParam");
            if (requestParam != null) {
                String nameFromAnno = extractAttributeValue(requestParam, "value"); // @RequestParam("userId")
                if (nameFromAnno.isEmpty()) nameFromAnno = extractAttributeValue(requestParam, "name");

                String finalName = nameFromAnno.isEmpty() ? paramName : nameFromAnno;
                api.addParam(new RestParam(finalName, "", RestParam.ParamType.QUERY, paramType));
                continue;
            }

            // 2. 处理 @PathVariable (Path Param)
            PsiAnnotation pathVar = parameter.getAnnotation("org.springframework.web.bind.annotation.PathVariable");
            if (pathVar != null) {
                String nameFromAnno = extractAttributeValue(pathVar, "value");
                String finalName = nameFromAnno.isEmpty() ? paramName : nameFromAnno;
                api.addParam(new RestParam(finalName, "1", RestParam.ParamType.PATH, paramType)); // 默认给个 "1"
                continue;
            }

            // 3. 处理 @RequestBody (Body JSON)
            PsiAnnotation requestBody = parameter.getAnnotation("org.springframework.web.bind.annotation.RequestBody");
            if (requestBody != null) {
                // 暂时只标记为 BODY，后续我们再做高级的 DTO 转 JSON
                api.addParam(new RestParam("body", "{}", RestParam.ParamType.BODY, paramType));
                continue;
            }

            // 4. 默认情况 (没有注解的基本类型通常也是 Query Param)
            if (isSimpleType(paramType)) {
                api.addParam(new RestParam(paramName, "", RestParam.ParamType.QUERY, paramType));
            }
        }
    }

    private boolean isSimpleType(String type) {
        return type.equals("String") || type.equals("int") || type.equals("Integer")
                || type.equals("Long") || type.equals("boolean") || type.equals("Boolean");
    }

    // 辅助方法：提取注解属性值
    private String extractAttributeValue(PsiAnnotation annotation, String attribute) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue(attribute);
        if (value != null && !"".equals(value.getText())) {
            return value.getText().replace("\"", "");
        }
        return "";
    }

    // 原有的辅助方法...
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