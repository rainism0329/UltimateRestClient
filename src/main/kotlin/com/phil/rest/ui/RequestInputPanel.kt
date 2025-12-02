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
import com.phil.rest.model.ExtractRule
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
    private val apiKeyKeyField = JTextField()
    private val apiKeyValueField = JTextField()
    private val apiKeyLocationCombo = ComboBox(arrayOf("Header", "Query Params"))

    // --- Body ---
    private val bodyTypeCombo = ComboBox(arrayOf("none", "raw (json)", "raw (text)", "raw (xml)", "x-www-form-urlencoded"))
    private val bodyEditor = EditorTextField("", project, JsonFileType.INSTANCE)
    private val formTableModel = DefaultTableModel(arrayOf("Key", "Value"), 0)
    private val formTable = JBTable(formTableModel)
    private val bodyCardPanel = JPanel(CardLayout())

    // --- [新增] Extract ---
    private val extractTableModel = DefaultTableModel(arrayOf("Variable Name", "JSON Path (e.g. data.token)"), 0)
    private val extractTable = JBTable(extractTableModel)

    init {
        // UI 美化
        styleTable(paramsTable)
        styleTable(headersTable)
        styleTable(formTable)
        styleTable(extractTable)

        // 1. Params
        addTab("Params", createTablePanel(paramsTable, paramsTableModel))
        // 2. Auth
        addTab("Auth", createAuthPanel())
        // 3. Headers
        setupHeaderAutoCompletion()
        addTab("Headers", createTablePanel(headersTable, headersTableModel))
        // 4. Body
        addTab("Body", createBodyPanel())
        // 5. [新增] Extract Tab
        addTab("Extract", createTablePanel(extractTable, extractTableModel))
    }

    // --- Data Getters ---

    fun getQueryParams(): List<RestParam> = getTableData(paramsTableModel, RestParam.ParamType.QUERY)
    fun getHeaders(): List<RestParam> = getTableData(headersTableModel, RestParam.ParamType.HEADER)

    // [新增] 获取提取规则
    fun getExtractRules(): List<ExtractRule> {
        val list = ArrayList<ExtractRule>()
        for (i in 0 until extractTableModel.rowCount) {
            val v = extractTableModel.getValueAt(i, 0) as String
            val p = extractTableModel.getValueAt(i, 1) as String
            if (v.isNotBlank() && p.isNotBlank()) {
                list.add(ExtractRule(v, p))
            }
        }
        return list
    }

    fun getBody(): String? {
        val type = bodyTypeCombo.selectedItem as String
        if (type == "none") return null
        if (type == "x-www-form-urlencoded") {
            val sb = StringBuilder()
            for (i in 0 until formTableModel.rowCount) {
                val k = formTableModel.getValueAt(i, 0) as String
                val v = formTableModel.getValueAt(i, 1) as String
                if (k.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append("&")
                    sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                        .append("=")
                        .append(URLEncoder.encode(v, StandardCharsets.UTF_8))
                }
            }
            return sb.toString()
        }
        return bodyEditor.text
    }

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

    // --- Data Loaders ---

    // [修改] 增加 extractRules 参数
    fun loadRequestData(
        params: List<RestParam>,
        headers: List<RestParam>,
        body: String?,
        authType: String,
        authContent: Map<String, String>,
        extractRules: List<ExtractRule> // New Param
    ) {
        // Params
        paramsTableModel.rowCount = 0
        params.forEach { paramsTableModel.addRow(arrayOf(it.name, it.value)) }
        if (paramsTableModel.rowCount == 0) paramsTableModel.addRow(arrayOf("", ""))

        // Headers
        headersTableModel.rowCount = 0
        headers.forEach { headersTableModel.addRow(arrayOf(it.name, it.value)) }
        if (headersTableModel.rowCount == 0) headersTableModel.addRow(arrayOf("", ""))

        // [新增] Extract Rules
        extractTableModel.rowCount = 0
        extractRules.forEach { extractTableModel.addRow(arrayOf(it.variable, it.path)) }
        if (extractTableModel.rowCount == 0) extractTableModel.addRow(arrayOf("", ""))

        // Body
        val currentType = bodyTypeCombo.selectedItem as String
        if (currentType == "x-www-form-urlencoded" && body != null) {
            formTableModel.rowCount = 0
            body.split("&").forEach { pair ->
                val parts = pair.split("=")
                if (parts.size == 2) {
                    try {
                        val k = URLDecoder.decode(parts[0], StandardCharsets.UTF_8)
                        val v = URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                        formTableModel.addRow(arrayOf(k, v))
                    } catch (e: Exception) { }
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

    fun setBodyType(type: String) { bodyTypeCombo.selectedItem = type }

    fun clearAll() {
        paramsTableModel.rowCount = 0; paramsTableModel.addRow(arrayOf("", ""))
        headersTableModel.rowCount = 0; headersTableModel.addRow(arrayOf("", ""))
        formTableModel.rowCount = 0; formTableModel.addRow(arrayOf("", ""))
        // [新增] Clear Extract
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

    // --- UI Helpers ---

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

        val formPanel = createTablePanel(formTable, formTableModel)
        val editorPanel = bodyEditor

        bodyCardPanel.add(editorPanel, "editor")
        bodyCardPanel.add(formPanel, "form")

        bodyTypeCombo.addActionListener {
            val type = bodyTypeCombo.selectedItem as String
            val layout = bodyCardPanel.layout as CardLayout
            if (type == "x-www-form-urlencoded") {
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
}