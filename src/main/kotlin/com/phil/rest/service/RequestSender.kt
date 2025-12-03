package com.phil.rest.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.phil.rest.model.RestParam
import com.phil.rest.model.RestResponse
import com.phil.rest.model.SavedRequest
import java.util.*
import java.util.concurrent.CompletableFuture
import javax.swing.SwingUtilities

/**
 * 负责处理请求发送的业务逻辑：
 * 1. 变量替换
 * 2. Auth Header 生成
 * 3. 异步线程调度 & 取消控制
 * 4. JSON 格式化 & 变量提取
 */
class RequestSender(private val project: Project) {

    private val objectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    // [新增] 持有当前运行的 Future，用于取消
    private var currentFuture: CompletableFuture<RestResponse>? = null

    // [新增] 取消当前请求
    fun cancelCurrentRequest() {
        currentFuture?.cancel(true)
        currentFuture = null
    }

    fun sendRequest(
        requestData: SavedRequest,        // 包含 URL, Method, Headers, Body, Auth 等
        multipartParams: List<RestParam>?, // 独立传递 Multipart 参数
        onStart: () -> Unit,
        onFinish: (RestResponse) -> Unit
    ) {
        // 1. 变量解析
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

        requestData.headers.forEach {
            val v = resolveVariables(it.value)
            headers.add(RestParam(it.name, v, RestParam.ParamType.HEADER, "String"))
            headerStore.recordHeader(it.name)
        }

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

        if (!finalUrl.contains("?") && queryParamsBuilder.isNotEmpty()) finalUrl += queryParamsBuilder.toString()
        else if (finalUrl.contains("?") && queryParamsBuilder.isNotEmpty()) finalUrl += "&" + queryParamsBuilder.substring(1)

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

        // 4. 执行请求
        SwingUtilities.invokeLater { onStart() }

        val executor = HttpExecutor()
        val timeout = 60L // 默认 60s 超时

        // 调用异步方法
        val future = executor.executeAsync(method, finalUrl, finalBody, headers, multipartParams, timeout)
        currentFuture = future

        future.whenComplete { response, _ ->
            // 注意：如果被 cancel，response 可能是 null
            val safeResponse = response ?: RestResponse(0, "Request Cancelled", ByteArray(0), emptyMap(), 0)

            // 后处理（JSON 美化、变量提取）放入后台线程，防止阻塞 UI
            ApplicationManager.getApplication().executeOnPooledThread {
                val prettyBody = if (safeResponse.body.trim().startsWith("{") || safeResponse.body.trim().startsWith("[")) {
                    formatJson(safeResponse.body)
                } else safeResponse.body

                val finalRes = RestResponse(
                    safeResponse.statusCode,
                    prettyBody,
                    safeResponse.rawBody,
                    safeResponse.headers,
                    safeResponse.durationMs
                )

                if (finalRes.statusCode in 200..299) {
                    JsonExtractor.executeExtraction(finalRes.body, requestData.extractRules, project)
                }

                SwingUtilities.invokeLater {
                    currentFuture = null // 清空引用
                    onFinish(finalRes)
                }
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