package com.phil.rest.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.treeStructure.Tree
import com.phil.rest.model.CollectionNode
import com.phil.rest.service.CollectionService
import java.awt.Component
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

class CollectionsTreePanel(
    private val project: Project,
    private val onRequestSelect: (CollectionNode) -> Unit,
    private val onCreateNew: () -> Unit
) : SimpleToolWindowPanel(true, true) {

    private val treeModel: DefaultTreeModel
    private val tree: Tree

    init {
        treeModel = DefaultTreeModel(DefaultMutableTreeNode("Root"))
        tree = Tree(treeModel).apply {
            isRootVisible = false
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            cellRenderer = CollectionTreeCellRenderer()
        }

        // 1. 左键点击选择
        tree.addTreeSelectionListener {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val userObject = node.userObject
            if (userObject is CollectionNode && !userObject.isFolder) {
                onRequestSelect(userObject)
            }
        }

        // 2. 右键菜单 (解决重命名和删除问题)
        tree.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                val path = tree.getPathForLocation(x, y)
                if (path != null) {
                    tree.selectionPath = path // 选中当前右键的节点
                    val node = path.lastPathComponent as DefaultMutableTreeNode
                    val userObject = node.userObject as? CollectionNode

                    // 创建右键菜单
                    val actionGroup = DefaultActionGroup()
                    actionGroup.add(object : AnAction("Rename") {
                        override fun actionPerformed(e: AnActionEvent) {
                            val newName = Messages.showInputDialog(project, "Enter new name:", "Rename", Messages.getQuestionIcon(), userObject?.name, null)
                            if (!newName.isNullOrBlank() && userObject != null) {
                                userObject.name = newName
                                treeModel.nodeChanged(node) // 刷新节点显示
                            }
                        }
                    })

                    actionGroup.add(object : AnAction("Delete", "Delete item", AllIcons.Actions.Cancel) {
                        override fun actionPerformed(e: AnActionEvent) {
                            if (Messages.showYesNoDialog(project, "Are you sure you want to delete '${userObject?.name}'?", "Delete", Messages.getQuestionIcon()) == Messages.YES) {
                                // 从数据结构中移除 (这里简化处理，实际应该递归查找父节点移除)
                                val service = CollectionService.getInstance(project)
                                removeFromService(service.rootNodes, userObject)
                                reloadTree()
                            }
                        }
                    })

                    val popup = ActionManager.getInstance().createActionPopupMenu("CollectionTreePopup", actionGroup)
                    popup.component.show(comp, x, y)
                }
            }
        })

        // --- 顶部工具栏 ---
        val actionManager = ActionManager.getInstance()
        val refreshAction = object : AnAction("Refresh", "Reload collections", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) { reloadTree() }
        }

        // 修改：新建文件夹时弹窗命名
        val addFolderAction = object : AnAction("New Folder", "Create new folder", AllIcons.Nodes.Folder) {
            override fun actionPerformed(e: AnActionEvent) {
                val folderName = Messages.showInputDialog(project, "Enter folder name:", "New Folder", Messages.getQuestionIcon(), "New Folder", null)
                if (!folderName.isNullOrBlank()) {
                    val service = CollectionService.getInstance(project)
                    service.addRootNode(CollectionNode.createFolder(folderName))
                    reloadTree()
                }
            }
        }

        val addRequestAction = object : AnAction("New Request", "Create empty request", AllIcons.FileTypes.Json) {
            override fun actionPerformed(e: AnActionEvent) { onCreateNew() }
        }

        val actionGroup = DefaultActionGroup()
        actionGroup.add(refreshAction)
        actionGroup.add(addFolderAction)
        actionGroup.addSeparator()
        actionGroup.add(addRequestAction)

        val toolbar = actionManager.createActionToolbar("CollectionToolbar", actionGroup, true)
        toolbar.targetComponent = this

        setContent(ScrollPaneFactory.createScrollPane(tree))
        setToolbar(toolbar.component)

        reloadTree()
    }

    // 简单的递归删除辅助方法
    private fun removeFromService(nodes: MutableList<CollectionNode>, target: CollectionNode?): Boolean {
        if (target == null) return false
        if (nodes.remove(target)) return true
        for (node in nodes) {
            if (node.isFolder && removeFromService(node.children, target)) return true
        }
        return false
    }

    fun reloadTree() {
        val root = treeModel.root as DefaultMutableTreeNode
        root.removeAllChildren()
        val service = CollectionService.getInstance(project)
        service.rootNodes.forEach { root.add(buildTreeNode(it)) }
        treeModel.reload()
    }

    private fun buildTreeNode(collectionNode: CollectionNode): DefaultMutableTreeNode {
        val treeNode = DefaultMutableTreeNode(collectionNode)
        if (collectionNode.isFolder) {
            collectionNode.children.forEach { treeNode.add(buildTreeNode(it)) }
        }
        return treeNode
    }
}