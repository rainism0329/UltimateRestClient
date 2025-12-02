package com.phil.rest.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.phil.rest.model.ApiDefinition
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
                    // 左键单击：预览
                    if (e.button == MouseEvent.BUTTON1 && !e.isControlDown && e.clickCount == 1) {
                        onApiSelect(userObject)
                    }
                    // 双击 OR Ctrl+单击：跳转
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

        // 3. 右键菜单 & 工具栏
        val rightClickActionGroup = DefaultActionGroup()
        rightClickActionGroup.add(object : AnAction("Jump to Source", "Navigate to method declaration", AllIcons.Actions.EditSource) {
            override fun actionPerformed(e: AnActionEvent) {
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                val userObject = node?.userObject as? ApiDefinition ?: return
                navigateToSource(userObject)
            }
        })
        rightClickActionGroup.addSeparator()
        rightClickActionGroup.add(object : AnAction("Refresh", "Scan project for APIs", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) { refreshApiTree() }
        })

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
        toolbarActionGroup.add(object : AnAction("Refresh", "Scan project for APIs", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) { refreshApiTree() }
        })
        toolbarActionGroup.add(object : AnAction("Jump to Source", "Navigate to code", AllIcons.Actions.EditSource) {
            override fun actionPerformed(e: AnActionEvent) {
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                val userObject = node?.userObject as? ApiDefinition
                if (userObject != null) navigateToSource(userObject)
            }
        })

        val toolbar = ActionManager.getInstance().createActionToolbar("ApiTreeToolbar", toolbarActionGroup, true)
        toolbar.targetComponent = this

        setContent(ScrollPaneFactory.createScrollPane(tree))
        setToolbar(toolbar.component)

        DataManager.registerDataProvider(this, this)

        refreshApiTree()
    }

    // [修复 2] 重构 DataProvider 逻辑
    // 不要在 UI 线程直接返回 PSI_ELEMENT，而是返回一个 BGT_DATA_PROVIDER
    override fun getData(dataId: String): Any? {
        // 1. 如果 IDE 请求后台数据提供者 (BGT_DATA_PROVIDER)
        if (PlatformCoreDataKeys.BGT_DATA_PROVIDER.`is`(dataId)) {
            // 获取当前选中的对象 (这个操作很快，可以在 EDT 做)
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val userObject = node?.userObject as? ApiDefinition ?: return null

            // 返回一个新的 DataProvider，这个 Provider 会在后台线程执行
            return DataProvider { bgDataId ->
                if (CommonDataKeys.PSI_ELEMENT.`is`(bgDataId) || CommonDataKeys.NAVIGATABLE.`is`(bgDataId)) {
                    // 这里已经在后台线程了，可以放心做 PSI 搜索
                    findPsiMethod(userObject)
                } else {
                    null
                }
            }
        }
        return null
    }

    // --- 辅助方法：查找 PSI ---
    private fun findPsiMethod(api: ApiDefinition): PsiMethod? {
        // 注意：即使在后台线程，访问 PSI 也需要读锁 (ReadAction)
        // IDE 的 BGT 机制通常会自动处理，或者我们需要手动 runReadAction
        // 为了稳妥，这里使用 compute
        return ReadAction.compute<PsiMethod?, Throwable> {
            val clazz = JavaPsiFacade.getInstance(project).findClass(api.className, GlobalSearchScope.projectScope(project))
                ?: return@compute null
            clazz.findMethodsByName(api.methodName, false).firstOrNull()
        }
    }

    // --- 核心：执行跳转 ---
    private fun navigateToSource(api: ApiDefinition) {
        ReadAction.nonBlocking<PsiMethod?> {
            // 这里复用查找逻辑
            val clazz = JavaPsiFacade.getInstance(project).findClass(api.className, GlobalSearchScope.projectScope(project))
                ?: return@nonBlocking null
            clazz.findMethodsByName(api.methodName, false).firstOrNull()
        }
            .inSmartMode(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { method ->
                if (method != null && method.canNavigate()) {
                    method.navigate(true)
                }
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
                if (apis.isEmpty()) {
                    root.add(DefaultMutableTreeNode("No APIs found"))
                } else {
                    val groupedApis = apis.groupBy { it.className }
                    groupedApis.forEach { (controllerName, apiList) ->
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
                for (i in 0 until tree.rowCount) {
                    tree.expandRow(i)
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}