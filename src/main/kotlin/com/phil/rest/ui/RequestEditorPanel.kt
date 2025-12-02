package com.phil.rest.ui

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBSplitter
import com.intellij.util.ui.JBUI
import com.phil.rest.model.*
import com.phil.rest.service.CollectionService
import com.phil.rest.service.EnvService
import com.phil.rest.service.HeaderStore
import com.phil.rest.service.HttpExecutor
import com.phil.rest.ui.action.EnvironmentComboAction
import com.phil.rest.ui.component.GeekAddressBar
import java.awt.BorderLayout
import java.util.*
import javax.swing.JPanel
import javax.swing.SwingUtilities

class RequestEditorPanel(
    private val project: Project,
    private val onSaveSuccess: () -> Unit
) : SimpleToolWindowPanel(true, true), Disposable {

    private var activeCollectionNode: CollectionNode? = null
    private val objectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    private val addressBar = GeekAddressBar(project) { sendRequest() }
    private val inputPanel = RequestInputPanel(project)
    private val responsePanel = ResponsePanel(project)

    init {
        Disposer.register(this, responsePanel)

        // 1. Toolbar
        val toolbar = createTopToolbar()
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        // 2. Layout
        val mainContent = JPanel(BorderLayout())

        val addressWrapper = JPanel(BorderLayout())
        addressWrapper.border = JBUI.Borders.empty(10, 10, 5, 10)
        addressWrapper.add(addressBar, BorderLayout.CENTER)

        val splitter = JBSplitter(true, 0.5f)
        splitter.firstComponent = inputPanel
        splitter.secondComponent = responsePanel
        splitter.dividerWidth = 2

        mainContent.add(addressWrapper, BorderLayout.NORTH)
        mainContent.add(splitter, BorderLayout.CENTER)

        setContent(mainContent)

        // [Shortcut] 注册 Ctrl+Enter
        val sendAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) { sendRequest() }
        }
        sendAction.registerCustomShortcutSet(CommonShortcuts.getCtrlEnter(), this)
    }

    override fun dispose() {}

    private fun createTopToolbar(): ActionToolbar {
        val actionGroup = DefaultActionGroup()
        actionGroup.add(EnvironmentComboAction(project) {})
        actionGroup.addSeparator()

        val saveAction = object : DumbAwareAction("Save", "Save current request", AllIcons.Actions.MenuSaveall) {
            override fun actionPerformed(e: AnActionEvent) {
                if (activeCollectionNode != null) updateExistingRequest() else createNewRequestFlow()
            }
        }
        val saveAllAction = ActionManager.getInstance().getAction("SaveAll")
        if (saveAllAction != null) {
            saveAction.registerCustomShortcutSet(saveAllAction.shortcutSet, this)
        }
        actionGroup.add(saveAction)

        actionGroup.add(object : DumbAwareAction("Save As...", "Save as new request", AllIcons.Actions.MenuPaste) {
            override fun actionPerformed(e: AnActionEvent) { createNewRequestFlow() }
        })
        actionGroup.addSeparator()
        actionGroup.add(object : DumbAwareAction("New Request", "Clear and create new", AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) { createNewEmptyRequest() }
        })
        return ActionManager.getInstance().createActionToolbar("RestClientTopToolbar", actionGroup, true)
    }

    // --- 业务逻辑 ---

    fun createNewEmptyRequest() {
        activeCollectionNode = null
        addressBar.method = "GET"
        addressBar.url = ""
        inputPanel.clearAll()
        responsePanel.clear()
    }

    fun renderApi(api: ApiDefinition) {
        activeCollectionNode = null
        addressBar.method = api.method.uppercase()
        var url = "http://localhost:8080" + api.url

        val params = ArrayList<RestParam>()
        val headers = ArrayList<RestParam>()
        var body = ""

        api.params.forEach { param ->
            when (param.type) {
                RestParam.ParamType.PATH -> url = url.replace("{${param.name}}", param.value)
                RestParam.ParamType.QUERY -> params.add(param)
                RestParam.ParamType.HEADER -> headers.add(param)
                RestParam.ParamType.BODY -> body = param.value.ifEmpty { "{}" }
            }
        }
        addressBar.url = url
        // 默认类型 raw (json)
        inputPanel.setBodyType("raw (json)")
        inputPanel.loadRequestData(params, headers, body, "noauth", mapOf())
        if (api.method.uppercase() in listOf("POST", "PUT")) inputPanel.selectedIndex = 3 else inputPanel.selectedIndex = 0
        responsePanel.clear()
    }

    fun renderSavedRequest(node: CollectionNode) {
        activeCollectionNode = node
        val req = node.request ?: return
        addressBar.method = req.method.uppercase()
        addressBar.url = req.url

        // [新增] 恢复 Body Type (如果 SavedRequest 有这个字段最好，没有则根据 bodyType 属性或者默认)
        // 假设 SavedRequest 有 bodyType 属性 (你上传的代码里有: @Attribute("bodyType") private String bodyType;)
        // 如果为空，默认为 raw (json)
        val bType = if (req.bodyType.isNullOrBlank()) "raw (json)" else req.bodyType
        inputPanel.setBodyType(bType)

        inputPanel.loadRequestData(req.params, req.headers, req.bodyContent, req.authType, req.authContent)
        responsePanel.clear()
    }

    private fun collectData(targetReq: SavedRequest) {
        targetReq.method = addressBar.method
        targetReq.url = addressBar.url

        // [新增] 保存 Body 类型
        targetReq.bodyType = inputPanel.getBodyType()
        targetReq.bodyContent = inputPanel.getBody()

        targetReq.params = inputPanel.getQueryParams()
        targetReq.headers = inputPanel.getHeaders()
        val (authType, authContent) = inputPanel.getAuthData()
        targetReq.authType = authType
        targetReq.authContent = authContent
    }

    private fun updateExistingRequest() {
        val node = activeCollectionNode ?: return
        val req = node.request ?: SavedRequest()
        collectData(req)
        node.request = req
        onSaveSuccess()
    }

    private fun createNewRequestFlow() {
        val dialog = SaveRequestDialog(project, "New Request")
        if (dialog.showAndGet()) {
            val savedRequest = SavedRequest()
            savedRequest.name = dialog.requestName
            collectData(savedRequest)
            val targetFolder = dialog.selectedFolder ?: CollectionService.getInstance(project).getOrCreateDefaultRoot()
            val uniqueName = getUniqueName(targetFolder, savedRequest.name)
            savedRequest.name = uniqueName
            val newNode = CollectionNode.createRequest(uniqueName, savedRequest)
            targetFolder.addChild(newNode)
            activeCollectionNode = newNode
            onSaveSuccess()
        }
    }

    private fun getUniqueName(folder: CollectionNode, baseName: String): String {
        var uniqueName = baseName
        var counter = 1
        val existingNames = folder.children.map { it.name }.toSet()
        while (existingNames.contains(uniqueName)) { uniqueName = "$baseName ($counter)"; counter++ }
        return uniqueName
    }

    private fun sendRequest() {
        if (addressBar.isBusy) return

        var finalUrl = resolveVariables(addressBar.url)
        val method = addressBar.method
        val finalBody = resolveVariables(inputPanel.getBody())
        val bodyType = inputPanel.getBodyType()

        val params = inputPanel.getQueryParams()
        val queryParamsBuilder = StringBuilder()
        params.forEach {
            val v = resolveVariables(it.value)
            if (queryParamsBuilder.isEmpty()) queryParamsBuilder.append("?") else queryParamsBuilder.append("&")
            queryParamsBuilder.append("${it.name}=$v")
        }

        val headers = ArrayList<RestParam>()
        val headerStore = HeaderStore.getInstance(project)

        inputPanel.getHeaders().forEach {
            val v = resolveVariables(it.value)
            headers.add(RestParam(it.name, v, RestParam.ParamType.HEADER, "String"))
            headerStore.recordHeader(it.name)
        }

        // --- [新增] Auth 逻辑处理 ---
        val (authType, authData) = inputPanel.getAuthData()
        if (authType == "bearer") {
            val token = resolveVariables(authData["token"])
            if (!token.isNullOrBlank()) headers.add(RestParam("Authorization", "Bearer $token", RestParam.ParamType.HEADER, "String"))
        } else if (authType == "basic") {
            val user = resolveVariables(authData["username"])
            val pass = resolveVariables(authData["password"])
            if (!user.isNullOrBlank() || !pass.isNullOrBlank()) {
                val encoded = Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
                headers.add(RestParam("Authorization", "Basic $encoded", RestParam.ParamType.HEADER, "String"))
            }
        } else if (authType == "apikey") {
            // [API Key] 支持 Header 和 Query
            val key = resolveVariables(authData["key"])
            val value = resolveVariables(authData["value"])
            val where = authData["where"] ?: "Header"

            if (!key.isNullOrBlank()) {
                if (where == "Header") {
                    headers.add(RestParam(key, value, RestParam.ParamType.HEADER, "String"))
                } else {
                    // 添加到 Query Params
                    if (queryParamsBuilder.isEmpty()) queryParamsBuilder.append("?") else queryParamsBuilder.append("&")
                    queryParamsBuilder.append("$key=$value")
                }
            }
        }

        // 拼接 URL
        if (!finalUrl.contains("?") && queryParamsBuilder.isNotEmpty()) finalUrl += queryParamsBuilder.toString()
        else if (finalUrl.contains("?") && queryParamsBuilder.isNotEmpty()) finalUrl += "&" + queryParamsBuilder.substring(1)

        // --- [新增] Content-Type 自动补全 ---
        val hasContentType = headers.any { it.name.equals("Content-Type", ignoreCase = true) }
        if (!hasContentType) {
            when (bodyType) {
                "x-www-form-urlencoded" -> headers.add(RestParam("Content-Type", "application/x-www-form-urlencoded", RestParam.ParamType.HEADER, "String"))
                "raw (json)" -> headers.add(RestParam("Content-Type", "application/json", RestParam.ParamType.HEADER, "String"))
                "raw (xml)" -> headers.add(RestParam("Content-Type", "application/xml", RestParam.ParamType.HEADER, "String"))
                "raw (text)" -> headers.add(RestParam("Content-Type", "text/plain", RestParam.ParamType.HEADER, "String"))
            }
        }

        addressBar.isBusy = true
        responsePanel.clear()

        ApplicationManager.getApplication().executeOnPooledThread {
            val executor = HttpExecutor()
            val response = executor.execute(method, finalUrl, finalBody, headers)

            // 只有 JSON 尝试格式化，XML/HTML 暂不处理
            val prettyBody = if (response.body.trim().startsWith("{") || response.body.trim().startsWith("[")) {
                formatJson(response.body)
            } else {
                response.body
            }

            val finalResponse = RestResponse(response.statusCode, prettyBody, response.headers, response.durationMs)

            SwingUtilities.invokeLater {
                addressBar.isBusy = false
                responsePanel.updateResponse(finalResponse)
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