package com.phil.rest.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.phil.rest.model.ApiDefinition
import com.phil.rest.model.CollectionNode
import com.phil.rest.model.RestParam
import com.phil.rest.model.SavedRequest
import com.phil.rest.service.CollectionService
import com.phil.rest.service.HttpExecutor
import java.awt.BorderLayout
import javax.swing.*
import javax.swing.table.DefaultTableModel

class RequestEditorPanel(
    private val project: Project,
    private val onSaveSuccess: () -> Unit
) : JPanel(BorderLayout()) {

    private var activeCollectionNode: CollectionNode? = null

    // --- 顶部组件 ---
    private val methodComboBox = ComboBox(arrayOf("GET", "POST", "PUT", "DELETE", "PATCH"))
    private val urlField = JTextField()
    private val sendButton = JButton("Send")
    private val saveButton = JButton("Save")
    private val saveAsButton = JButton("Save As...")
    private val tabbedPane = JBTabbedPane()

    // --- Params 组件 ---
    private val paramsTableModel = DefaultTableModel(arrayOf("Key", "Value"), 0)
    private val paramsTable = JBTable(paramsTableModel)

    // --- Headers 组件 (新增) ---
    private val headersTableModel = DefaultTableModel(arrayOf("Key", "Value"), 0)
    private val headersTable = JBTable(headersTableModel)

    // --- Body 组件 (新增类型选择) ---
    private val bodyTypeCombo = ComboBox(arrayOf("none", "raw (json)")) // 暂时只支持这俩
    private val bodyTextArea = JBTextArea()

    // --- Response 组件 ---
    private val responseArea = JBTextArea().apply { isEditable = false }
    private val responseStatusLabel = JLabel("Ready")

    init {
        // 1. 顶部 URL 栏
        val topPanel = panel {
            row {
                cell(methodComboBox)
                cell(urlField).align(AlignX.FILL)
                cell(sendButton)
                cell(saveButton)
                cell(saveAsButton)
            }
        }

        // 2. Params 面板 (带 Add/Remove)
        val paramsPanel = createTablePanel(paramsTable, paramsTableModel)

        // 3. Headers 面板 (新增)
        val headersPanel = createTablePanel(headersTable, headersTableModel)

        // 4. Body 面板 (带类型选择)
        val bodyPanel = JPanel(BorderLayout())
        val bodyTopBar = JPanel(BorderLayout())
        bodyTopBar.add(JLabel("Type: "), BorderLayout.WEST)
        bodyTopBar.add(bodyTypeCombo, BorderLayout.CENTER)
        // Body 类型切换逻辑
        bodyTypeCombo.addActionListener {
            val type = bodyTypeCombo.selectedItem as String
            bodyTextArea.isEnabled = type != "none"
            if (type == "none") bodyTextArea.text = ""
        }
        bodyPanel.add(bodyTopBar, BorderLayout.NORTH)
        bodyPanel.add(JBScrollPane(bodyTextArea), BorderLayout.CENTER)

        // 5. Response 面板
        val responsePanel = JPanel(BorderLayout())
        responsePanel.add(responseStatusLabel, BorderLayout.NORTH)
        responsePanel.add(JBScrollPane(responseArea), BorderLayout.CENTER)

        // 6. 组装 Tabs
        tabbedPane.addTab("Params", paramsPanel)
        tabbedPane.addTab("Body", bodyPanel)
        tabbedPane.addTab("Headers", headersPanel) // 现在有了!
        tabbedPane.addTab("Response", responsePanel)

        add(topPanel, BorderLayout.NORTH)
        add(tabbedPane, BorderLayout.CENTER)

        // 事件绑定
        sendButton.addActionListener { sendRequest() }
        saveButton.addActionListener { if (activeCollectionNode != null) updateExistingRequest() else createNewRequestFlow() }
        saveAsButton.addActionListener { createNewRequestFlow() }
    }

    // 辅助方法：创建带 Toolbar 的表格面板
    private fun createTablePanel(table: JBTable, model: DefaultTableModel): JPanel {
        val decorator = ToolbarDecorator.createDecorator(table)
            .setAddAction { model.addRow(arrayOf("", "")) }
            .setRemoveAction {
                // *** 修复问题2：删除前停止编辑，防止报错 ***
                if (table.isEditing) {
                    table.cellEditor?.stopCellEditing()
                }
                val selectedRow = table.selectedRow
                if (selectedRow >= 0) {
                    model.removeRow(selectedRow)
                }
            }
        val panel = JPanel(BorderLayout())
        panel.add(decorator.createPanel(), BorderLayout.CENTER)
        return panel
    }

    // --- 对外接口 ---

    // *** 修复问题3：彻底清空 ***
    fun createNewEmptyRequest() {
        activeCollectionNode = null
        methodComboBox.selectedItem = "GET"
        urlField.text = ""

        // 清空 Params
        paramsTableModel.rowCount = 0
        paramsTableModel.addRow(arrayOf("", ""))

        // 清空 Headers
        headersTableModel.rowCount = 0
        headersTableModel.addRow(arrayOf("", ""))

        // 重置 Body
        bodyTypeCombo.selectedItem = "none"
        bodyTextArea.text = ""

        responseArea.text = ""
        responseStatusLabel.text = "New Request"
        tabbedPane.selectedIndex = 0
    }

    fun renderApi(api: ApiDefinition) {
        activeCollectionNode = null
        methodComboBox.selectedItem = api.method.uppercase()
        var fullUrl = "http://localhost:8080" + api.url

        paramsTableModel.rowCount = 0
        headersTableModel.rowCount = 0
        bodyTextArea.text = ""
        bodyTypeCombo.selectedItem = "none"

        for (param in api.params) {
            when (param.type) {
                RestParam.ParamType.PATH -> fullUrl = fullUrl.replace("{${param.name}}", param.value)
                RestParam.ParamType.QUERY -> paramsTableModel.addRow(arrayOf(param.name, param.value))
                RestParam.ParamType.HEADER -> headersTableModel.addRow(arrayOf(param.name, param.value)) // 支持 Header
                RestParam.ParamType.BODY -> {
                    bodyTypeCombo.selectedItem = "raw (json)"
                    bodyTextArea.text = param.value.ifEmpty { "{}" }
                    if (api.method.uppercase() in listOf("POST", "PUT")) {
                        tabbedPane.selectedIndex = 1
                    }
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

        // 恢复 Params
        paramsTableModel.rowCount = 0
        req.params.forEach { paramsTableModel.addRow(arrayOf(it.name, it.value)) }

        // 恢复 Headers
        headersTableModel.rowCount = 0
        req.headers.forEach { headersTableModel.addRow(arrayOf(it.name, it.value)) }

        // 恢复 Body
        bodyTextArea.text = req.bodyContent ?: ""
        bodyTypeCombo.selectedItem = if (req.bodyContent.isNullOrEmpty()) "none" else "raw (json)"

        tabbedPane.selectedIndex = 0
        responseArea.text = ""
        responseStatusLabel.text = "Editing: ${node.name}"
    }

    // --- 保存 & 发送逻辑 ---

    private fun collectData(targetReq: SavedRequest) {
        targetReq.method = methodComboBox.selectedItem as String
        targetReq.url = urlField.text
        targetReq.bodyContent = if (bodyTypeCombo.selectedItem == "none") null else bodyTextArea.text

        // 收集 Params
        val params = ArrayList<RestParam>()
        for (i in 0 until paramsTableModel.rowCount) {
            val k = paramsTableModel.getValueAt(i, 0) as String
            val v = paramsTableModel.getValueAt(i, 1) as String
            if (k.isNotBlank()) params.add(RestParam(k, v, RestParam.ParamType.QUERY, "String"))
        }
        targetReq.params = params

        // 收集 Headers
        val headers = ArrayList<RestParam>()
        for (i in 0 until headersTableModel.rowCount) {
            val k = headersTableModel.getValueAt(i, 0) as String
            val v = headersTableModel.getValueAt(i, 1) as String
            if (k.isNotBlank()) headers.add(RestParam(k, v, RestParam.ParamType.HEADER, "String"))
        }
        targetReq.headers = headers
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
        var url = urlField.text
        val method = methodComboBox.selectedItem as String
        val bodyContent = if (bodyTypeCombo.selectedItem == "none") null else bodyTextArea.text

        // 1. 拼接 URL Params
        val queryParams = StringBuilder()
        for (i in 0 until paramsTableModel.rowCount) {
            val key = paramsTableModel.getValueAt(i, 0) as String
            val value = paramsTableModel.getValueAt(i, 1) as String
            if (key.isNotBlank()) {
                if (queryParams.isEmpty()) queryParams.append("?") else queryParams.append("&")
                queryParams.append("$key=$value")
            }
        }
        if (!url.contains("?") && queryParams.isNotEmpty()) {
            url += queryParams.toString()
        } else if (url.contains("?") && queryParams.isNotEmpty()) {
            url += "&" + queryParams.substring(1)
        }

        // 2. 准备 UI
        sendButton.isEnabled = false
        responseStatusLabel.text = "Sending..."
        responseArea.text = ""
        tabbedPane.selectedIndex = 3

        // 3. 异步发送 (注意：HttpExecutor 目前还没支持 Header 参数，这里只是 UI 收集了)
        ApplicationManager.getApplication().executeOnPooledThread {
            val executor = HttpExecutor()
            // TODO: 修改 HttpExecutor.execute 方法以接收 headers Map
            val response = executor.execute(method, url, bodyContent)

            SwingUtilities.invokeLater {
                sendButton.isEnabled = true
                if (response.statusCode == 0) {
                    responseStatusLabel.text = "Failed (${response.durationMs}ms)"
                    responseArea.text = response.body
                } else {
                    responseStatusLabel.text = "Status: ${response.statusCode}  Time: ${response.durationMs}ms"
                    val sb = StringBuilder()
                    sb.append("--- Headers ---\n").append(response.headersString).append("\n")
                    sb.append("--- Body ---\n").append(response.body)
                    responseArea.text = sb.toString()
                    responseArea.caretPosition = 0
                }
            }
        }
    }
}