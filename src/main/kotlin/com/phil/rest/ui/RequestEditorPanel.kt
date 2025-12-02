package com.phil.rest.ui

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer // 引入 Disposer
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

// [修改] 实现 Disposable
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
        // [重要] 将 responsePanel 注册为当前面板的子 Disposable
        // 这样当 RequestEditorPanel 被销毁时，responsePanel.dispose() 会自动被调用
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
        // 让分割线更明显一点，避免“格格不入”
        splitter.dividerWidth = 2

        mainContent.add(addressWrapper, BorderLayout.NORTH)
        mainContent.add(splitter, BorderLayout.CENTER)

        setContent(mainContent)
    }

    // [重要] 必须重写 dispose 方法，虽然 SimpleToolWindowPanel 甚至不需要显式重写，
    // 但作为良好实践，声明在这里表示我们知道它会被调用。
    override fun dispose() {
        // 默认实现会处理 Disposer.register 的子组件
    }

    // --- 下面的业务逻辑保持不变 (复制之前的即可) ---
    // 为节省篇幅，这里省略 renderApi, sendRequest 等具体实现
    // 请直接复用上一轮回复中 RequestEditorPanel 的 sendRequest, renderApi 等方法
    // 逻辑完全一样，不需要修改。

    // ... copy rest of the methods ...

    private fun createTopToolbar(): ActionToolbar {
        val actionGroup = DefaultActionGroup()
        actionGroup.add(EnvironmentComboAction(project) {})
        actionGroup.addSeparator()
        actionGroup.add(object : DumbAwareAction("Save", "Save current request", AllIcons.Actions.MenuSaveall) {
            override fun actionPerformed(e: AnActionEvent) {
                if (activeCollectionNode != null) updateExistingRequest() else createNewRequestFlow()
            }
        })
        actionGroup.add(object : DumbAwareAction("Save As...", "Save as new request", AllIcons.Actions.MenuPaste) {
            override fun actionPerformed(e: AnActionEvent) { createNewRequestFlow() }
        })
        actionGroup.addSeparator()
        actionGroup.add(object : DumbAwareAction("New Request", "Clear and create new", AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) { createNewEmptyRequest() }
        })
        return ActionManager.getInstance().createActionToolbar("RestClientTopToolbar", actionGroup, true)
    }

    fun createNewEmptyRequest() {
        activeCollectionNode = null
        addressBar.method = "GET"
        addressBar.url = ""
        inputPanel.clearAll()
        responsePanel.clear()
    }

    // 复用之前的 renderApi, renderSavedRequest, updateExistingRequest, createNewRequestFlow
    // 复用之前的 sendRequest, resolveVariables, formatJson
    // 注意：sendRequest 里调用 responsePanel.updateResponse(finalResponse)

    // 为了完整性，这里贴一下最关键的 sendRequest
    private fun sendRequest() {
        var finalUrl = resolveVariables(addressBar.url)
        val method = addressBar.method
        val finalBody = resolveVariables(inputPanel.getBody())

        val params = inputPanel.getQueryParams()
        val queryParamsBuilder = StringBuilder()
        params.forEach {
            val v = resolveVariables(it.value)
            if (queryParamsBuilder.isEmpty()) queryParamsBuilder.append("?") else queryParamsBuilder.append("&")
            queryParamsBuilder.append("${it.name}=$v")
        }

        if (!finalUrl.contains("?") && queryParamsBuilder.isNotEmpty()) finalUrl += queryParamsBuilder.toString()
        else if (finalUrl.contains("?") && queryParamsBuilder.isNotEmpty()) finalUrl += "&" + queryParamsBuilder.substring(1)

        val headers = ArrayList<RestParam>()
        val headerStore = HeaderStore.getInstance(project)

        inputPanel.getHeaders().forEach {
            val v = resolveVariables(it.value)
            headers.add(RestParam(it.name, v, RestParam.ParamType.HEADER, "String"))
            headerStore.recordHeader(it.name)
        }

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
        }

        addressBar.isBusy = true
        responsePanel.clear()

        ApplicationManager.getApplication().executeOnPooledThread {
            val executor = HttpExecutor()
            val response = executor.execute(method, finalUrl, finalBody, headers)
            val prettyBody = formatJson(response.body)
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
    // ... 补充其他缺失的方法 (renderApi 等) 与上一次回答一致 ...
    // [补充] 必须补充上 renderApi 等方法，否则会编译错误。
    // 请参考上一轮回答的 RequestEditorPanel 代码将剩余方法补全。
    // 关键点在于 init 里的 Disposer.register(this, responsePanel)

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
        inputPanel.loadRequestData(params, headers, body, "noauth", mapOf())
        if (api.method.uppercase() in listOf("POST", "PUT")) inputPanel.selectedIndex = 3 else inputPanel.selectedIndex = 0
        responsePanel.clear()
    }

    fun renderSavedRequest(node: CollectionNode) {
        activeCollectionNode = node
        val req = node.request ?: return
        addressBar.method = req.method.uppercase()
        addressBar.url = req.url
        inputPanel.loadRequestData(req.params, req.headers, req.bodyContent, req.authType, req.authContent)
        responsePanel.clear()
    }

    private fun collectData(targetReq: SavedRequest) {
        targetReq.method = addressBar.method
        targetReq.url = addressBar.url
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
}