package com.phil.rest.ui

import com.intellij.icons.AllIcons
import com.intellij.json.JsonLanguage
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.LanguageTextField
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.actionButton
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.phil.rest.model.ApiDefinition
import com.phil.rest.model.CollectionNode
import com.phil.rest.model.RestEnv
import com.phil.rest.model.RestParam
import com.phil.rest.model.SavedRequest
import com.phil.rest.service.CollectionService
import com.phil.rest.service.EnvService
import com.phil.rest.service.HeaderStore
import com.phil.rest.service.HttpExecutor
import java.awt.BorderLayout
import java.awt.CardLayout
import java.util.Base64
import javax.swing.*
import javax.swing.table.DefaultTableModel

class RequestEditorPanel(
    private val project: Project,
    private val onSaveSuccess: () -> Unit
) : JPanel(BorderLayout()) {

    private var activeCollectionNode: CollectionNode? = null

    // --- 顶部组件 ---
    private val envComboBox = ComboBox<RestEnv>()
    // 注意：管理、保存等按钮现在变成了 Action，不再是简单的 JButton 成员变量
    // 我们只需持有 Send 按钮的引用用于控制状态
    private val methodComboBox = ComboBox(arrayOf("GET", "POST", "PUT", "DELETE", "PATCH"))
    private val urlField = JTextField()
    private val sendButton = JButton("Send", AllIcons.Actions.Execute) // 加个图标更显眼

    // --- Tabs ---
    private val tabbedPane = JBTabbedPane()
    private val paramsTableModel = DefaultTableModel(arrayOf("Key", "Value"), 0)
    private val paramsTable = JBTable(paramsTableModel)

    private val headersTableModel = DefaultTableModel(arrayOf("Key", "Value"), 0)
    private val headersTable = JBTable(headersTableModel)

    // Auth
    private val authTypeCombo = ComboBox(arrayOf("No Auth", "Bearer Token", "Basic Auth"))
    private val bearerTokenField = JTextField()
    private val basicUserField = JTextField()
    private val basicPasswordField = JPasswordField()

    // Body (JSON 高亮)
    private val bodyTypeCombo = ComboBox(arrayOf("none", "raw (json)"))
    private val bodyEditor = LanguageTextField(JsonLanguage.INSTANCE, project, "", false)

    // Response (JSON 高亮只读)
    private val responseEditor = LanguageTextField(JsonLanguage.INSTANCE, project, "", true).apply {
        isViewer = true
    }
    private val responseStatusLabel = JLabel("Ready")

    init {
        // 0. 初始化数据
        refreshEnvComboBox()
        envComboBox.addActionListener {
            EnvService.getInstance(project).selectedEnv = envComboBox.selectedItem as? RestEnv
        }

        // 1. 构建全新的双层顶部面板
        val topPanel = buildCoolTopPanel()

        // 2. 构建 Tab 内容
        val paramsPanel = createTablePanel(paramsTable, paramsTableModel)
        setupHeaderAutoCompletion()
        val headersPanel = createTablePanel(headersTable, headersTableModel)
        val authPanel = createAuthPanel()
        val bodyPanel = createBodyPanel()
        val responsePanel = createResponsePanel()

        // 3. 组装
        tabbedPane.addTab("Params", paramsPanel)
        tabbedPane.addTab("Auth", authPanel)
        tabbedPane.addTab("Headers", headersPanel)
        tabbedPane.addTab("Body", bodyPanel)
        tabbedPane.addTab("Response", responsePanel)

        add(topPanel, BorderLayout.NORTH)
        add(tabbedPane, BorderLayout.CENTER)

        // 4. 事件绑定
        sendButton.addActionListener { sendRequest() }
    }

    // --- ✨ 核心整容代码：构建酷炫的顶部面板 ---
    private fun buildCoolTopPanel(): JComponent {
        return panel {
            // 第一行：环境 + 工具栏 (Meta Row)
            row {
                // 左侧：环境选择
                label("Env:")
                cell(envComboBox).gap(RightGap.SMALL)

                // ⚙️ 管理环境 (图标按钮)
                actionButton(object : DumbAwareAction("Manage Environments", "Add or edit environments", AllIcons.General.Settings) {
                    override fun actionPerformed(e: AnActionEvent) {
                        if (EnvManagerDialog(project).showAndGet()) refreshEnvComboBox()
                    }
                })

                // 中间：弹簧占位符，把后面的按钮顶到最右边
                panel { }.resizableColumn().align(AlignX.FILL)

                // 右侧：保存操作 (图标按钮)
                // 💾 保存
                actionButton(object : DumbAwareAction("Save", "Save current request", AllIcons.Actions.MenuSaveall) {
                    override fun actionPerformed(e: AnActionEvent) {
                        if (activeCollectionNode != null) updateExistingRequest() else createNewRequestFlow()
                    }
                })

                // 📝 另存为
                actionButton(object : DumbAwareAction("Save As...", "Save as new request", AllIcons.Actions.MenuPaste) {
                    override fun actionPerformed(e: AnActionEvent) {
                        createNewRequestFlow()
                    }
                })

                // 🧹 清空 (新建)
                actionButton(object : DumbAwareAction("New/Clear", "Create empty request", AllIcons.Actions.GC) {
                    override fun actionPerformed(e: AnActionEvent) {
                        createNewEmptyRequest()
                    }
                })
            }

            // 第二行：核心请求栏 (Action Row)
            // 这里不需要 label，直接像浏览器地址栏一样紧凑
            row {
                cell(methodComboBox).gap(RightGap.SMALL)
                cell(urlField).align(AlignX.FILL).gap(RightGap.SMALL)
                cell(sendButton)
            }
        }
    }

    // --- 其他辅助面板构建 ---

    private fun createBodyPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        val top = JPanel(BorderLayout())
        top.add(JLabel(" Content-Type: "), BorderLayout.WEST)
        top.add(bodyTypeCombo, BorderLayout.CENTER)

        bodyTypeCombo.addActionListener {
            val type = bodyTypeCombo.selectedItem as String
            bodyEditor.isEnabled = type != "none"
            if (type == "none") setEditorText(bodyEditor, "")
        }
        panel.add(top, BorderLayout.NORTH)
        panel.add(bodyEditor, BorderLayout.CENTER)
        return panel
    }

    private fun createResponsePanel(): JPanel {
        val panel = JPanel(BorderLayout())
        // 美化状态栏，加点 padding
        responseStatusLabel.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
        responseStatusLabel.icon = AllIcons.RunConfigurations.TestState.Run // 默认图标
        panel.add(responseStatusLabel, BorderLayout.NORTH)
        panel.add(responseEditor, BorderLayout.CENTER)
        return panel
    }

    private fun createAuthPanel(): JPanel {
        val cardPanel = JPanel(CardLayout())
        val noAuth = JPanel()

        val bearer = panel {
            row("Token:") { cell(bearerTokenField).align(AlignX.FILL) }
        }
        val basic = panel {
            row("Username:") { cell(basicUserField).align(AlignX.FILL) }
            row("Password:") { cell(basicPasswordField).align(AlignX.FILL) }
        }

        cardPanel.add(noAuth, "No Auth")
        cardPanel.add(bearer, "Bearer Token")
        cardPanel.add(basic, "Basic Auth")

        val main = JPanel(BorderLayout())
        main.add(panel {
            row("Auth Type:") { cell(authTypeCombo) }
        }, BorderLayout.NORTH)
        main.add(cardPanel, BorderLayout.CENTER)

        authTypeCombo.addActionListener {
            (cardPanel.layout as CardLayout).show(cardPanel, authTypeCombo.selectedItem as String)
        }
        return main
    }

    private fun createTablePanel(table: JBTable, model: DefaultTableModel): JPanel {
        val decorator = ToolbarDecorator.createDecorator(table)
            .setAddAction { model.addRow(arrayOf("", "")) }
            .setRemoveAction {
                if (table.isEditing) table.cellEditor?.stopCellEditing()
                if (table.selectedRow >= 0) model.removeRow(table.selectedRow)
            }
        val panel = JPanel(BorderLayout())
        panel.add(decorator.createPanel(), BorderLayout.CENTER)
        return panel
    }

    private fun setupHeaderAutoCompletion() {
        val headerStore = HeaderStore.getInstance(project)
        val suggestions = headerStore.getAllSuggestions().toTypedArray()
        val headerKeyEditor = DefaultCellEditor(ComboBox(suggestions).apply { isEditable = true })
        headersTable.columnModel.getColumn(0).cellEditor = headerKeyEditor
    }

    // 安全更新编辑器
    private fun setEditorText(editor: LanguageTextField, text: String) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                WriteCommandAction.runWriteCommandAction(project) {
                    editor.text = text
                }
            }
        }
    }

    private fun refreshEnvComboBox() {
        val service = EnvService.getInstance(project)
        val listeners = envComboBox.actionListeners
        listeners.forEach { envComboBox.removeActionListener(it) }
        envComboBox.removeAllItems()
        envComboBox.addItem(null)
        service.envs.forEach { envComboBox.addItem(it) }
        envComboBox.selectedItem = service.selectedEnv
        listeners.forEach { envComboBox.addActionListener(it) }
    }

    private fun resolveVariables(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val selectedEnv = envComboBox.selectedItem as? RestEnv ?: return text
        var result = text
        for ((key, value) in selectedEnv.variables) {
            result = result?.replace("{{$key}}", value)
        }
        return result ?: ""
    }

    // --- 逻辑部分 (保持不变) ---
    // ... sendRequest, collectData, createNewEmptyRequest, renderApi, renderSavedRequest, updateExistingRequest, createNewRequestFlow ...
    // 为节省篇幅，请直接保留上一版的所有这些业务逻辑代码，它们完全通用。
    // 只需要把 sendRequest 里的 responseStatusLabel.icon 更新一下即可

    // -------------------------------------------------------------------------
    // 下面是需要保留的业务方法，你可以直接从之前的代码块复制进来，
    // 唯一的区别是 sendRequest 里我加了一行 icon 更新
    // -------------------------------------------------------------------------

    fun createNewEmptyRequest() {
        activeCollectionNode = null
        methodComboBox.selectedItem = "GET"
        urlField.text = ""
        paramsTableModel.rowCount = 0; paramsTableModel.addRow(arrayOf("", ""))
        headersTableModel.rowCount = 0; headersTableModel.addRow(arrayOf("", ""))
        bodyTypeCombo.selectedItem = "none"
        setEditorText(bodyEditor, "")

        // Reset Auth
        authTypeCombo.selectedItem = "No Auth"
        bearerTokenField.text = ""
        basicUserField.text = ""
        basicPasswordField.text = ""

        setEditorText(responseEditor, "")
        responseStatusLabel.text = "New Request"
        responseStatusLabel.icon = AllIcons.General.Information
        tabbedPane.selectedIndex = 0
    }

    fun renderApi(api: ApiDefinition) {
        activeCollectionNode = null
        methodComboBox.selectedItem = api.method.uppercase()
        var fullUrl = "http://localhost:8080" + api.url

        paramsTableModel.rowCount = 0
        headersTableModel.rowCount = 0
        setEditorText(bodyEditor, "")
        bodyTypeCombo.selectedItem = "none"

        authTypeCombo.selectedItem = "No Auth"
        bearerTokenField.text = ""

        for (param in api.params) {
            when (param.type) {
                RestParam.ParamType.PATH -> fullUrl = fullUrl.replace("{${param.name}}", param.value)
                RestParam.ParamType.QUERY -> paramsTableModel.addRow(arrayOf(param.name, param.value))
                RestParam.ParamType.HEADER -> headersTableModel.addRow(arrayOf(param.name, param.value))
                RestParam.ParamType.BODY -> {
                    bodyTypeCombo.selectedItem = "raw (json)"
                    setEditorText(bodyEditor, param.value.ifEmpty { "{}" })
                    if (api.method.uppercase() in listOf("POST", "PUT")) tabbedPane.selectedIndex = 3
                }
            }
        }
        urlField.text = fullUrl
    }

    fun renderSavedRequest(node: CollectionNode) {
        activeCollectionNode = node
        val req = node.request ?: return
        methodComboBox.selectedItem = req.method.uppercase()
        urlField.text = req.url

        paramsTableModel.rowCount = 0
        req.params.forEach { paramsTableModel.addRow(arrayOf(it.name, it.value)) }
        headersTableModel.rowCount = 0
        req.headers.forEach { headersTableModel.addRow(arrayOf(it.name, it.value)) }

        val content = req.bodyContent ?: ""
        setEditorText(bodyEditor, content)
        bodyTypeCombo.selectedItem = if (content.isEmpty()) "none" else "raw (json)"

        val typeStr = when (req.authType) {
            "bearer" -> "Bearer Token"
            "basic" -> "Basic Auth"
            else -> "No Auth"
        }
        authTypeCombo.selectedItem = typeStr
        bearerTokenField.text = req.authContent["token"] ?: ""
        basicUserField.text = req.authContent["username"] ?: ""
        basicPasswordField.text = req.authContent["password"] ?: ""

        tabbedPane.selectedIndex = 0
        setEditorText(responseEditor, "")
        responseStatusLabel.text = "Editing: ${node.name}"
        responseStatusLabel.icon = AllIcons.Actions.Edit
    }

    private fun collectData(targetReq: SavedRequest) {
        targetReq.method = methodComboBox.selectedItem as String
        targetReq.url = urlField.text
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
        targetReq.authType = when (authType) {
            "Bearer Token" -> "bearer"
            "Basic Auth" -> "basic"
            else -> "noauth"
        }
        val authMap = HashMap<String, String>()
        if (targetReq.authType == "bearer") authMap["token"] = bearerTokenField.text
        if (targetReq.authType == "basic") {
            authMap["username"] = basicUserField.text
            authMap["password"] = String(basicPasswordField.password)
        }
        targetReq.authContent = authMap
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
            val newNode = CollectionNode.createRequest(savedRequest.name, savedRequest)
            targetFolder.addChild(newNode)
            activeCollectionNode = newNode
            onSaveSuccess()
        }
    }

    private fun sendRequest() {
        if (headersTable.isEditing) headersTable.cellEditor.stopCellEditing()
        if (paramsTable.isEditing) paramsTable.cellEditor.stopCellEditing()

        var finalUrl = resolveVariables(urlField.text)
        val method = methodComboBox.selectedItem as String
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
        if (!finalUrl.contains("?") && queryParams.isNotEmpty()) {
            finalUrl += queryParams.toString()
        } else if (finalUrl.contains("?") && queryParams.isNotEmpty()) {
            finalUrl += "&" + queryParams.substring(1)
        }

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

        sendButton.isEnabled = false
        responseStatusLabel.text = "Sending..."
        responseStatusLabel.icon = AllIcons.Process.Step_1 // 加载中图标
        setEditorText(responseEditor, "")
        tabbedPane.selectedIndex = 4

        ApplicationManager.getApplication().executeOnPooledThread {
            val executor = HttpExecutor()
            val response = executor.execute(method, finalUrl, finalBody, headers)

            SwingUtilities.invokeLater {
                sendButton.isEnabled = true
                val responseText = if (response.statusCode != 0) response.body else response.body

                responseStatusLabel.text = "Status: ${response.statusCode}  Time: ${response.durationMs}ms"
                // 根据状态码显示不同图标
                if (response.statusCode in 200..299) {
                    responseStatusLabel.icon = AllIcons.RunConfigurations.TestState.Green2
                } else {
                    responseStatusLabel.icon = AllIcons.RunConfigurations.TestState.Red2
                }

                setEditorText(responseEditor, responseText)
            }
        }
    }
}