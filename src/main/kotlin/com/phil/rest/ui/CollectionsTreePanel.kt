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

    // [新增] 搜索框
    private val searchField = SearchTextField(true)

    init {
        treeModel = DefaultTreeModel(DefaultMutableTreeNode("Root"))
        tree = Tree(treeModel).apply {
            isRootVisible = false
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            cellRenderer = CollectionTreeCellRenderer()
        }

        // --- 搜索逻辑 ---
        searchField.textEditor.emptyText.text = "Search collections..."
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                filterTree(searchField.text)
            }
        })

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
                    // 删除后刷新，保持当前搜索状态
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
                    actionGroup.add(exportSelectionAction)
                    val popup = ActionManager.getInstance().createActionPopupMenu("CollectionTreePopup", actionGroup)
                    popup.component.show(comp, x, y)
                }
            }
        })

        // 4. 工具栏配置
        val actionManager = ActionManager.getInstance()

        val refreshAction = object : AnAction("Refresh", "Reload collections", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                // 刷新时清空搜索
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
        actionGroup.add(exportAllAction)

        val toolbar = actionManager.createActionToolbar("CollectionToolbar", actionGroup, true)
        toolbar.targetComponent = this

        // --- 组装界面 ---
        // 顶部面板：上面是工具栏，下面是搜索框
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

    // --- 过滤核心逻辑 ---

    private fun filterTree(query: String) {
        val root = treeModel.root as DefaultMutableTreeNode
        root.removeAllChildren()

        val service = CollectionService.getInstance(project)

        if (query.isNullOrBlank()) {
            service.rootNodes.forEach { root.add(buildTreeNode(it)) }
        } else {
            val lowerQuery = query.lowercase().trim()
            // 递归构建过滤后的树
            service.rootNodes.forEach { node ->
                val filteredNode = buildFilteredNode(node, lowerQuery)
                if (filteredNode != null) {
                    root.add(filteredNode)
                }
            }
        }

        treeModel.reload()

        // 如果有搜索内容，默认展开所有匹配项，方便查看
        if (!query.isNullOrBlank()) {
            for (i in 0 until tree.rowCount) {
                tree.expandRow(i)
            }
        }
    }

    // 递归过滤函数
    private fun buildFilteredNode(node: CollectionNode, query: String): DefaultMutableTreeNode? {
        val isSelfMatch = nodeMatches(node, query)

        // 递归处理子节点
        val matchedChildren = ArrayList<DefaultMutableTreeNode>()
        if (node.isFolder) {
            for (child in node.children) {
                val childNode = buildFilteredNode(child, query)
                if (childNode != null) {
                    matchedChildren.add(childNode)
                }
            }
        }

        // 逻辑：如果自己匹配，或者有子节点匹配，就保留这个节点
        // 注意：如果自己匹配但子节点不匹配，是否保留所有子节点？
        // 这里采用标准逻辑：只显示匹配的路径。
        // 如果文件夹名字匹配，通常希望看到它下面的东西，但在严格过滤模式下，
        // 我们只展示命中的叶子节点及其路径，或者命中的文件夹。

        if (isSelfMatch || matchedChildren.isNotEmpty()) {
            val treeNode = DefaultMutableTreeNode(node)
            // 如果自己匹配了，是不是要加入所有孩子？
            // 现在的逻辑是：即使自己匹配了，也只加入匹配的孩子。
            // 这样能过滤得更精准。如果你想“搜到文件夹就显示全部”，需要改这里的逻辑。
            matchedChildren.forEach { treeNode.add(it) }
            return treeNode
        }

        return null
    }

    private fun nodeMatches(node: CollectionNode, query: String): Boolean {
        // 匹配名字
        if (node.name.lowercase().contains(query)) return true

        // 如果是请求，额外匹配 URL 和 Method
        if (!node.isFolder && node.request != null) {
            val req = node.request
            if (req.url != null && req.url.lowercase().contains(query)) return true
            if (req.method != null && req.method.lowercase().contains(query)) return true
        }
        return false
    }

    // --- 原有逻辑 ---

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
        // 只有当搜索框为空时才全量加载，避免刷新打断搜索状态
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