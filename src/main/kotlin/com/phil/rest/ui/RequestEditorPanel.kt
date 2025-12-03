package com.phil.rest.ui

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import com.phil.rest.model.*
import com.phil.rest.service.*
import com.phil.rest.ui.action.EnvironmentComboAction
import com.phil.rest.ui.component.GeekAddressBar
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import javax.swing.JPanel

class RequestEditorPanel(
    private val project: Project,
    private val onSaveSuccess: () -> Unit
) : SimpleToolWindowPanel(true, true), Disposable {

    private var activeCollectionNode: CollectionNode? = null

    // [UI组件]
    private val addressBar = GeekAddressBar(project, { sendRequest() }, { curlText -> importCurl(curlText) })
    private val inputPanel = RequestInputPanel(project)
    private val responsePanel = ResponsePanel(project)

    // [服务]
    private val requestSender = RequestSender(project) // 使用新的 Sender

    init {
        Disposer.register(this, responsePanel)

        val toolbar = createTopToolbar()
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

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

        // 快捷键支持 (Ctrl+Enter 发送)
        val sendAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) { sendRequest() }
        }
        sendAction.registerCustomShortcutSet(CommonShortcuts.getCtrlEnter(), this)
    }

    override fun dispose() {}

    /**
     * 发送请求的核心方法 (已重构，调用 RequestSender)
     */
    private fun sendRequest() {
        if (addressBar.isBusy) return

        // 1. 收集 UI 数据到临时对象
        val tempRequest = SavedRequest()
        collectData(tempRequest)
        val multipartParams = inputPanel.getMultipartParams()

        // 2. 委托 RequestSender 处理业务逻辑
        requestSender.sendRequest(
            requestData = tempRequest,
            multipartParams = multipartParams,
            onStart = {
                addressBar.isBusy = true
                responsePanel.clear()
            },
            onFinish = { response ->
                addressBar.isBusy = false
                responsePanel.updateResponse(response)

                // 简单的提示
                if (response.statusCode in 200..299 && tempRequest.extractRules.isNotEmpty()) {
                    JBPopupFactory.getInstance().createHtmlTextBalloonBuilder("Variables Extracted!", MessageType.INFO, null)
                        .setFadeoutTime(2000).createBalloon().show(RelativePoint.getCenterOf(addressBar), Balloon.Position.below)
                }
            }
        )
    }

    // --- 以下是 UI 交互与工具栏逻辑 (保持精简) ---

    private fun createTopToolbar(): ActionToolbar {
        val actionGroup = DefaultActionGroup()
        actionGroup.add(EnvironmentComboAction(project) {})

        // 环境变量透视眼
        actionGroup.add(object : DumbAwareAction("View Variables", "Peek active environment variables", AllIcons.General.InspectionsEye) {
            override fun actionPerformed(e: AnActionEvent) {
                val component = e.inputEvent?.component as? JComponent ?: return
                val env = EnvService.getInstance(project).selectedEnv
                if (env == null || env.variables.isEmpty()) {
                    showBalloon("No active variables", MessageType.WARNING, component)
                    return
                }
                val html = buildString {
                    append("<html><table border='0' cellspacing='4'>")
                    env.variables.forEach { (k, v) ->
                        val displayValue = if (v.length > 60) v.substring(0, 60) + "..." else v
                        append("<tr><td><b><span style='color:#9876AA'>$k</span></b></td><td>&nbsp;=&nbsp;</td><td><span style='color:#6A8759'>$displayValue</span></td></tr>")
                    }
                    append("</table></html>")
                }
                JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(html, null, JBColor.PanelBackground, null)
                    .setShadow(true).setHideOnClickOutside(true).createBalloon()
                    .show(RelativePoint.getSouthOf(component), Balloon.Position.below)
            }
        })

        actionGroup.add(object : DumbAwareAction("Clear Cookies", "Clear all session cookies", AllIcons.Actions.GC) {
            override fun actionPerformed(e: AnActionEvent) {
                HttpExecutor.clearCookies()
                showBalloon("Cookies Cleared!", MessageType.INFO, addressBar)
            }
        })
        actionGroup.addSeparator()
        actionGroup.add(object : DumbAwareAction("Import cURL", "Paste cURL command to import", AllIcons.Actions.Upload) {
            override fun actionPerformed(e: AnActionEvent) {
                val curlText = Messages.showMultilineInputDialog(project, "Paste your cURL command here:", "Import cURL", null, Messages.getQuestionIcon(), null)
                if (!curlText.isNullOrBlank()) {
                    if (!importCurl(curlText)) Messages.showErrorDialog("Invalid cURL command format.", "Import Failed")
                }
            }
        })
        actionGroup.add(object : DumbAwareAction("Copy as cURL", "Copy request as cURL command", AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) { copyAsCurl() }
        })
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

    // --- 数据加载与渲染 ---

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
        inputPanel.setBodyType("raw (json)")
        inputPanel.loadRequestData(params, headers, body, "noauth", mapOf(), emptyList())
        if (api.method.uppercase() in listOf("POST", "PUT")) inputPanel.selectedIndex = 3 else inputPanel.selectedIndex = 0
        responsePanel.clear()
    }

    fun renderSavedRequest(node: CollectionNode) {
        activeCollectionNode = node
        val req = node.request ?: return
        addressBar.method = req.method.uppercase()
        addressBar.url = req.url
        val bType = if (req.bodyType.isNullOrBlank()) "raw (json)" else req.bodyType
        inputPanel.setBodyType(bType)
        inputPanel.loadRequestData(req.params, req.headers, req.bodyContent, req.authType, req.authContent, req.extractRules)
        responsePanel.clear()
    }

    // --- 辅助方法 ---

    private fun importCurl(curlText: String): Boolean {
        val curlReq = CurlConverter.parseCurl(curlText) ?: return false
        addressBar.method = curlReq.method
        addressBar.url = curlReq.url
        var bodyType = "raw (json)"
        if (curlReq.contentType != null) {
            if (curlReq.contentType.contains("xml")) bodyType = "raw (xml)"
            else if (curlReq.contentType.contains("text")) bodyType = "raw (text)"
            else if (curlReq.contentType.contains("form-urlencoded")) bodyType = "x-www-form-urlencoded"
        }
        inputPanel.setBodyType(bodyType)
        inputPanel.loadRequestData(emptyList(), curlReq.headers, curlReq.body, "noauth", mapOf(), emptyList())
        showBalloon("Imported cURL!", MessageType.INFO, addressBar)
        return true
    }

    private fun copyAsCurl() {
        val tempReq = SavedRequest()
        collectData(tempReq)
        val curlCmd = CurlConverter.toCurl(tempReq)
        CopyPasteManager.getInstance().setContents(StringSelection(curlCmd))
        showBalloon("cURL copied to clipboard!", MessageType.INFO, addressBar)
    }

    private fun collectData(targetReq: SavedRequest) {
        targetReq.method = addressBar.method
        targetReq.url = addressBar.url
        targetReq.bodyType = inputPanel.getBodyType()
        targetReq.bodyContent = inputPanel.getBody()
        targetReq.params = inputPanel.getQueryParams()
        targetReq.headers = inputPanel.getHeaders()
        val (authType, authContent) = inputPanel.getAuthData()
        targetReq.authType = authType
        targetReq.authContent = authContent
        targetReq.extractRules = inputPanel.getExtractRules()
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

    private fun showBalloon(msg: String, type: MessageType, target: JComponent) {
        JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(msg, type, null)
            .setFadeoutTime(1500).createBalloon().show(RelativePoint.getCenterOf(target), Balloon.Position.below)
    }
}