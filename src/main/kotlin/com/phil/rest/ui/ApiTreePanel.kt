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
            isRootVisible = true
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            cellRenderer = ApiTreeCellRenderer()
        }

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                val userObject = node?.userObject
                if (userObject is ApiDefinition) {
                    onApiSelect(userObject)
                }
            }
        })

        val actionManager = ActionManager.getInstance()
        val refreshAction = object : AnAction("Refresh", "Scan project for APIs", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                refreshApiTree()
            }
        }
        val actionGroup = DefaultActionGroup(refreshAction)
        val toolbar = actionManager.createActionToolbar("ApiTreeToolbar", actionGroup, true)
        toolbar.targetComponent = this

        val scrollPane = ScrollPaneFactory.createScrollPane(tree)
        setContent(scrollPane)
        setToolbar(toolbar.component)

        refreshApiTree()
    }

    /**
     * 核心修复：使用 ReadAction.nonBlocking 异步处理
     */
    private fun refreshApiTree() {
        // 显示加载状态（可选，但体验更好）
        val root = treeModel.root as DefaultMutableTreeNode
        root.removeAllChildren()
        root.add(DefaultMutableTreeNode("Scanning..."))
        treeModel.reload()

        // 1. 在后台线程执行扫描任务
        ReadAction.nonBlocking<List<ApiDefinition>> {
            // 这块代码会在后台线程执行，不会卡顿 UI
            val scanner = SpringScannerService(project)
            scanner.scanCurrentProject()
        }
            .inSmartMode(project) // *** 关键修复 ***：告诉 IDEA 只有在索引建好(Smart Mode)后才执行
            .finishOnUiThread(ModalityState.defaultModalityState()) { apis ->
                // 2. 扫描完成后，自动回到 UI 线程 (EDT) 更新界面
                renderTree(apis)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun renderTree(apis: List<ApiDefinition>) {
        val root = treeModel.root as DefaultMutableTreeNode
        root.removeAllChildren() // 清除 "Scanning..."

        if (apis.isEmpty()) {
            root.add(DefaultMutableTreeNode("No APIs found"))
        } else {
            // 按 Controller 分组
            val groupedApis = apis.groupBy { it.className }
            groupedApis.forEach { (controllerName, apiList) ->
                // 只显示类名简单部分，不要全限定名太长了
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
}