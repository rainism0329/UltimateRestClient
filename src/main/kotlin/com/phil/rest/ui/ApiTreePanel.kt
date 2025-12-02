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
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.phil.rest.model.ApiDefinition
import com.phil.rest.service.PostmanExportService
import com.phil.rest.service.SpringScannerService
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

class ApiTreePanel(
    private val project: Project,
    private val onApiSelect: (ApiDefinition) -> Unit
) : SimpleToolWindowPanel(true, true), DataProvider {

    private val treeModel: DefaultTreeModel
    private val tree: Tree

    init {
        val rootNode = DefaultMutableTreeNode(project.name)
        treeModel = DefaultTreeModel(rootNode)
        tree = Tree(treeModel).apply {
            isRootVisible = true
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            cellRenderer = CollectionTreeCellRenderer()
        }

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

        // --- Actions 定义 ---

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

        // [Unified] 1. Export All (用于工具栏)
        val exportAllAction = object : AnAction("Export All", "Export all scanned APIs to Postman", AllIcons.Actions.MenuSaveall) {
            override fun actionPerformed(e: AnActionEvent) {
                val root = treeModel.root as DefaultMutableTreeNode
                if (root.childCount == 0) {
                    Messages.showWarningDialog("No APIs to export. Please refresh first.", "Export Failed")
                    return
                }

                val allApis = ArrayList<ApiDefinition>()
                // 遍历 Root 下的所有 Controller
                for (i in 0 until root.childCount) {
                    val controllerNode = root.getChildAt(i) as DefaultMutableTreeNode
                    // 遍历 Controller 下的所有 API
                    for (j in 0 until controllerNode.childCount) {
                        val apiNode = controllerNode.getChildAt(j) as DefaultMutableTreeNode
                        val api = apiNode.userObject as? ApiDefinition
                        if (api != null) allApis.add(api)
                    }
                }

                if (allApis.isEmpty()) {
                    Messages.showWarningDialog("No APIs found.", "Export Failed")
                    return
                }

                performExport("${project.name}_All_APIs", allApis)
            }
        }

        // [Unified] 2. Export Selection (用于右键菜单)
        val exportSelectionAction = object : AnAction("Export This", "Export selected item to Postman", AllIcons.ToolbarDecorator.Export) {
            override fun actionPerformed(e: AnActionEvent) {
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                val apisToExport = ArrayList<ApiDefinition>()
                var exportName = "Exported_APIs"

                if (node.isRoot) {
                    // 如果右键点了 Root，行为等同于 Export All
                    exportAllAction.actionPerformed(e)
                    return
                } else if (node.userObject is String) {
                    // 选中了 Controller
                    exportName = node.userObject as String
                    for (i in 0 until node.childCount) {
                        val child = node.getChildAt(i) as DefaultMutableTreeNode
                        val api = child.userObject as? ApiDefinition
                        if (api != null) apisToExport.add(api)
                    }
                } else if (node.userObject is ApiDefinition) {
                    // 选中了单个 API
                    val api = node.userObject as ApiDefinition
                    apisToExport.add(api)
                    exportName = api.methodName
                }

                if (apisToExport.isNotEmpty()) {
                    performExport(exportName, apisToExport)
                }
            }
        }

        // 3. 右键菜单配置 (只放 Export Selection)
        val rightClickActionGroup = DefaultActionGroup()
        rightClickActionGroup.add(jumpAction)
        rightClickActionGroup.addSeparator()
        rightClickActionGroup.add(exportSelectionAction) // Export Selection
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

        // 4. 工具栏配置 (只放 Export All)
        val toolbarActionGroup = DefaultActionGroup()
        toolbarActionGroup.add(refreshAction)
        toolbarActionGroup.add(jumpAction)
        toolbarActionGroup.addSeparator()
        toolbarActionGroup.add(exportAllAction) // Export All

        val toolbar = ActionManager.getInstance().createActionToolbar("ApiTreeToolbar", toolbarActionGroup, true)
        toolbar.targetComponent = this

        setContent(ScrollPaneFactory.createScrollPane(tree))
        setToolbar(toolbar.component)

        DataManager.registerDataProvider(this, this)
        refreshApiTree()
    }

    // --- 统一的导出逻辑 ---
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

    // ... (getData, findPsiMethod, navigateToSource, refreshApiTree 保持不变) ...
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

    private fun refreshApiTree() {
        val root = treeModel.root as DefaultMutableTreeNode
        ReadAction.nonBlocking<List<ApiDefinition>> {
            val scanner = SpringScannerService(project)
            scanner.scanCurrentProject()
        }
            .inSmartMode(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { apis ->
                root.removeAllChildren()
                if (apis.isEmpty()) root.add(DefaultMutableTreeNode("No APIs found"))
                else {
                    val groupedApis = apis.groupBy { it.className }
                    groupedApis.forEach { (controllerName, apiList) ->
                        val simpleName = controllerName.substringAfterLast('.')
                        val controllerNode = DefaultMutableTreeNode(simpleName)
                        apiList.forEach { api -> controllerNode.add(DefaultMutableTreeNode(api)) }
                        root.add(controllerNode)
                    }
                }
                treeModel.reload()
                for (i in 0 until tree.rowCount) tree.expandRow(i)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}