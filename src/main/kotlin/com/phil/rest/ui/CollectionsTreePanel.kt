package com.phil.rest.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.phil.rest.model.CollectionNode
import com.phil.rest.service.CollectionService
import com.phil.rest.service.PostmanExportService
import com.phil.rest.service.PostmanImportService
import java.awt.BorderLayout
import java.awt.Component
import java.io.File
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
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
    private val searchField = SearchTextField(true)

    init {
        treeModel = DefaultTreeModel(DefaultMutableTreeNode("Root"))
        tree = Tree(treeModel).apply {
            isRootVisible = false
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            cellRenderer = CollectionTreeCellRenderer()
        }

        searchField.textEditor.emptyText.text = "Search collections..."
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { filterTree(searchField.text) }
        })

        tree.addTreeSelectionListener {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val userObject = node.userObject
            if (userObject is CollectionNode && !userObject.isFolder) {
                onRequestSelect(userObject)
            }
        }

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
                    if (searchField.text.isNullOrBlank()) reloadTree() else filterTree(searchField.text)
                }
            }
        }

        val exportSelectionAction = object : AnAction("Export This", "Export selected item to JSON", AllIcons.ToolbarDecorator.Export) {
            override fun actionPerformed(e: AnActionEvent) {
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                val userObject = node.userObject as? CollectionNode ?: return
                val listToExport = listOf(userObject)
                exportToFile(listToExport, "${userObject.name}.postman_collection.json")
            }
        }

        val exportAllAction = object : AnAction("Export All", "Export all collections", AllIcons.ToolbarDecorator.Export) {
            override fun actionPerformed(e: AnActionEvent) {
                val service = CollectionService.getInstance(project)
                if (service.rootNodes.isEmpty()) {
                    Messages.showWarningDialog("Nothing to export.", "Empty Collection")
                    return
                }
                exportToFile(service.rootNodes, "${project.name}_All_Collections.postman_collection.json")
            }
        }

        tree.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                val path = tree.getPathForLocation(x, y)
                if (path != null) {
                    tree.selectionPath = path
                    val actionGroup = DefaultActionGroup()
                    actionGroup.add(renameAction)
                    actionGroup.add(deleteAction)
                    actionGroup.addSeparator()
                    actionGroup.add(exportSelectionAction)
                    val popup = ActionManager.getInstance().createActionPopupMenu("CollectionTreePopup", actionGroup)
                    popup.component.show(comp, x, y)
                }
            }
        })

        val refreshAction = object : AnAction("Refresh", "Reload collections", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                searchField.text = ""
                reloadTree()
            }
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

        // [Fix] 使用 ToolbarDecorator.Import 图标
        val importAction = object : AnAction("Import Postman", "Import Collection or Dump (Backup)", AllIcons.ToolbarDecorator.Import) {
            override fun actionPerformed(e: AnActionEvent) {
                val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
                descriptor.title = "Select Postman JSON (Collection or Dump)"
                val file = FileChooser.chooseFile(descriptor, project, null) ?: return

                try {
                    val importer = PostmanImportService()
                    val resultMsg = importer.importDump(File(file.path), project)
                    reloadTree()
                    Messages.showInfoMessage(resultMsg, "Import Success")
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
        actionGroup.add(exportAllAction)

        val toolbar = ActionManager.getInstance().createActionToolbar("CollectionToolbar", actionGroup, true)
        toolbar.targetComponent = this

        val northPanel = JPanel(BorderLayout())
        northPanel.add(toolbar.component, BorderLayout.NORTH)
        val searchWrapper = JPanel(BorderLayout())
        searchWrapper.border = JBUI.Borders.empty(5)
        searchWrapper.add(searchField, BorderLayout.CENTER)
        northPanel.add(searchWrapper, BorderLayout.CENTER)

        setToolbar(northPanel)
        setContent(ScrollPaneFactory.createScrollPane(tree))

        reloadTree()
    }

    private fun filterTree(query: String) {
        val root = treeModel.root as DefaultMutableTreeNode
        root.removeAllChildren()

        val service = CollectionService.getInstance(project)

        if (query.isNullOrBlank()) {
            service.rootNodes.forEach { root.add(buildTreeNode(it)) }
        } else {
            val lowerQuery = query.lowercase().trim()
            service.rootNodes.forEach { node ->
                val filteredNode = buildFilteredNode(node, lowerQuery)
                if (filteredNode != null) root.add(filteredNode)
            }
        }
        treeModel.reload()
        if (!query.isNullOrBlank()) {
            for (i in 0 until tree.rowCount) tree.expandRow(i)
        }
    }

    private fun buildFilteredNode(node: CollectionNode, query: String): DefaultMutableTreeNode? {
        val isSelfMatch = nodeMatches(node, query)
        val matchedChildren = ArrayList<DefaultMutableTreeNode>()
        if (node.isFolder) {
            for (child in node.children) {
                val childNode = buildFilteredNode(child, query)
                if (childNode != null) matchedChildren.add(childNode)
            }
        }
        if (isSelfMatch || matchedChildren.isNotEmpty()) {
            val treeNode = DefaultMutableTreeNode(node)
            matchedChildren.forEach { treeNode.add(it) }
            return treeNode
        }
        return null
    }

    private fun nodeMatches(node: CollectionNode, query: String): Boolean {
        if (node.name.lowercase().contains(query)) return true
        if (!node.isFolder && node.request != null) {
            val req = node.request
            if (req.url != null && req.url.lowercase().contains(query)) return true
            if (req.method != null && req.method.lowercase().contains(query)) return true
        }
        return false
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
        if (searchField.text.isNullOrBlank()) {
            val root = treeModel.root as DefaultMutableTreeNode
            root.removeAllChildren()
            val service = CollectionService.getInstance(project)
            service.rootNodes.forEach { root.add(buildTreeNode(it)) }
            treeModel.reload()
        } else {
            filterTree(searchField.text)
        }
    }

    private fun buildTreeNode(collectionNode: CollectionNode): DefaultMutableTreeNode {
        val treeNode = DefaultMutableTreeNode(collectionNode)
        if (collectionNode.isFolder) {
            collectionNode.children.forEach { treeNode.add(buildTreeNode(it)) }
        }
        return treeNode
    }
}