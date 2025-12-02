package com.phil.rest.ui

import com.intellij.ide.highlighter.XmlFileType
import com.intellij.json.JsonFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.phil.rest.model.ExtractRule
import com.phil.rest.model.RestParam
import com.phil.rest.service.HeaderStore
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

class RequestInputPanel(private val project: Project) : JBTabbedPane() {

    // --- Params & Headers ---
    private val paramsTableModel = DefaultTableModel(arrayOf("Key", "Value"), 0)
    private val paramsTable = JBTable(paramsTableModel)
    private val headersTableModel = DefaultTableModel(arrayOf("Key", "Value"), 0)
    private val headersTable = JBTable(headersTableModel)

    // --- Auth ---
    private val authTypeCombo = ComboBox(arrayOf("No Auth", "Bearer Token", "Basic Auth", "API Key"))
    private val bearerTokenField = JTextField()
    private val basicUserField = JTextField()
    private val basicPasswordField = JPasswordField()
    private val apiKeyKeyField = JTextField()
    private val apiKeyValueField = JTextField()
    private val apiKeyLocationCombo = ComboBox(arrayOf("Header", "Query Params"))

    // --- Body ---
    // [UI] 增加 multipart/form-data
    private val bodyTypeCombo = ComboBox(arrayOf("none", "raw (json)", "raw (text)", "raw (xml)", "x-www-form-urlencoded", "multipart/form-data"))
    private val bodyEditor = EditorTextField("", project, JsonFileType.INSTANCE)

    // [UI] 改造 Form 表格，支持 3 列: Key, Value, Type
    private val formTableModel = DefaultTableModel(arrayOf("Key", "Value", "Type"), 0)
    private val formTable = object : JBTable(formTableModel) {
        // 自定义编辑器选择逻辑
        override fun getCellEditor(row: Int, column: Int): TableCellEditor {
            if (column == 1) { // Value 列
                val type = model.getValueAt(row, 2) as? String
                if (type == "File") {
                    return FileChooserCellEditor(project)
                }
            }
            return super.getCellEditor(row, column)
        }
    }
    private val bodyCardPanel = JPanel(CardLayout())

    // --- Extract ---
    private val extractTableModel = DefaultTableModel(arrayOf("Variable Name", "JSON Path"), 0)
    private val extractTable = JBTable(extractTableModel)

    init {
        styleTable(paramsTable)
        styleTable(headersTable)
        styleTable(formTable)
        styleTable(extractTable)

        // 配置 Form 表格的 Type 列编辑器 (Text / File)
        val typeCombo = ComboBox(arrayOf("Text", "File"))
        formTable.columnModel.getColumn(2).cellEditor = DefaultCellEditor(typeCombo)
        // 设置默认宽度
        formTable.columnModel.getColumn(2).maxWidth = 80

        addTab("Params", createTablePanel(paramsTable, paramsTableModel))
        addTab("Auth", createAuthPanel())
        setupHeaderAutoCompletion()
        addTab("Headers", createTablePanel(headersTable, headersTableModel))
        addTab("Body", createBodyPanel())
        addTab("Extract", createTablePanel(extractTable, extractTableModel))
    }

    // --- 核心：获取 Multipart 数据 ---
    fun getMultipartParams(): List<RestParam>? {
        val type = bodyTypeCombo.selectedItem as String
        if (type != "multipart/form-data") return null

        val list = ArrayList<RestParam>()
        for (i in 0 until formTableModel.rowCount) {
            val k = formTableModel.getValueAt(i, 0) as? String ?: ""
            val v = formTableModel.getValueAt(i, 1) as? String ?: ""
            val t = formTableModel.getValueAt(i, 2) as? String ?: "Text"
            if (k.isNotBlank()) {
                // 使用 dataType 字段存储 "File" 标记
                list.add(RestParam(k, v, RestParam.ParamType.BODY, t))
            }
        }
        return list
    }

    // ... (getQueryParams, getHeaders, getBody, getBodyType, getAuthData, getExtractRules 保持不变) ...
    // 为了节省篇幅，请保留原有的这些方法实现

    fun getQueryParams(): List<RestParam> = getTableData(paramsTableModel, RestParam.ParamType.QUERY)
    fun getHeaders(): List<RestParam> = getTableData(headersTableModel, RestParam.ParamType.HEADER)
    fun getExtractRules(): List<ExtractRule> {
        val list = ArrayList<ExtractRule>()
        for (i in 0 until extractTableModel.rowCount) {
            val v = extractTableModel.getValueAt(i, 0) as String
            val p = extractTableModel.getValueAt(i, 1) as String
            if (v.isNotBlank() && p.isNotBlank()) list.add(ExtractRule(v, p))
        }
        return list
    }
    fun getBodyType(): String = bodyTypeCombo.selectedItem as String
    fun getAuthData(): Pair<String, Map<String, String>> {
        val typeStr = authTypeCombo.selectedItem as String
        val typeCode = when (typeStr) { "Bearer Token" -> "bearer"; "Basic Auth" -> "basic"; "API Key" -> "apikey"; else -> "noauth" }
        val map = HashMap<String, String>()
        if (typeCode == "bearer") map["token"] = bearerTokenField.text
        if (typeCode == "basic") { map["username"] = basicUserField.text; map["password"] = String(basicPasswordField.password) }
        if (typeCode == "apikey") { map["key"] = apiKeyKeyField.text; map["value"] = apiKeyValueField.text; map["where"] = apiKeyLocationCombo.selectedItem as String }
        return typeCode to map
    }

    fun getBody(): String? {
        val type = bodyTypeCombo.selectedItem as String
        if (type == "none" || type == "multipart/form-data") return null

        if (type == "x-www-form-urlencoded") {
            val sb = StringBuilder()
            for (i in 0 until formTableModel.rowCount) {
                val k = formTableModel.getValueAt(i, 0) as? String ?: ""
                val v = formTableModel.getValueAt(i, 1) as? String ?: ""
                // 忽略 Type 列，只处理 Text
                if (k.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append("&")
                    sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append("=").append(URLEncoder.encode(v, StandardCharsets.UTF_8))
                }
            }
            return sb.toString()
        }
        return bodyEditor.text
    }

    // --- 数据加载 ---

    fun loadRequestData(
        params: List<RestParam>,
        headers: List<RestParam>,
        body: String?,
        authType: String,
        authContent: Map<String, String>,
        extractRules: List<ExtractRule>
        // 注意：SavedRequest 最好增加一个 multipartParams 字段来专门存，或者复用 params
        // 为了简单，我们这里假设 bodyType 已经正确设置
    ) {
        // ... (Params, Headers, Extract, Auth 加载逻辑保持不变) ...
        paramsTableModel.rowCount = 0
        params.forEach { paramsTableModel.addRow(arrayOf(it.name, it.value)) }
        if (paramsTableModel.rowCount == 0) paramsTableModel.addRow(arrayOf("", ""))

        headersTableModel.rowCount = 0
        headers.forEach { headersTableModel.addRow(arrayOf(it.name, it.value)) }
        if (headersTableModel.rowCount == 0) headersTableModel.addRow(arrayOf("", ""))

        extractTableModel.rowCount = 0
        extractRules.forEach { extractTableModel.addRow(arrayOf(it.variable, it.path)) }
        if (extractTableModel.rowCount == 0) extractTableModel.addRow(arrayOf("", ""))

        // Body Logic
        val currentType = bodyTypeCombo.selectedItem as String
        if (currentType == "x-www-form-urlencoded" && body != null) {
            formTableModel.rowCount = 0
            body.split("&").forEach { pair ->
                val parts = pair.split("=")
                if (parts.size == 2) {
                    try {
                        val k = URLDecoder.decode(parts[0], StandardCharsets.UTF_8)
                        val v = URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                        formTableModel.addRow(arrayOf(k, v, "Text")) // Default Text
                    } catch (e: Exception) {}
                }
            }
            if (formTableModel.rowCount == 0) formTableModel.addRow(arrayOf("", "", "Text"))
        } else if (currentType == "multipart/form-data") {
            // [Todo] 理想情况下 SavedRequest 应该存 multipart 列表
            // 这里暂且置空，或者你可以从 body 字符串反序列化（如果存了的话）
            formTableModel.rowCount = 0
            formTableModel.addRow(arrayOf("", "", "Text"))
        } else {
            setEditorText(bodyEditor, body ?: "")
        }

        // Auth Logic
        val typeStr = when (authType) { "bearer" -> "Bearer Token"; "basic" -> "Basic Auth"; "apikey" -> "API Key"; else -> "No Auth" }
        authTypeCombo.selectedItem = typeStr
        bearerTokenField.text = authContent["token"] ?: ""
        basicUserField.text = authContent["username"] ?: ""
        basicPasswordField.text = authContent["password"] ?: ""
        apiKeyKeyField.text = authContent["key"] ?: ""
        apiKeyValueField.text = authContent["value"] ?: ""
        apiKeyLocationCombo.selectedItem = authContent["where"] ?: "Header"
    }

    // ... (setBodyType, clearAll, styleTable, createTablePanel 保持不变) ...
    // 为了完整性，请保留原有的 setBodyType, clearAll, styleTable, createTablePanel

    fun setBodyType(type: String) { bodyTypeCombo.selectedItem = type }
    fun clearAll() {
        paramsTableModel.rowCount = 0; paramsTableModel.addRow(arrayOf("", ""))
        headersTableModel.rowCount = 0; headersTableModel.addRow(arrayOf("", ""))
        formTableModel.rowCount = 0; formTableModel.addRow(arrayOf("", "", "Text")) // Reset to Text
        extractTableModel.rowCount = 0; extractTableModel.addRow(arrayOf("", ""))
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

    private fun styleTable(table: JBTable) {
        table.rowHeight = 28
        table.setShowGrid(false)
        table.setShowHorizontalLines(true)
        table.gridColor = JBColor.border()
        table.intercellSpacing = java.awt.Dimension(0, 0)
        table.tableHeader.font = Font("JetBrains Mono", Font.BOLD, 12)
        table.font = Font("JetBrains Mono", Font.PLAIN, 13)
    }
    private fun createTablePanel(table: JBTable, model: DefaultTableModel): JPanel {
        val decorator = ToolbarDecorator.createDecorator(table)
            .setAddAction {
                // Form 表格默认加上 "Text" 类型
                if (model == formTableModel) model.addRow(arrayOf("", "", "Text"))
                else model.addRow(arrayOf("", ""))
            }
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

        val formPanel = createTablePanel(formTable, formTableModel)
        val editorPanel = bodyEditor

        bodyCardPanel.add(editorPanel, "editor")
        bodyCardPanel.add(formPanel, "form")

        bodyTypeCombo.addActionListener {
            val type = bodyTypeCombo.selectedItem as String
            val layout = bodyCardPanel.layout as CardLayout
            if (type == "x-www-form-urlencoded" || type == "multipart/form-data") {
                layout.show(bodyCardPanel, "form")
            } else {
                layout.show(bodyCardPanel, "editor")
                when (type) {
                    "raw (json)" -> bodyEditor.setFileType(JsonFileType.INSTANCE)
                    "raw (xml)" -> bodyEditor.setFileType(XmlFileType.INSTANCE)
                    "raw (text)" -> bodyEditor.setFileType(PlainTextFileType.INSTANCE)
                }
            }
            val isNone = type == "none"
            bodyEditor.isEnabled = !isNone
            formTable.isEnabled = !isNone
            if (isNone) setEditorText(bodyEditor, "")
        }

        panel.add(top, BorderLayout.NORTH)
        panel.add(bodyCardPanel, BorderLayout.CENTER)
        return panel
    }

    // ... (createAuthPanel, setupHeaderAutoCompletion, getTableData, setEditorText 保持不变) ...
    private fun createAuthPanel(): JPanel {
        val cardPanel = JPanel(CardLayout())
        cardPanel.add(JPanel(), "No Auth")
        cardPanel.add(panel { row("Token:") { cell(bearerTokenField).align(AlignX.FILL) } }, "Bearer Token")
        cardPanel.add(panel {
            row("Username:") { cell(basicUserField).align(AlignX.FILL) }
            row("Password:") { cell(basicPasswordField).align(AlignX.FILL) }
        }, "Basic Auth")
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

    // --- 自定义编辑器: 文件选择器 ---
    class FileChooserCellEditor(private val project: Project) : AbstractCellEditor(), TableCellEditor {
        private val textField = TextFieldWithBrowseButton()

        init {
            textField.addBrowseFolderListener(
                "Select File",
                "Choose file to upload",
                project,
                FileChooserDescriptorFactory.createSingleFileDescriptor()
            )
        }

        override fun getCellEditorValue(): Any = textField.text

        override fun getTableCellEditorComponent(table: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int): Component {
            textField.text = value as? String ?: ""
            return textField
        }
    }
}