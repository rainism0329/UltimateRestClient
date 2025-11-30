package com.phil.rest.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.phil.rest.model.CollectionNode
import com.phil.rest.service.CollectionService
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

class SaveRequestDialog(
    private val project: Project,
    private val defaultName: String
) : DialogWrapper(true) {

    var requestName: String = defaultName
    var selectedFolder: CollectionNode? = null

    init {
        title = "Save Request"
        init()
    }

    override fun createCenterPanel(): JComponent {
        // 1. 获取所有文件夹节点
        val service = CollectionService.getInstance(project)
        val folders = getAllFolders(service.rootNodes)

        // 确保至少有一个默认文件夹
        if (folders.isEmpty()) {
            val root = service.getOrCreateDefaultRoot()
            folders.add(root)
        }

        selectedFolder = folders.firstOrNull()

        return panel {
            row("Name:") {
                textField()
                    .bindText(::requestName)
                    .focused()
                    .columns(30)
            }
            row("Collection:") {
                comboBox(DefaultComboBoxModel(folders.toTypedArray()))
                    .bindItem(::selectedFolder)
                    // 自定义渲染器，只显示文件夹名字
                    .component.renderer = javax.swing.DefaultListCellRenderer()
            }
        }
    }

    // 递归获取所有文件夹
    private fun getAllFolders(nodes: List<CollectionNode>): MutableList<CollectionNode> {
        val result = ArrayList<CollectionNode>()
        for (node in nodes) {
            if (node.isFolder) {
                result.add(node)
                result.addAll(getAllFolders(node.children))
            }
        }
        return result
    }
}