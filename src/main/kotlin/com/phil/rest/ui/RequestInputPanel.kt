package com.phil.rest.ui

import com.intellij.ide.highlighter.XmlFileType
import com.intellij.json.JsonFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.phil.rest.model.RestParam
import com.phil.rest.service.HeaderStore
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.Font
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.swing.*
import javax.swing.table.DefaultTableModel

class RequestInputPanel(private val project: Project) : JBTabbedPane() {

    // --- Params ---
    private val paramsTableModel = DefaultTableModel(arrayOf("Key", "Value"), 0)
    private val paramsTable = JBTable(paramsTableModel)

    // --- Headers ---
    private val headersTableModel = DefaultTableModel(arrayOf("Key", "Value"), 0)
    private val headersTable = JBTable(headersTableModel)

    // --- Auth ---
    private val authTypeCombo = ComboBox(arrayOf("No Auth", "Bearer Token", "Basic Auth", "API Key"))
    private val bearerTokenField = JTextField()
    private val basicUserField = JTextField()
    private val basicPasswordField = JPasswordField()
    // [Auth增强] API Key 组件
    private val apiKeyKeyField = JTextField()
    private val apiKeyValueField = JTextField()
    private val apiKeyLocationCombo = ComboBox(arrayOf("Header", "Query Params"))

    // --- Body ---
    // [Body增强] 支持更多 Body 类型
    private val bodyTypeCombo = ComboBox(arrayOf("none", "raw (json)", "raw (text)", "raw (xml)", "x-www-form-urlencoded"))
    // 使用 EditorTextField 以支持动态切换 FileType (JSON/XML/Text)
    private val bodyEditor = EditorTextField("", project, JsonFileType.INSTANCE)

    // [Body增强] Form Data 表格
    private val formTableModel = DefaultTableModel(arrayOf("Key", "Value"), 0)
    private val formTable = JBTable(formTableModel)
    private val bodyCardPanel = JPanel(CardLayout()) // 用于切换 Editor 和 Table

    init {
        // [UI美化] 应用表格样式
        styleTable(paramsTable)
        styleTable(headersTable)
        styleTable(formTable)

        // 1. Params Tab
        addTab("Params", createTablePanel(paramsTable, paramsTableModel))

        // 2. Auth Tab
        addTab("Auth", createAuthPanel())

        // 3. Headers Tab
        setupHeaderAutoCompletion()
        addTab("Headers", createTablePanel(headersTable, headersTableModel))

        // 4. Body Tab
        addTab("Body", createBodyPanel())
    }

    // --- 公开 API: 数据获取 ---

    fun getQueryParams(): List<RestParam> = getTableData(paramsTableModel, RestParam.ParamType.QUERY)
    fun getHeaders(): List<RestParam> = getTableData(headersTableModel, RestParam.ParamType.HEADER)

    fun getBody(): String? {
        val type = bodyTypeCombo.selectedItem as String
        if (type == "none") return null

        // [Body增强] 如果是 form-urlencoded，将表格转换为 k=v&k=v 字符串
        if (type == "x-www-form-urlencoded") {
            val sb = StringBuilder()
            for (i in 0 until formTableModel.rowCount) {
                val k = formTableModel.getValueAt(i, 0) as String
                val v = formTableModel.getValueAt(i, 1) as String
                if (k.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append("&")
                    // 注意：这里需要 URL 编码
                    sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                        .append("=")
                        .append(URLEncoder.encode(v, StandardCharsets.UTF_8))
                }
            }
            return sb.toString()
        }

        return bodyEditor.text
    }

    // 获取 Body 类型，方便 EditorPanel 设置 Content-Type
    fun getBodyType(): String = bodyTypeCombo.selectedItem as String

    fun getAuthData(): Pair<String, Map<String, String>> {
        val typeStr = authTypeCombo.selectedItem as String
        val typeCode = when (typeStr) {
            "Bearer Token" -> "bearer"
            "Basic Auth" -> "basic"
            "API Key" -> "apikey"
            else -> "noauth"
        }
        val map = HashMap<String, String>()
        if (typeCode == "bearer") map["token"] = bearerTokenField.text
        if (typeCode == "basic") {
            map["username"] = basicUserField.text
            map["password"] = String(basicPasswordField.password)
        }
        if (typeCode == "apikey") {
            map["key"] = apiKeyKeyField.text
            map["value"] = apiKeyValueField.text
            map["where"] = apiKeyLocationCombo.selectedItem as String
        }
        return typeCode to map
    }

    // --- 公开 API: 数据加载 ---

    fun loadRequestData(params: List<RestParam>, headers: List<RestParam>, body: String?, authType: String, authContent: Map<String, String>) {
        // Params
        paramsTableModel.rowCount = 0
        params.forEach { paramsTableModel.addRow(arrayOf(it.name, it.value)) }
        if (paramsTableModel.rowCount == 0) paramsTableModel.addRow(arrayOf("", ""))

        // Headers
        headersTableModel.rowCount = 0
        headers.forEach { headersTableModel.addRow(arrayOf(it.name, it.value)) }
        if (headersTableModel.rowCount == 0) headersTableModel.addRow(arrayOf("", ""))

        // Body
        // 外部（RequestEditorPanel）通常已经先调用 setBodyType 设置了正确的 Combo 状态
        // 这里根据 Combo 状态来决定如何填充数据
        val currentType = bodyTypeCombo.selectedItem as String
        if (currentType == "x-www-form-urlencoded" && body != null) {
            // 解析 k=v&k=v -> Table
            formTableModel.rowCount = 0
            body.split("&").forEach { pair ->
                val parts = pair.split("=")
                if (parts.size == 2) {
                    try {
                        val k = URLDecoder.decode(parts[0], StandardCharsets.UTF_8)
                        val v = URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                        formTableModel.addRow(arrayOf(k, v))
                    } catch (e: Exception) { /* ignore */ }
                }
            }
            if (formTableModel.rowCount == 0) formTableModel.addRow(arrayOf("", ""))
        } else {
            setEditorText(bodyEditor, body ?: "")
        }

        // Auth
        val typeStr = when (authType) {
            "bearer" -> "Bearer Token"
            "basic" -> "Basic Auth"
            "apikey" -> "API Key"
            else -> "No Auth"
        }
        authTypeCombo.selectedItem = typeStr
        bearerTokenField.text = authContent["token"] ?: ""
        basicUserField.text = authContent["username"] ?: ""
        basicPasswordField.text = authContent["password"] ?: ""

        apiKeyKeyField.text = authContent["key"] ?: ""
        apiKeyValueField.text = authContent["value"] ?: ""
        apiKeyLocationCombo.selectedItem = authContent["where"] ?: "Header"
    }

    fun setBodyType(type: String) {
        bodyTypeCombo.selectedItem = type
    }

    fun clearAll() {
        paramsTableModel.rowCount = 0; paramsTableModel.addRow(arrayOf("", ""))
        headersTableModel.rowCount = 0; headersTableModel.addRow(arrayOf("", ""))
        formTableModel.rowCount = 0; formTableModel.addRow(arrayOf("", ""))

        setEditorText(bodyEditor, "")
        bodyTypeCombo.selectedItem = "none"

        authTypeCombo.selectedItem = "No Auth"
        bearerTokenField.text = ""
        basicUserField.text = ""
        basicPasswordField.text = ""
        apiKeyKeyField.text = ""
        apiKeyValueField.text = ""

        selectedIndex = 0
    }

    // --- UI 构建辅助方法 ---

    // [UI美化] 统一表格样式
    private fun styleTable(table: JBTable) {
        table.rowHeight = 28 // 增加行高，更现代
        table.setShowGrid(false) // 隐藏所有网格线
        table.setShowHorizontalLines(true) // 只显示水平分隔线
        table.gridColor = JBColor.border() // 淡色分割线
        table.intercellSpacing = java.awt.Dimension(0, 0) // 去掉单元格间隙

        // 表头字体
        table.tableHeader.font = Font("JetBrains Mono", Font.BOLD, 12)
        // 内容字体
        table.font = Font("JetBrains Mono", Font.PLAIN, 13)
    }

    private fun createTablePanel(table: JBTable, model: DefaultTableModel): JPanel {
        val decorator = ToolbarDecorator.createDecorator(table)
            .setAddAction { model.addRow(arrayOf("", "")) }
            .setRemoveAction {
                if (table.isEditing) table.cellEditor?.stopCellEditing()
                if (table.selectedRow >= 0) model.removeRow(table.selectedRow)
            }
        return decorator.createPanel()
    }

    private fun createBodyPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        val top = JPanel(FlowLayout(FlowLayout.LEFT))
        top.add(JLabel("Content-Type:"))
        top.add(bodyTypeCombo)

        // Form Table Panel
        val formPanel = createTablePanel(formTable, formTableModel)

        // Editor Panel
        // EditorTextField 本身就是一个 Component
        val editorPanel = bodyEditor

        bodyCardPanel.add(editorPanel, "editor")
        bodyCardPanel.add(formPanel, "form")

        // 监听类型切换
        bodyTypeCombo.addActionListener {
            val type = bodyTypeCombo.selectedItem as String

            // 1. 切换 CardLayout (Table vs Editor)
            val layout = bodyCardPanel.layout as CardLayout
            if (type == "x-www-form-urlencoded") {
                layout.show(bodyCardPanel, "form")
            } else {
                layout.show(bodyCardPanel, "editor")
                // 2. 切换 Editor 高亮语言
                when (type) {
                    "raw (json)" -> bodyEditor.setFileType(JsonFileType.INSTANCE)
                    "raw (xml)" -> bodyEditor.setFileType(XmlFileType.INSTANCE)
                    "raw (text)" -> bodyEditor.setFileType(PlainTextFileType.INSTANCE)
                }
            }

            // 3. 启用/禁用控件
            val isNone = type == "none"
            bodyEditor.isEnabled = !isNone
            formTable.isEnabled = !isNone
            if (isNone) setEditorText(bodyEditor, "")
        }

        panel.add(top, BorderLayout.NORTH)
        panel.add(bodyCardPanel, BorderLayout.CENTER)
        return panel
    }

    private fun createAuthPanel(): JPanel {
        val cardPanel = JPanel(CardLayout())

        // No Auth
        cardPanel.add(JPanel(), "No Auth")

        // Bearer
        cardPanel.add(panel {
            row("Token:") { cell(bearerTokenField).align(AlignX.FILL) }
        }, "Bearer Token")

        // Basic
        cardPanel.add(panel {
            row("Username:") { cell(basicUserField).align(AlignX.FILL) }
            row("Password:") { cell(basicPasswordField).align(AlignX.FILL) }
        }, "Basic Auth")

        // [Auth增强] API Key
        cardPanel.add(panel {
            row("Key:") { cell(apiKeyKeyField).align(AlignX.FILL) }
            row("Value:") { cell(apiKeyValueField).align(AlignX.FILL) }
            row("Add To:") { cell(apiKeyLocationCombo) }
        }, "API Key")

        val main = JPanel(BorderLayout())
        main.add(panel { row("Auth Type:") { cell(authTypeCombo) } }, BorderLayout.NORTH)
        main.add(cardPanel, BorderLayout.CENTER)

        authTypeCombo.addActionListener { (cardPanel.layout as CardLayout).show(cardPanel, authTypeCombo.selectedItem as String) }
        return main
    }

    private fun setupHeaderAutoCompletion() {
        val headerStore = HeaderStore.getInstance(project)
        val suggestions = headerStore.getAllSuggestions().toTypedArray()
        val headerKeyEditor = DefaultCellEditor(ComboBox(suggestions).apply { isEditable = true })
        headersTable.columnModel.getColumn(0).cellEditor = headerKeyEditor
    }

    private fun getTableData(model: DefaultTableModel, type: RestParam.ParamType): List<RestParam> {
        val list = ArrayList<RestParam>()
        for (i in 0 until model.rowCount) {
            val k = model.getValueAt(i, 0) as String
            val v = model.getValueAt(i, 1) as String
            if (k.isNotBlank()) list.add(RestParam(k, v, type, "String"))
        }
        return list
    }

    private fun setEditorText(editor: EditorTextField, text: String) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) WriteCommandAction.runWriteCommandAction(project) { editor.text = text }
        }
    }
}