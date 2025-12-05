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
import com.phil.rest.service.ApiCacheService // [New]
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
    private val searchField = SearchTextField(true)

    // 数据源
    private var allApis: List<ApiDefinition> = emptyList()

    init {
        val rootNode = DefaultMutableTreeNode(project.name)
        treeModel = DefaultTreeModel(rootNode)

        tree = Tree(treeModel).apply {
            isRootVisible = true
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            cellRenderer = CollectionTreeCellRenderer() // 复用渲染器
        }

        // --- 搜索逻辑 ---
        searchField.textEditor.emptyText.text = "Search APIs..."
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { filterTree(searchField.text) }
        })

        val topPanel = JPanel(BorderLayout())
        topPanel.border = JBUI.Borders.empty(5)
        topPanel.add(searchField, BorderLayout.CENTER)

        // ... (MouseListener & KeyListener 保持不变，省略以节省空间) ...
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

        // ... (Actions 定义保持不变) ...
        val jumpAction = object : AnAction("Jump to Source", "Navigate to method declaration", AllIcons.Actions.EditSource) {
            override fun actionPerformed(e: AnActionEvent) {
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                val userObject = node?.userObject as? ApiDefinition ?: return
                navigateToSource(userObject)
            }
        }

        val refreshAction = object : AnAction("Refresh", "Scan project for APIs", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) { refreshApiTree(true) }
        }

        val exportAllAction = object : AnAction("Export All", "Export all scanned APIs to Postman", AllIcons.ToolbarDecorator.Export) {
            override fun actionPerformed(e: AnActionEvent) {
                if (allApis.isEmpty()) {
                    Messages.showWarningDialog("No APIs to export.", "Export Failed")
                    return
                }
                performExport("${project.name}_All_APIs", allApis)
            }
        }

        val exportSelectionAction = object : AnAction("Export This", "Export selection", AllIcons.ToolbarDecorator.Export) {
            override fun actionPerformed(e: AnActionEvent) {
                // ... (Logic same as before) ...
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                val apisToExport = ArrayList<ApiDefinition>()
                var exportName = "Exported_APIs"

                if (node.isRoot) {
                    exportAllAction.actionPerformed(e)
                    return
                } else if (node.userObject is String) {
                    exportName = node.userObject as String
                    collectApisFromNode(node, apisToExport) // Extract helper
                } else if (node.userObject is ApiDefinition) {
                    val api = node.userObject as ApiDefinition
                    apisToExport.add(api)
                    exportName = api.methodName
                }

                if (apisToExport.isNotEmpty()) performExport(exportName, apisToExport)
            }
        }

        // Action Groups (Same as before)
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

        val toolbarActionGroup = DefaultActionGroup()
        toolbarActionGroup.add(refreshAction)
        toolbarActionGroup.add(jumpAction)
        toolbarActionGroup.addSeparator()
        toolbarActionGroup.add(exportAllAction)

        val toolbar = ActionManager.getInstance().createActionToolbar("ApiTreeToolbar", toolbarActionGroup, true)
        toolbar.targetComponent = this

        val northPanel = JPanel(BorderLayout())
        northPanel.add(toolbar.component, BorderLayout.NORTH)
        northPanel.add(topPanel, BorderLayout.CENTER)

        setToolbar(northPanel)
        setContent(ScrollPaneFactory.createScrollPane(tree))
        DataManager.registerDataProvider(this, this)

        // [核心逻辑] 启动时先加载缓存，再后台扫描
        loadFromCache()
        refreshApiTree(false) // false = background sync
    }

    // --- 缓存加载逻辑 ---
    private fun loadFromCache() {
        val cachedApis = ApiCacheService.getInstance(project).cachedApis
        if (cachedApis.isNotEmpty()) {
            allApis = cachedApis
            filterTree(searchField.text)
        } else {
            val root = treeModel.root as DefaultMutableTreeNode
            root.add(DefaultMutableTreeNode("Scanning..."))
            treeModel.reload()
        }
    }

    // --- 扫描逻辑 ---
    private fun refreshApiTree(force: Boolean) {
        if (force) {
            val root = treeModel.root as DefaultMutableTreeNode
            root.removeAllChildren()
            root.add(DefaultMutableTreeNode("Scanning..."))
            treeModel.reload()
        }

        ReadAction.nonBlocking<List<ApiDefinition>> {
            val scanner = SpringScannerService(project)
            scanner.scanCurrentProject()
        }
            .inSmartMode(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { apis ->
                allApis = apis

                // 1. 更新 UI
                filterTree(searchField.text)

                // 2. 更新缓存 (静默)
                ApiCacheService.getInstance(project).updateCache(apis)

                // 3. 如果是静默更新，可以给个提示 (可选)
                // if (!force) showBalloon("APIs Synced", MessageType.INFO)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    // --- 树构建逻辑 (支持 Module 分组) ---
    private fun filterTree(query: String) {
        val root = treeModel.root as DefaultMutableTreeNode
        root.removeAllChildren()
        root.userObject = project.name // Ensure root name

        if (allApis.isEmpty()) {
            root.add(DefaultMutableTreeNode("No APIs found"))
            treeModel.reload()
            return
        }

        val lowerQuery = query.lowercase().trim()

        // 1. 过滤
        val filteredApis = if (lowerQuery.isEmpty()) {
            allApis
        } else {
            allApis.filter { api ->
                api.url.lowercase().contains(lowerQuery) ||
                        api.method.lowercase().contains(lowerQuery) ||
                        api.methodName.lowercase().contains(lowerQuery) ||
                        api.className.lowercase().contains(lowerQuery) ||
                        (api.moduleName != null && api.moduleName.lowercase().contains(lowerQuery))
            }
        }

        if (filteredApis.isEmpty()) {
            treeModel.reload()
            return
        }

        // 2. 分组逻辑 (Module -> Controller)

        // 检查是否有多个 Module (如果只有一个模块，就不显示模块层级，节省空间)
        val uniqueModules = filteredApis.map { it.moduleName }.distinct()
        val showModuleLevel = uniqueModules.size > 1

        if (showModuleLevel) {
            // 多模块模式: Root -> Module -> Controller -> API
            val apisByModule = filteredApis.groupBy { it.moduleName }
            apisByModule.forEach { (modName, modApis) ->
                val moduleNode = DefaultMutableTreeNode(modName) // 需要 Icon 区分的话可以改 Renderer

                val apisByController = modApis.groupBy { it.className }
                apisByController.forEach { (controllerName, apiList) ->
                    val simpleName = controllerName.substringAfterLast('.')
                    val controllerNode = DefaultMutableTreeNode(simpleName)
                    apiList.forEach { api -> controllerNode.add(DefaultMutableTreeNode(api)) }
                    moduleNode.add(controllerNode)
                }
                root.add(moduleNode)
            }
        } else {
            // 单模块模式: Root -> Controller -> API (保持原样)
            val apisByController = filteredApis.groupBy { it.className }
            apisByController.forEach { (controllerName, apiList) ->
                val simpleName = controllerName.substringAfterLast('.')
                val controllerNode = DefaultMutableTreeNode(simpleName)
                apiList.forEach { api -> controllerNode.add(DefaultMutableTreeNode(api)) }
                root.add(controllerNode)
            }
        }

        treeModel.reload()

        // 搜索时自动展开
        if (lowerQuery.isNotEmpty()) {
            for (i in 0 until tree.rowCount) tree.expandRow(i)
        }
    }

    // Helper to recursively collect APIs for export
    private fun collectApisFromNode(node: DefaultMutableTreeNode, result: ArrayList<ApiDefinition>) {
        if (node.userObject is ApiDefinition) {
            result.add(node.userObject as ApiDefinition)
        }
        for (i in 0 until node.childCount) {
            collectApisFromNode(node.getChildAt(i) as DefaultMutableTreeNode, result)
        }
    }

    // ... (getData, findPsiMethod, navigateToSource 保持不变) ...
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
                else {
                    // 如果找不到方法（代码改了），触发刷新
                    Messages.showInfoMessage("Method definition changed. Refreshing...", "Sync")
                    refreshApiTree(true)
                }
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