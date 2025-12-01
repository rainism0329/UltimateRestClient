package com.phil.rest.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.phil.rest.model.ApiDefinition
import com.phil.rest.service.SpringScannerService
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

class ApiTreePanel(
    private val project: Project,
    private val onApiSelect: (ApiDefinition) -> Unit
) : SimpleToolWindowPanel(true, true) {

    private val treeModel: DefaultTreeModel
    private val tree: Tree

    init {
        val rootNode = DefaultMutableTreeNode(project.name)
        treeModel = DefaultTreeModel(rootNode)
        tree = Tree(treeModel).apply {
            isRootVisible = true // 显示根节点 (项目名)
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            // *** 关键修改：使用统一的渲染器 ***
            cellRenderer = CollectionTreeCellRenderer()
        }

        // 点击事件
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                val userObject = node?.userObject
                if (userObject is ApiDefinition) {
                    onApiSelect(userObject)
                }
            }
        })

        // 工具栏
        val actionManager = ActionManager.getInstance()
        val refreshAction = object : AnAction("Refresh", "Scan project for APIs", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                refreshApiTree()
            }
        }
        val actionGroup = DefaultActionGroup(refreshAction)
        val toolbar = actionManager.createActionToolbar("ApiTreeToolbar", actionGroup, true)
        toolbar.targetComponent = this

        setContent(ScrollPaneFactory.createScrollPane(tree))
        setToolbar(toolbar.component)

        refreshApiTree()
    }

    private fun refreshApiTree() {
        val root = treeModel.root as DefaultMutableTreeNode

        // 异步扫描，避免卡顿 UI
        ReadAction.nonBlocking<List<ApiDefinition>> {
            val scanner = SpringScannerService(project)
            scanner.scanCurrentProject()
        }
            .inSmartMode(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { apis ->
                root.removeAllChildren()

                if (apis.isEmpty()) {
                    // 可以加个空节点提示
                } else {
                    // 按 Controller 分组
                    val groupedApis = apis.groupBy { it.className }
                    groupedApis.forEach { (controllerName, apiList) ->
                        // 简化类名：com.example.UserController -> UserController
                        val simpleName = controllerName.substringAfterLast('.')
                        val controllerNode = DefaultMutableTreeNode(simpleName)

                        apiList.forEach { api ->
                            val methodNode = DefaultMutableTreeNode(api)
                            controllerNode.add(methodNode)
                        }
                        root.add(controllerNode)
                    }
                }
                treeModel.reload()
                // 展开所有节点
                for (i in 0 until tree.rowCount) {
                    tree.expandRow(i)
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}