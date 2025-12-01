package com.phil.rest.ui

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.intellij.icons.AllIcons
import com.intellij.json.JsonLanguage
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.LanguageTextField
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.phil.rest.model.*
import com.phil.rest.service.CollectionService
import com.phil.rest.service.EnvService
import com.phil.rest.service.HeaderStore
import com.phil.rest.service.HttpExecutor
import com.phil.rest.ui.action.EnvironmentComboAction
import com.phil.rest.ui.component.GeekAddressBar
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.util.*
import javax.swing.*
import javax.swing.table.DefaultTableModel

class RequestEditorPanel(
    private val project: Project,
    private val onSaveSuccess: () -> Unit
) : SimpleToolWindowPanel(true, true) {

    private var activeCollectionNode: CollectionNode? = null

    // --- 核心组件 ---
    private val addressBar = GeekAddressBar(project) { sendRequest() }

    // --- Tabs ---
    private val tabbedPane = JBTabbedPane()

    // Params
    private val paramsTableModel = DefaultTableModel(arrayOf("Key", "Value"), 0)
    private val paramsTable = JBTable(paramsTableModel)

    // Auth
    private val authTypeCombo = ComboBox(arrayOf("No Auth", "Bearer Token", "Basic Auth"))
    private val bearerTokenField = JTextField()
    private val basicUserField = JTextField()
    private val basicPasswordField = JPasswordField()

    // Headers
    private val headersTableModel = DefaultTableModel(arrayOf("Key", "Value"), 0)
    private val headersTable = JBTable(headersTableModel)

    // Body & Response
    private val bodyTypeCombo = ComboBox(arrayOf("none", "raw (json)"))
    private val bodyEditor = LanguageTextField(JsonLanguage.INSTANCE, project, "", false)
    private val responseEditor = LanguageTextField(JsonLanguage.INSTANCE, project, "", true).apply { isViewer = true }

    // 状态栏
    private val statusLabel = JBLabel(" Ready", AllIcons.General.Balloon, SwingConstants.LEFT).apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }
    private val timeLabel = JBLabel()

    private val objectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    init {
        refreshEnvComboBox() // 虽然里面其实没逻辑了，但保留结构

        val toolbar = createTopToolbar()
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        val mainContent = JPanel(BorderLayout())
        val addressWrapper = JPanel(BorderLayout())
        addressWrapper.border = JBUI.Borders.empty(10, 10, 5, 10)
        addressWrapper.add(addressBar, BorderLayout.CENTER)

        val paramsPanel = createTablePanel(paramsTable, paramsTableModel)
        setupHeaderAutoCompletion()
        val headersPanel = createTablePanel(headersTable, headersTableModel)
        val authPanel = createAuthPanel()
        val bodyPanel = createBodyPanel()
        val responsePanel = createResponsePanel()

        tabbedPane.addTab("Params", paramsPanel)
        tabbedPane.addTab("Auth", authPanel)
        tabbedPane.addTab("Headers", headersPanel)
        tabbedPane.addTab("Body", bodyPanel)
        tabbedPane.addTab("Response", responsePanel)

        val statusBar = JPanel(BorderLayout())
        statusBar.border = JBUI.Borders.empty(2, 5)
        statusBar.background = JBUI.CurrentTheme.StatusBar.Widget.HOVER_BACKGROUND
        statusBar.add(statusLabel, BorderLayout.WEST)
        statusBar.add(timeLabel, BorderLayout.EAST)

        mainContent.add(addressWrapper, BorderLayout.NORTH)
        mainContent.add(tabbedPane, BorderLayout.CENTER)
        mainContent.add(statusBar, BorderLayout.SOUTH)

        setContent(mainContent)
    }

    // ... createTopToolbar, createResponsePanel, createBodyPanel, createAuthPanel ...
    // ... createTablePanel, setupHeaderAutoCompletion, setEditorText, resolveVariables, refreshEnvComboBox ...
    // 为了节省篇幅，请确保保留这些辅助方法 (与上一版一致)

    // ------------------------------------------------------------------------
    // 仅贴出需要修改逻辑的方法：sendRequest
    // ------------------------------------------------------------------------

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

    // ... (其他构建方法略，请从上一条回复复制) ...

    private fun createResponsePanel(): JPanel {
        val panel = JPanel(BorderLayout())
        val actionGroup = DefaultActionGroup()
        actionGroup.add(object : DumbAwareAction("Copy", "Copy response body", AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) {
                val text = responseEditor.text
                if (text.isNotEmpty()) {
                    CopyPasteManager.getInstance().setContents(StringSelection(text))
                    JBPopupFactory.getInstance().createHtmlTextBalloonBuilder("Copied!", MessageType.INFO, null).setFadeoutTime(1500).createBalloon().show(RelativePoint.getSouthEastOf(statusLabel), Balloon.Position.atRight)
                }
            }
        })
        actionGroup.add(object : DumbAwareAction("Export", "Export to file", AllIcons.Actions.MenuSaveall) {
            override fun actionPerformed(e: AnActionEvent) {
                val descriptor = FileSaverDescriptor("Export Response", "Save response body", "json", "txt")
                val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
                val wrapper = dialog.save(null as VirtualFile?, "response.json")
                if (wrapper != null) {
                    try { wrapper.file.writeText(responseEditor.text); statusLabel.text = " Saved to ${wrapper.file.name}" }
                    catch (ex: Exception) { statusLabel.text = " Export failed: ${ex.message}"; statusLabel.icon = AllIcons.General.Error }
                }
            }
        })
        val toolbar = ActionManager.getInstance().createActionToolbar("ResponseToolbar", actionGroup, true)
        toolbar.targetComponent = responseEditor
        val toolBarPanel = JPanel(BorderLayout())
        toolBarPanel.border = JBUI.Borders.emptyBottom(2)
        toolBarPanel.add(JLabel("Body:"), BorderLayout.WEST)
        toolBarPanel.add(toolbar.component, BorderLayout.EAST)
        panel.add(toolBarPanel, BorderLayout.NORTH)
        panel.add(responseEditor, BorderLayout.CENTER)
        return panel
    }

    private fun createBodyPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        val top = JPanel(FlowLayout(FlowLayout.LEFT))
        top.add(JLabel("Content-Type:"))
        top.add(bodyTypeCombo)
        bodyTypeCombo.addActionListener {
            val type = bodyTypeCombo.selectedItem as String
            bodyEditor.isEnabled = type != "none"
            if (type == "none") setEditorText(bodyEditor, "")
        }
        panel.add(top, BorderLayout.NORTH)
        panel.add(bodyEditor, BorderLayout.CENTER)
        return panel
    }

    private fun createAuthPanel(): JPanel {
        val cardPanel = JPanel(CardLayout())
        val noAuth = JPanel()
        val bearer = panel { row("Token:") { cell(bearerTokenField).align(AlignX.FILL) } }
        val basic = panel { row("Username:") { cell(basicUserField).align(AlignX.FILL) }; row("Password:") { cell(basicPasswordField).align(AlignX.FILL) } }
        cardPanel.add(noAuth, "No Auth")
        cardPanel.add(bearer, "Bearer Token")
        cardPanel.add(basic, "Basic Auth")
        val main = JPanel(BorderLayout())
        main.add(panel { row("Auth Type:") { cell(authTypeCombo) } }, BorderLayout.NORTH)
        main.add(cardPanel, BorderLayout.CENTER)
        authTypeCombo.addActionListener { (cardPanel.layout as CardLayout).show(cardPanel, authTypeCombo.selectedItem as String) }
        return main
    }

    private fun createTablePanel(table: JBTable, model: DefaultTableModel): JPanel {
        val decorator = ToolbarDecorator.createDecorator(table)
            .setAddAction { model.addRow(arrayOf("", "")) }
            .setRemoveAction { if (table.isEditing) table.cellEditor?.stopCellEditing(); if (table.selectedRow >= 0) model.removeRow(table.selectedRow) }
        return decorator.createPanel()
    }

    private fun setupHeaderAutoCompletion() {
        val headerStore = HeaderStore.getInstance(project)
        val suggestions = headerStore.getAllSuggestions().toTypedArray()
        val headerKeyEditor = DefaultCellEditor(ComboBox(suggestions).apply { isEditable = true })
        headersTable.columnModel.getColumn(0).cellEditor = headerKeyEditor
    }

    private fun setEditorText(editor: LanguageTextField, text: String) {
        ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) WriteCommandAction.runWriteCommandAction(project) { editor.text = text } }
    }

    private fun resolveVariables(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val selectedEnv = EnvService.getInstance(project).selectedEnv ?: return text
        var result = text
        for ((key, value) in selectedEnv.variables) { result = result?.replace("{{$key}}", value) }
        return result ?: ""
    }

    private fun refreshEnvComboBox() { } // 空实现

    private fun getUniqueName(folder: CollectionNode, baseName: String): String {
        var uniqueName = baseName
        var counter = 1
        val existingNames = folder.children.map { it.name }.toSet()
        while (existingNames.contains(uniqueName)) { uniqueName = "$baseName ($counter)"; counter++ }
        return uniqueName
    }

    private fun formatJson(json: String): String {
        if (json.isBlank()) return ""
        return try { objectMapper.writeValueAsString(objectMapper.readTree(json)) } catch (e: Exception) { json }
    }

    fun createNewEmptyRequest() {
        activeCollectionNode = null
        addressBar.method = "GET"
        addressBar.url = ""
        paramsTableModel.rowCount = 0; paramsTableModel.addRow(arrayOf("", ""))
        headersTableModel.rowCount = 0; headersTableModel.addRow(arrayOf("", ""))
        bodyTypeCombo.selectedItem = "none"
        setEditorText(bodyEditor, "")
        authTypeCombo.selectedItem = "No Auth"
        bearerTokenField.text = ""
        basicUserField.text = ""
        basicPasswordField.text = ""
        setEditorText(responseEditor, "")
        statusLabel.text = " New Request"
        statusLabel.icon = AllIcons.General.Add
        timeLabel.text = ""
        tabbedPane.selectedIndex = 0
    }

    fun renderApi(api: ApiDefinition) {
        activeCollectionNode = null
        addressBar.method = api.method.uppercase()
        addressBar.url = "http://localhost:8080" + api.url
        paramsTableModel.rowCount = 0
        headersTableModel.rowCount = 0
        setEditorText(bodyEditor, "")
        bodyTypeCombo.selectedItem = "none"
        authTypeCombo.selectedItem = "No Auth"
        for (param in api.params) {
            when (param.type) {
                RestParam.ParamType.PATH -> addressBar.url = addressBar.url.replace("{${param.name}}", param.value)
                RestParam.ParamType.QUERY -> paramsTableModel.addRow(arrayOf(param.name, param.value))
                RestParam.ParamType.HEADER -> headersTableModel.addRow(arrayOf(param.name, param.value))
                RestParam.ParamType.BODY -> {
                    bodyTypeCombo.selectedItem = "raw (json)"
                    setEditorText(bodyEditor, param.value.ifEmpty { "{}" })
                    if (api.method.uppercase() in listOf("POST", "PUT")) tabbedPane.selectedIndex = 3
                }
            }
        }
        statusLabel.text = " Unsaved API"
        statusLabel.icon = AllIcons.FileTypes.Java
    }

    fun renderSavedRequest(node: CollectionNode) {
        activeCollectionNode = node
        val req = node.request ?: return
        addressBar.method = req.method.uppercase()
        addressBar.url = req.url
        paramsTableModel.rowCount = 0
        req.params.forEach { paramsTableModel.addRow(arrayOf(it.name, it.value)) }
        headersTableModel.rowCount = 0
        req.headers.forEach { headersTableModel.addRow(arrayOf(it.name, it.value)) }
        val content = req.bodyContent ?: ""
        setEditorText(bodyEditor, content)
        bodyTypeCombo.selectedItem = if (content.isEmpty()) "none" else "raw (json)"
        val typeStr = when (req.authType) { "bearer" -> "Bearer Token"; "basic" -> "Basic Auth"; else -> "No Auth" }
        authTypeCombo.selectedItem = typeStr
        bearerTokenField.text = req.authContent["token"] ?: ""
        basicUserField.text = req.authContent["username"] ?: ""
        basicPasswordField.text = req.authContent["password"] ?: ""
        tabbedPane.selectedIndex = 0
        setEditorText(responseEditor, "")
        statusLabel.text = " Editing: ${node.name}"
        statusLabel.icon = AllIcons.Actions.Edit
    }

    private fun collectData(targetReq: SavedRequest) {
        targetReq.method = addressBar.method
        targetReq.url = addressBar.url
        targetReq.bodyContent = if (bodyTypeCombo.selectedItem == "none") null else bodyEditor.text
        val params = ArrayList<RestParam>()
        for (i in 0 until paramsTableModel.rowCount) {
            val k = paramsTableModel.getValueAt(i, 0) as String
            val v = paramsTableModel.getValueAt(i, 1) as String
            if (k.isNotBlank()) params.add(RestParam(k, v, RestParam.ParamType.QUERY, "String"))
        }
        targetReq.params = params
        val headers = ArrayList<RestParam>()
        for (i in 0 until headersTableModel.rowCount) {
            val k = headersTableModel.getValueAt(i, 0) as String
            val v = headersTableModel.getValueAt(i, 1) as String
            if (k.isNotBlank()) headers.add(RestParam(k, v, RestParam.ParamType.HEADER, "String"))
        }
        targetReq.headers = headers
        val authType = authTypeCombo.selectedItem as String
        targetReq.authType = when (authType) { "Bearer Token" -> "bearer"; "Basic Auth" -> "basic"; else -> "noauth" }
        val authMap = HashMap<String, String>()
        if (targetReq.authType == "bearer") authMap["token"] = bearerTokenField.text
        if (targetReq.authType == "basic") { authMap["username"] = basicUserField.text; authMap["password"] = String(basicPasswordField.password) }
        targetReq.authContent = authMap
    }

    private fun updateExistingRequest() {
        val node = activeCollectionNode ?: return
        val req = node.request ?: SavedRequest()
        collectData(req)
        node.request = req
        onSaveSuccess()
        statusLabel.text = " Saved: ${node.name}"
        statusLabel.icon = AllIcons.Actions.MenuSaveall
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
            statusLabel.text = " Saved: $uniqueName"
            statusLabel.icon = AllIcons.Actions.MenuSaveall
        }
    }

    private fun sendRequest() {
        if (headersTable.isEditing) headersTable.cellEditor.stopCellEditing()
        if (paramsTable.isEditing) paramsTable.cellEditor.stopCellEditing()

        var finalUrl = resolveVariables(addressBar.url)
        val method = addressBar.method
        val finalBody = resolveVariables(if (bodyTypeCombo.selectedItem == "none") null else bodyEditor.text)

        val queryParams = StringBuilder()
        for (i in 0 until paramsTableModel.rowCount) {
            val key = paramsTableModel.getValueAt(i, 0) as String
            val valResolved = resolveVariables(paramsTableModel.getValueAt(i, 1) as String)
            if (key.isNotBlank()) {
                if (queryParams.isEmpty()) queryParams.append("?") else queryParams.append("&")
                queryParams.append("$key=$valResolved")
            }
        }
        if (!finalUrl.contains("?") && queryParams.isNotEmpty()) finalUrl += queryParams.toString()
        else if (finalUrl.contains("?") && queryParams.isNotEmpty()) finalUrl += "&" + queryParams.substring(1)

        val headers = ArrayList<RestParam>()
        val headerStore = HeaderStore.getInstance(project)
        for (i in 0 until headersTableModel.rowCount) {
            val key = headersTableModel.getValueAt(i, 0) as String
            val valResolved = resolveVariables(headersTableModel.getValueAt(i, 1) as String)
            if (key.isNotBlank()) {
                headers.add(RestParam(key, valResolved, RestParam.ParamType.HEADER, "String"))
                headerStore.recordHeader(key)
            }
        }

        val authType = authTypeCombo.selectedItem as String
        if (authType == "Bearer Token") {
            val token = resolveVariables(bearerTokenField.text)
            if (token.isNotBlank()) headers.add(RestParam("Authorization", "Bearer $token", RestParam.ParamType.HEADER, "String"))
        } else if (authType == "Basic Auth") {
            val user = resolveVariables(basicUserField.text)
            val pass = resolveVariables(String(basicPasswordField.password))
            if (user.isNotBlank() || pass.isNotBlank()) {
                val encoded = Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
                headers.add(RestParam("Authorization", "Basic $encoded", RestParam.ParamType.HEADER, "String"))
            }
        }

        // [修复] 使用 addressBar.isBusy 控制状态
        addressBar.isBusy = true
        statusLabel.text = " Sending..."
        statusLabel.icon = AllIcons.Process.Step_1
        timeLabel.text = ""
        setEditorText(responseEditor, "")
        tabbedPane.selectedIndex = 4

        ApplicationManager.getApplication().executeOnPooledThread {
            val executor = HttpExecutor()
            val response = executor.execute(method, finalUrl, finalBody, headers)

            SwingUtilities.invokeLater {
                // [修复] 恢复状态
                addressBar.isBusy = false

                val prettyBody = formatJson(response.body)
                statusLabel.text = " Status: ${response.statusCode} ${if(response.statusCode==200) "OK" else ""}"
                timeLabel.text = "Time: ${response.durationMs}ms  "
                if (response.statusCode in 200..299) {
                    statusLabel.icon = AllIcons.RunConfigurations.TestState.Green2
                    statusLabel.foreground = Color(54, 150, 70)
                } else {
                    statusLabel.icon = AllIcons.RunConfigurations.TestState.Red2
                    statusLabel.foreground = Color(200, 50, 50)
                }
                setEditorText(responseEditor, prettyBody)
            }
        }
    }
}