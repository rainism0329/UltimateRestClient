package com.phil.rest.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.phil.rest.model.ApiDefinition
import com.phil.rest.service.PostmanExportService
import com.phil.rest.service.SpringScannerService
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

class ApiTreePanel(
    private val project: Project,
    private val onApiSelect: (ApiDefinition) -> Unit
) : SimpleToolWindowPanel(true, true), DataProvider {

    private val treeModel: DefaultTreeModel
    private val tree: Tree

    // [新增] 搜索组件
    private val searchField = SearchTextField(true)

    // [新增] 数据缓存 (Source of Truth)
    private var allApis: List<ApiDefinition> = emptyList()

    init {
        val rootNode = DefaultMutableTreeNode(project.name)
        treeModel = DefaultTreeModel(rootNode)

        // [优化] 使用 CollectionTreeCellRenderer (假设你已经应用了最新的 Outline 风格渲染器)
        // 这样 Live 和 Collections 列表风格就统一了
        tree = Tree(treeModel).apply {
            isRootVisible = true
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            cellRenderer = CollectionTreeCellRenderer()
        }

        // --- 搜索逻辑 ---
        searchField.textEditor.emptyText.text = "Search APIs (e.g. 'user', 'POST')..."
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                filterTree(searchField.text)
            }
        })

        // --- 布局组装 ---
        // 将搜索框放在顶部，带一点内边距
        val topPanel = JPanel(BorderLayout())
        topPanel.border = JBUI.Borders.empty(5)
        topPanel.add(searchField, BorderLayout.CENTER)

        // 1. 鼠标监听
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val userObject = node.userObject

                if (userObject is ApiDefinition) {
                    if (e.button == MouseEvent.BUTTON1 && !e.isControlDown && e.clickCount == 1) {
                        onApiSelect(userObject)
                    }
                    if ((e.clickCount == 2 && e.button == MouseEvent.BUTTON1) ||
                        (e.clickCount == 1 && e.button == MouseEvent.BUTTON1 && e.isControlDown)) {
                        navigateToSource(userObject)
                    }
                }
            }
        })

        // 2. 键盘监听
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                    val userObject = node?.userObject
                    if (userObject is ApiDefinition) {
                        navigateToSource(userObject)
                        e.consume()
                    }
                }
            }
        })

        // --- Actions ---
        val jumpAction = object : AnAction("Jump to Source", "Navigate to method declaration", AllIcons.Actions.EditSource) {
            override fun actionPerformed(e: AnActionEvent) {
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                val userObject = node?.userObject as? ApiDefinition ?: return
                navigateToSource(userObject)
            }
        }

        val refreshAction = object : AnAction("Refresh", "Scan project for APIs", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) { refreshApiTree() }
        }

        val exportAllAction = object : AnAction("Export All", "Export all scanned APIs to Postman", AllIcons.Actions.MenuSaveall) {
            override fun actionPerformed(e: AnActionEvent) {
                if (allApis.isEmpty()) {
                    Messages.showWarningDialog("No APIs to export.", "Export Failed")
                    return
                }
                performExport("${project.name}_All_APIs", allApis)
            }
        }

        val exportSelectionAction = object : AnAction("Export This", "Export selected item to Postman", AllIcons.ToolbarDecorator.Export) {
            override fun actionPerformed(e: AnActionEvent) {
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                val apisToExport = ArrayList<ApiDefinition>()
                var exportName = "Exported_APIs"

                if (node.isRoot) {
                    exportAllAction.actionPerformed(e)
                    return
                } else if (node.userObject is String) {
                    exportName = node.userObject as String
                    for (i in 0 until node.childCount) {
                        val child = node.getChildAt(i) as DefaultMutableTreeNode
                        val api = child.userObject as? ApiDefinition
                        if (api != null) apisToExport.add(api)
                    }
                } else if (node.userObject is ApiDefinition) {
                    val api = node.userObject as ApiDefinition
                    apisToExport.add(api)
                    exportName = api.methodName
                }

                if (apisToExport.isNotEmpty()) {
                    performExport(exportName, apisToExport)
                }
            }
        }

        // 3. 右键菜单
        val rightClickActionGroup = DefaultActionGroup()
        rightClickActionGroup.add(jumpAction)
        rightClickActionGroup.addSeparator()
        rightClickActionGroup.add(exportSelectionAction)
        rightClickActionGroup.addSeparator()
        rightClickActionGroup.add(refreshAction)

        tree.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: java.awt.Component?, x: Int, y: Int) {
                val path = tree.getPathForLocation(x, y)
                if (path != null) {
                    tree.selectionPath = path
                    val popup = ActionManager.getInstance().createActionPopupMenu("ApiTreePopup", rightClickActionGroup)
                    popup.component.show(comp, x, y)
                }
            }
        })

        // 4. 工具栏
        val toolbarActionGroup = DefaultActionGroup()
        toolbarActionGroup.add(refreshAction)
        toolbarActionGroup.add(jumpAction)
        toolbarActionGroup.addSeparator()
        toolbarActionGroup.add(exportAllAction)

        val toolbar = ActionManager.getInstance().createActionToolbar("ApiTreeToolbar", toolbarActionGroup, true)
        toolbar.targetComponent = this

        // 组装最终界面
        // Top: Toolbar + SearchField
        // Center: Tree
        val northPanel = JPanel(BorderLayout())
        northPanel.add(toolbar.component, BorderLayout.NORTH)
        northPanel.add(topPanel, BorderLayout.CENTER)

        setToolbar(northPanel) // 使用 setToolbar 设置顶部区域
        setContent(ScrollPaneFactory.createScrollPane(tree))

        DataManager.registerDataProvider(this, this)

        // 初始化加载
        refreshApiTree()
    }

    // --- 核心逻辑 ---

    private fun refreshApiTree() {
        val root = treeModel.root as DefaultMutableTreeNode
        root.removeAllChildren()
        root.userObject = "Scanning..."
        treeModel.reload()

        ReadAction.nonBlocking<List<ApiDefinition>> {
            val scanner = SpringScannerService(project)
            scanner.scanCurrentProject()
        }
            .inSmartMode(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { apis ->
                // 1. 更新缓存
                allApis = apis

                // 2. 根据当前搜索框内容构建树
                filterTree(searchField.text)

                // 3. 恢复 Root 名字
                root.userObject = project.name
                treeModel.reload()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * 根据关键词过滤并重建树
     */
    private fun filterTree(query: String) {
        val root = treeModel.root as DefaultMutableTreeNode
        root.removeAllChildren()

        if (allApis.isEmpty()) {
            root.add(DefaultMutableTreeNode("No APIs found"))
            treeModel.reload()
            return
        }

        val lowerQuery = query.lowercase().trim()

        // 过滤逻辑：匹配 URL、Method、MethodName 或 ClassName
        val filteredApis = if (lowerQuery.isEmpty()) {
            allApis
        } else {
            allApis.filter { api ->
                api.url.lowercase().contains(lowerQuery) ||
                        api.method.lowercase().contains(lowerQuery) ||
                        api.methodName.lowercase().contains(lowerQuery) ||
                        api.className.lowercase().contains(lowerQuery)
            }
        }

        if (filteredApis.isEmpty()) {
            // 如果没搜到，这里什么都不加，树就是空的（除了 Root）
        } else {
            // 分组逻辑
            val groupedApis = filteredApis.groupBy { it.className }
            groupedApis.forEach { (controllerName, apiList) ->
                val simpleName = controllerName.substringAfterLast('.')
                val controllerNode = DefaultMutableTreeNode(simpleName)
                apiList.forEach { api -> controllerNode.add(DefaultMutableTreeNode(api)) }
                root.add(controllerNode)
            }
        }

        treeModel.reload()

        // 如果正在搜索，自动展开所有节点以便查看结果
        if (lowerQuery.isNotEmpty()) {
            for (i in 0 until tree.rowCount) {
                tree.expandRow(i)
            }
        }
    }

    // ... (getData, findPsiMethod, navigateToSource, performExport 保持不变) ...
    // 为了节省篇幅，这里复用您之前的代码，逻辑不需要变

    override fun getData(dataId: String): Any? {
        if (PlatformCoreDataKeys.BGT_DATA_PROVIDER.`is`(dataId)) {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val userObject = node?.userObject as? ApiDefinition ?: return null
            return DataProvider { bgDataId ->
                if (CommonDataKeys.PSI_ELEMENT.`is`(bgDataId) || CommonDataKeys.NAVIGATABLE.`is`(bgDataId)) {
                    findPsiMethod(userObject)
                } else null
            }
        }
        return null
    }

    private fun findPsiMethod(api: ApiDefinition): PsiMethod? {
        return ReadAction.compute<PsiMethod?, Throwable> {
            val clazz = JavaPsiFacade.getInstance(project).findClass(api.className, GlobalSearchScope.projectScope(project))
                ?: return@compute null
            clazz.findMethodsByName(api.methodName, false).firstOrNull()
        }
    }

    private fun navigateToSource(api: ApiDefinition) {
        ReadAction.nonBlocking<PsiMethod?> {
            val clazz = JavaPsiFacade.getInstance(project).findClass(api.className, GlobalSearchScope.projectScope(project))
                ?: return@nonBlocking null
            clazz.findMethodsByName(api.methodName, false).firstOrNull()
        }
            .inSmartMode(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { method ->
                if (method != null && method.canNavigate()) method.navigate(true)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun performExport(defaultName: String, apis: List<ApiDefinition>) {
        val descriptor = FileSaverDescriptor("Export to Postman", "Save as JSON file", "json")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(null as VirtualFile?, "$defaultName.postman_collection.json")

        if (wrapper != null) {
            try {
                val exporter = PostmanExportService()
                exporter.exportLiveApis(defaultName, apis, wrapper.file)
                Messages.showInfoMessage("Successfully exported ${apis.size} APIs.", "Export Success")
            } catch (ex: Exception) {
                Messages.showErrorDialog("Export failed: ${ex.message}", "Error")
            }
        }
    }
}