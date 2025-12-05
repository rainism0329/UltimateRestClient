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
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.phil.rest.model.RestEnv
import com.phil.rest.service.EnvService
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.event.TableModelEvent
import javax.swing.table.DefaultTableModel

class EnvManagerDialog(private val project: Project) : DialogWrapper(true) {

    private val service = EnvService.getInstance(project)

    private val envListModel = DefaultListModel<RestEnv>()
    private val envList = JBList(envListModel)

    private val varTableModel = DefaultTableModel(arrayOf("Variable", "Value"), 0)
    private val varTable = JBTable(varTableModel)

    private var isLoading = false

    init {
        title = "Manage Environments"

        // 1. 先添加 Globals
        envListModel.addElement(service.globalEnv)

        // 2. 再添加普通环境
        service.envs.forEach { envListModel.addElement(it) }

        // 自定义 Renderer
        envList.cellRenderer = object : ColoredListCellRenderer<RestEnv>() {
            override fun customizeCellRenderer(
                list: JList<out RestEnv>, value: RestEnv?, index: Int, selected: Boolean, hasFocus: Boolean
            ) {
                if (value == service.globalEnv) {
                    // [Fix] 使用更通用的 Web 图标作为地球仪
                    icon = AllIcons.General.Web
                    append(value.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                } else {
                    icon = AllIcons.Nodes.Folder
                    append(value?.name ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }

        init()

        envList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                loadVarsForSelectedEnv()
            }
        }

        varTableModel.addTableModelListener { e ->
            if (!isLoading && e.type == TableModelEvent.UPDATE) {
                saveCurrentTableToEnv()
            }
        }

        // 默认选中 Globals
        envList.selectedIndex = 0
    }

    private fun loadVarsForSelectedEnv() {
        isLoading = true
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
                // 禁止删除 Globals
                if (selected == service.globalEnv) {
                    Messages.showWarningDialog("Cannot delete Global environment.", "Restricted")
                    return@setRemoveAction
                }

                if (selected != null && Messages.showYesNoDialog("Delete environment '${selected.name}'?", "Confirm Delete", Messages.getQuestionIcon()) == Messages.YES) {
                    service.removeEnv(selected)
                    envListModel.removeElement(selected)
                    if (service.selectedEnv == selected) {
                        service.selectedEnv = null
                    }
                }
            }
            .setRemoveActionUpdater { e ->
                envList.selectedValue != service.globalEnv
            }
            .addExtraAction(object : AnActionButton("Import Postman Env", AllIcons.Actions.Upload) {
                override fun actionPerformed(e: AnActionEvent) {
                    importPostmanEnv()
                }
            })

        val tableDecorator = ToolbarDecorator.createDecorator(varTable)
            .setAddAction {
                varTableModel.addRow(arrayOf("", ""))
                saveCurrentTableToEnv()
            }
            .setRemoveAction {
                if (varTable.isEditing) varTable.cellEditor?.stopCellEditing()
                if (varTable.selectedRow >= 0) {
                    varTableModel.removeRow(varTable.selectedRow)
                    saveCurrentTableToEnv()
                }
            }

        val splitter = OnePixelSplitter(false, 0.3f)
        splitter.firstComponent = listDecorator.createPanel()
        splitter.secondComponent = tableDecorator.createPanel()
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
                    if (node.path("enabled").asBoolean(true)) {
                        val key = node.path("key").asText()
                        val value = node.path("value").asText()
                        if (!key.isEmpty()) map[key] = value
                    }
                }
            }
            newEnv.variables = map

            service.addEnv(newEnv)
            envListModel.addElement(newEnv)
            envList.setSelectedValue(newEnv, true)
            Messages.showInfoMessage("Imported environment '$name'.", "Success")
        } catch (ex: Exception) {
            Messages.showErrorDialog("Import failed: ${ex.message}", "Error")
        }
    }
}