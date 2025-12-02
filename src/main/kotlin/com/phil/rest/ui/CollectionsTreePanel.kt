package com.phil.rest.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.treeStructure.Tree
import com.phil.rest.model.CollectionNode
import com.phil.rest.service.CollectionService
import com.phil.rest.service.PostmanExportService
import com.phil.rest.service.PostmanImportService
import java.awt.Component
import java.io.File
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

        tree.addTreeSelectionListener {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val userObject = node.userObject
            if (userObject is CollectionNode && !userObject.isFolder) {
                onRequestSelect(userObject)
            }
        }

        // --- Actions 定义 ---

        val renameAction = object : AnAction("Rename") {
            override fun actionPerformed(e: AnActionEvent) {
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                val userObject = node.userObject as? CollectionNode ?: return
                val newName = Messages.showInputDialog(project, "Enter new name:", "Rename", Messages.getQuestionIcon(), userObject.name, null)
                if (!newName.isNullOrBlank()) {
                    userObject.name = newName
                    treeModel.nodeChanged(node)
                }
            }
        }

        val deleteAction = object : AnAction("Delete", "Delete item", AllIcons.Actions.Cancel) {
            override fun actionPerformed(e: AnActionEvent) {
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                val userObject = node.userObject as? CollectionNode ?: return
                if (Messages.showYesNoDialog(project, "Are you sure you want to delete '${userObject.name}'?", "Delete", Messages.getQuestionIcon()) == Messages.YES) {
                    val service = CollectionService.getInstance(project)
                    removeFromService(service.rootNodes, userObject)
                    reloadTree()
                }
            }
        }

        // [Unified] 1. Export Selection (用于右键菜单)
        val exportSelectionAction = object : AnAction("Export This", "Export selected item to JSON", AllIcons.ToolbarDecorator.Export) {
            override fun actionPerformed(e: AnActionEvent) {
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                val userObject = node.userObject as? CollectionNode ?: return

                val listToExport = listOf(userObject)
                exportToFile(listToExport, "${userObject.name}.postman_collection.json")
            }
        }

        // [Unified] 2. Export All (用于工具栏)
        val exportAllAction = object : AnAction("Export All", "Export all collections", AllIcons.Actions.MenuSaveall) {
            override fun actionPerformed(e: AnActionEvent) {
                val service = CollectionService.getInstance(project)
                if (service.rootNodes.isEmpty()) {
                    Messages.showWarningDialog("Nothing to export.", "Empty Collection")
                    return
                }
                exportToFile(service.rootNodes, "${project.name}_All_Collections.postman_collection.json")
            }
        }

        // 3. 右键菜单配置
        tree.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                val path = tree.getPathForLocation(x, y)
                if (path != null) {
                    tree.selectionPath = path
                    val actionGroup = DefaultActionGroup()
                    actionGroup.add(renameAction)
                    actionGroup.add(deleteAction)
                    actionGroup.addSeparator()
                    actionGroup.add(exportSelectionAction) // Export Selection

                    val popup = ActionManager.getInstance().createActionPopupMenu("CollectionTreePopup", actionGroup)
                    popup.component.show(comp, x, y)
                }
            }
        })

        // 4. 工具栏配置
        val actionManager = ActionManager.getInstance()

        val refreshAction = object : AnAction("Refresh", "Reload collections", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) { reloadTree() }
        }

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

        val importAction = object : AnAction("Import Postman", "Import from JSON file", AllIcons.Actions.Upload) {
            override fun actionPerformed(e: AnActionEvent) {
                val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
                descriptor.title = "Select Postman Collection JSON"
                val file = FileChooser.chooseFile(descriptor, project, null) ?: return
                try {
                    val importer = PostmanImportService()
                    val importedNodes = importer.importCollection(File(file.path))
                    if (importedNodes.isEmpty()) {
                        Messages.showWarningDialog("No items found in the collection.", "Import Failed")
                        return
                    }
                    val service = CollectionService.getInstance(project)
                    val importRoot = CollectionNode.createFolder(file.nameWithoutExtension)
                    importRoot.children = importedNodes
                    service.addRootNode(importRoot)
                    reloadTree()
                    Messages.showInfoMessage("Successfully imported ${importedNodes.size} items.", "Import Success")
                } catch (ex: Exception) {
                    Messages.showErrorDialog("Error parsing Postman file: ${ex.message}", "Import Error")
                }
            }
        }

        val actionGroup = DefaultActionGroup()
        actionGroup.add(refreshAction)
        actionGroup.add(addFolderAction)
        actionGroup.add(addRequestAction)
        actionGroup.addSeparator()
        actionGroup.add(importAction)
        actionGroup.add(exportAllAction) // Export All

        val toolbar = actionManager.createActionToolbar("CollectionToolbar", actionGroup, true)
        toolbar.targetComponent = this

        setContent(ScrollPaneFactory.createScrollPane(tree))
        setToolbar(toolbar.component)

        reloadTree()
    }

    private fun exportToFile(nodes: List<CollectionNode>, defaultFileName: String) {
        val descriptor = FileSaverDescriptor("Export Collection", "Save as JSON file", "json")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(null as VirtualFile?, defaultFileName)

        if (wrapper != null) {
            try {
                val exporter = PostmanExportService()
                exporter.exportCollections(nodes, wrapper.file)
                Messages.showInfoMessage("Successfully exported to ${wrapper.file.name}.", "Export Success")
            } catch (ex: Exception) {
                Messages.showErrorDialog("Export failed: ${ex.message}", "Error")
            }
        }
    }

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