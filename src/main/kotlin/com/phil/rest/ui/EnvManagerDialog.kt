package com.phil.rest.ui

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.AnActionButton
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.phil.rest.model.RestEnv
import com.phil.rest.service.EnvService
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.event.TableModelEvent
import javax.swing.table.DefaultTableModel

class EnvManagerDialog(private val project: Project) : DialogWrapper(true) {

    private val service = EnvService.getInstance(project)

    // 左侧：环境列表
    private val envListModel = DefaultListModel<RestEnv>()
    private val envList = JBList(envListModel)

    // 右侧：变量表格 (Key, Value)
    private val varTableModel = DefaultTableModel(arrayOf("Variable", "Value"), 0)
    private val varTable = JBTable(varTableModel)

    // 标记是否正在加载数据，防止监听器循环触发
    private var isLoading = false

    init {
        title = "Manage Environments"

        // 1. 加载所有环境到列表
        service.envs.forEach { envListModel.addElement(it) }

        init() // 构建 UI

        // 2. 监听左侧列表选择，刷新右侧表格
        envList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                loadVarsForSelectedEnv()
            }
        }

        // 3. 监听右侧表格修改，实时同步回对象
        varTableModel.addTableModelListener { e ->
            if (!isLoading && e.type == TableModelEvent.UPDATE) {
                saveCurrentTableToEnv()
            }
        }

        // 默认选中上次使用的，或者第一个
        if (!envListModel.isEmpty) {
            val lastSelected = service.selectedEnv
            if (lastSelected != null && service.envs.contains(lastSelected)) {
                envList.setSelectedValue(lastSelected, true)
            } else {
                envList.selectedIndex = 0
            }
        }
    }

    private fun loadVarsForSelectedEnv() {
        isLoading = true
        // 停止之前的编辑
        if (varTable.isEditing) varTable.cellEditor.stopCellEditing()

        varTableModel.rowCount = 0
        val selectedEnv = envList.selectedValue ?: return

        selectedEnv.variables.forEach { (k, v) ->
            varTableModel.addRow(arrayOf(k, v))
        }
        isLoading = false
    }

    private fun saveCurrentTableToEnv() {
        val selectedEnv = envList.selectedValue ?: return

        val newMap = HashMap<String, String>()
        for (i in 0 until varTableModel.rowCount) {
            val k = varTableModel.getValueAt(i, 0) as? String
            val v = varTableModel.getValueAt(i, 1) as? String
            if (!k.isNullOrBlank()) {
                newMap[k] = v ?: ""
            }
        }
        selectedEnv.variables = newMap
    }

    override fun createCenterPanel(): JComponent {
        // --- 左侧面板配置 (环境列表) ---
        val listDecorator = ToolbarDecorator.createDecorator(envList)
            .setAddAction {
                val name = Messages.showInputDialog("Environment Name:", "New Environment", null)
                if (!name.isNullOrBlank()) {
                    val newEnv = RestEnv(name)
                    service.addEnv(newEnv)
                    envListModel.addElement(newEnv)
                    envList.setSelectedValue(newEnv, true)
                }
            }
            .setRemoveAction {
                val selected = envList.selectedValue
                if (selected != null && Messages.showYesNoDialog("Delete environment '${selected.name}'?", "Confirm Delete", Messages.getQuestionIcon()) == Messages.YES) {
                    service.removeEnv(selected)
                    envListModel.removeElement(selected)
                    // 如果删除了当前选中的，重置选中状态
                    if (service.selectedEnv == selected) {
                        service.selectedEnv = null
                    }
                }
            }
            // *** 核心功能：导入 Postman Environment ***
            .addExtraAction(object : AnActionButton("Import Postman Env", AllIcons.Actions.Upload) {
                override fun actionPerformed(e: AnActionEvent) {
                    importPostmanEnv()
                }
            })

        // --- 右侧面板配置 (变量表格) ---
        val tableDecorator = ToolbarDecorator.createDecorator(varTable)
            .setAddAction {
                varTableModel.addRow(arrayOf("", ""))
                saveCurrentTableToEnv() // 添加空行也触发保存，以便实时更新
            }
            .setRemoveAction {
                if (varTable.isEditing) varTable.cellEditor?.stopCellEditing()
                if (varTable.selectedRow >= 0) {
                    varTableModel.removeRow(varTable.selectedRow)
                    saveCurrentTableToEnv()
                }
            }

        // --- 组装 SplitPane ---
        val splitter = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listDecorator.createPanel(), tableDecorator.createPanel())
        splitter.dividerLocation = 200
        splitter.preferredSize = JBUI.size(700, 450)

        return splitter
    }

    private fun importPostmanEnv() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
        descriptor.title = "Select Postman Environment JSON"

        val file = FileChooser.chooseFile(descriptor, project, null) ?: return

        try {
            val mapper = ObjectMapper()
            val root = mapper.readTree(file.inputStream)

            val name = root.get("name")?.asText() ?: "Imported Env"
            val valuesNode = root.get("values")

            val newEnv = RestEnv(name)
            val map = HashMap<String, String>()

            if (valuesNode != null && valuesNode.isArray) {
                for (node in valuesNode) {
                    // Postman 导出格式中，enabled 默认为 true
                    val enabled = node.get("enabled")?.asBoolean(true) ?: true
                    if (enabled) {
                        val key = node.get("key")?.asText() ?: ""
                        val value = node.get("value")?.asText() ?: ""
                        if (key.isNotBlank()) {
                            map[key] = value
                        }
                    }
                }
            }
            newEnv.variables = map

            // 保存并选中
            service.addEnv(newEnv)
            envListModel.addElement(newEnv)
            envList.setSelectedValue(newEnv, true)

            Messages.showInfoMessage("Imported environment '$name' with ${map.size} variables.", "Success")

        } catch (ex: Exception) {
            Messages.showErrorDialog("Failed to import Postman environment: ${ex.message}", "Import Error")
        }
    }
}