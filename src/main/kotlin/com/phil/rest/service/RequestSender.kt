package com.phil.rest.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.phil.rest.model.RestParam
import com.phil.rest.model.RestResponse
import com.phil.rest.model.SavedRequest
import java.util.*
import javax.swing.SwingUtilities

/**
 * 负责处理请求发送的业务逻辑：
 * 1. 变量替换
 * 2. Auth Header 生成
 * 3. 线程调度
 * 4. JSON 格式化 & 变量提取
 */
class RequestSender(private val project: Project) {

    private val objectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    fun sendRequest(
        requestData: SavedRequest,        // 包含 URL, Method, Headers, Body, Auth 等
        multipartParams: List<RestParam>?, // 独立传递 Multipart 参数
        onStart: () -> Unit,
        onFinish: (RestResponse) -> Unit
    ) {
        // 1. 变量解析 (Context Preparation)
        var finalUrl = resolveVariables(requestData.url)
        val method = requestData.method
        val finalBody = resolveVariables(requestData.bodyContent)

        // 2. 构建 Query Params
        val queryParamsBuilder = StringBuilder()
        requestData.params.forEach {
            val v = resolveVariables(it.value)
            if (queryParamsBuilder.isEmpty()) queryParamsBuilder.append("?") else queryParamsBuilder.append("&")
            queryParamsBuilder.append("${it.name}=$v")
        }

        // 3. 构建 Headers & Auth
        val headers = ArrayList<RestParam>()
        val headerStore = HeaderStore.getInstance(project)

        // 添加常规 Header
        requestData.headers.forEach {
            val v = resolveVariables(it.value)
            headers.add(RestParam(it.name, v, RestParam.ParamType.HEADER, "String"))
            headerStore.recordHeader(it.name) // 记录到自动补全
        }

        // 处理 Auth (逻辑从 UI 移到这里)
        val authContent = requestData.authContent
        when (requestData.authType) {
            "bearer" -> {
                val token = resolveVariables(authContent["token"])
                if (!token.isNullOrBlank()) headers.add(RestParam("Authorization", "Bearer $token", RestParam.ParamType.HEADER, "String"))
            }
            "basic" -> {
                val user = resolveVariables(authContent["username"])
                val pass = resolveVariables(authContent["password"])
                if (!user.isNullOrBlank() || !pass.isNullOrBlank()) {
                    val encoded = Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
                    headers.add(RestParam("Authorization", "Basic $encoded", RestParam.ParamType.HEADER, "String"))
                }
            }
            "apikey" -> {
                val key = resolveVariables(authContent["key"])
                val value = resolveVariables(authContent["value"])
                val where = authContent["where"] ?: "Header"
                if (!key.isNullOrBlank()) {
                    if (where == "Header") headers.add(RestParam(key, value, RestParam.ParamType.HEADER, "String"))
                    else {
                        if (queryParamsBuilder.isEmpty()) queryParamsBuilder.append("?") else queryParamsBuilder.append("&")
                        queryParamsBuilder.append("$key=$value")
                    }
                }
            }
        }

        // 拼接 URL Query
        if (!finalUrl.contains("?") && queryParamsBuilder.isNotEmpty()) finalUrl += queryParamsBuilder.toString()
        else if (finalUrl.contains("?") && queryParamsBuilder.isNotEmpty()) finalUrl += "&" + queryParamsBuilder.substring(1)

        // 自动补充 Content-Type
        val hasContentType = headers.any { it.name.equals("Content-Type", ignoreCase = true) }
        if (!hasContentType) {
            val bodyType = requestData.bodyType
            if (bodyType != "multipart/form-data") {
                when (bodyType) {
                    "x-www-form-urlencoded" -> headers.add(RestParam("Content-Type", "application/x-www-form-urlencoded", RestParam.ParamType.HEADER, "String"))
                    "raw (json)" -> headers.add(RestParam("Content-Type", "application/json", RestParam.ParamType.HEADER, "String"))
                    "raw (xml)" -> headers.add(RestParam("Content-Type", "application/xml", RestParam.ParamType.HEADER, "String"))
                    "raw (text)" -> headers.add(RestParam("Content-Type", "text/plain", RestParam.ParamType.HEADER, "String"))
                }
            }
        }

        // 4. 执行请求 (Async)
        SwingUtilities.invokeLater { onStart() }

        ApplicationManager.getApplication().executeOnPooledThread {
            val executor = HttpExecutor()
            // 执行网络请求
            val response = executor.execute(method, finalUrl, finalBody, headers, multipartParams)

            // 格式化 JSON Body (美化)
            val prettyBody = if (response.body.trim().startsWith("{") || response.body.trim().startsWith("[")) {
                formatJson(response.body)
            } else {
                response.body
            }

            // [修复] 这里传入 rawBody，解决了报错问题
            val finalResponse = RestResponse(
                response.statusCode,
                prettyBody,
                response.rawBody, // <--- 关键修复点
                response.headers,
                response.durationMs
            )

            // 5. 执行变量提取
            if (response.statusCode in 200..299) {
                JsonExtractor.executeExtraction(response.body, requestData.extractRules, project)
            }

            // 回调 UI
            SwingUtilities.invokeLater {
                onFinish(finalResponse)
            }
        }
    }

    private fun resolveVariables(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val selectedEnv = EnvService.getInstance(project).selectedEnv ?: return text
        var result = text
        for ((key, value) in selectedEnv.variables) {
            result = result?.replace("{{$key}}", value)
        }
        return result ?: ""
    }

    private fun formatJson(json: String): String {
        if (json.isBlank()) return ""
        return try {
            objectMapper.writeValueAsString(objectMapper.readTree(json))
        } catch (e: Exception) {
            json
        }
    }
}