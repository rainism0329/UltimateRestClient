package com.phil.rest.ui

import com.intellij.json.JsonLanguage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.LanguageTextField
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.phil.rest.model.RestParam
import com.phil.rest.model.SavedRequest
import com.phil.rest.service.HeaderStore
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
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
    private val authTypeCombo = ComboBox(arrayOf("No Auth", "Bearer Token", "Basic Auth"))
    private val bearerTokenField = JTextField()
    private val basicUserField = JTextField()
    private val basicPasswordField = JPasswordField()

    // --- Body ---
    private val bodyTypeCombo = ComboBox(arrayOf("none", "raw (json)"))
    private val bodyEditor = LanguageTextField(JsonLanguage.INSTANCE, project, "", false)

    init {
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

    // --- 对外暴露的数据获取/设置方法 ---

    fun getQueryParams(): List<RestParam> = getTableData(paramsTableModel, RestParam.ParamType.QUERY)
    fun getHeaders(): List<RestParam> = getTableData(headersTableModel, RestParam.ParamType.HEADER)

    fun getBody(): String? = if (bodyTypeCombo.selectedItem == "none") null else bodyEditor.text

    fun getAuthData(): Pair<String, Map<String, String>> {
        val typeStr = authTypeCombo.selectedItem as String
        val typeCode = when (typeStr) { "Bearer Token" -> "bearer"; "Basic Auth" -> "basic"; else -> "noauth" }
        val map = HashMap<String, String>()
        if (typeCode == "bearer") map["token"] = bearerTokenField.text
        if (typeCode == "basic") {
            map["username"] = basicUserField.text
            map["password"] = String(basicPasswordField.password)
        }
        return typeCode to map
    }

    // 设置数据（用于加载保存的请求或渲染API）
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
        setEditorText(bodyEditor, body ?: "")
        bodyTypeCombo.selectedItem = if (body.isNullOrEmpty()) "none" else "raw (json)"

        // Auth
        val typeStr = when (authType) { "bearer" -> "Bearer Token"; "basic" -> "Basic Auth"; else -> "No Auth" }
        authTypeCombo.selectedItem = typeStr
        bearerTokenField.text = authContent["token"] ?: ""
        basicUserField.text = authContent["username"] ?: ""
        basicPasswordField.text = authContent["password"] ?: ""
    }

    fun clearAll() {
        paramsTableModel.rowCount = 0; paramsTableModel.addRow(arrayOf("", ""))
        headersTableModel.rowCount = 0; headersTableModel.addRow(arrayOf("", ""))
        setEditorText(bodyEditor, "")
        bodyTypeCombo.selectedItem = "none"
        authTypeCombo.selectedItem = "No Auth"
        bearerTokenField.text = ""
        basicUserField.text = ""
        basicPasswordField.text = ""
        selectedIndex = 0
    }

    // --- 内部 UI 构建辅助方法 (基本照搬原有逻辑，稍微精简) ---

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

    private fun setEditorText(editor: LanguageTextField, text: String) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) WriteCommandAction.runWriteCommandAction(project) { editor.text = text }
        }
    }
}