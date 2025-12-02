package com.phil.rest.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import com.phil.rest.model.ApiDefinition
import com.phil.rest.model.CollectionNode

// [修改] 显式实现 Disposable 接口，解决 Disposer.register 报错
class RestClientMainPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val requestEditor: RequestEditorPanel
    private val navPanel: NavigationPanel
    private val splitter: OnePixelSplitter

    init {
        // 1. 初始化组件
        requestEditor = RequestEditorPanel(project) { refreshCollectionsTree() }

        // [修改] 注册子组件的生命周期，确保 MainPanel 销毁时 RequestEditorPanel 也被销毁
        Disposer.register(this, requestEditor)

        navPanel = NavigationPanel(project, { selectedObject ->
            when (selectedObject) {
                is ApiDefinition -> requestEditor.renderApi(selectedObject)
                is CollectionNode -> requestEditor.renderSavedRequest(selectedObject)
            }
        }, { requestEditor.createNewEmptyRequest() })

        // 2. 初始化 Splitter
        splitter = OnePixelSplitter(false, 0.2f)
        splitter.firstComponent = navPanel
        splitter.secondComponent = requestEditor

        // 3. 创建顶部工具栏
        val toolbar = createToolbar()
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        setContent(splitter)
    }

    private fun createToolbar(): ActionToolbar {
        val actionGroup = DefaultActionGroup()

        val toggleLayoutAction = object : AnAction("Switch Layout", "Toggle horizontal/vertical split", AllIcons.Actions.SplitVertically) {
            override fun actionPerformed(e: AnActionEvent) {
                val isVertical = splitter.orientation
                splitter.orientation = !isVertical
                e.presentation.icon = if (isVertical) AllIcons.Actions.SplitVertically else AllIcons.Actions.SplitHorizontally
            }
        }

        actionGroup.add(toggleLayoutAction)
        return ActionManager.getInstance().createActionToolbar("RestClientMainToolbar", actionGroup, true)
    }

    private fun refreshCollectionsTree() {
        val tabbedPane = navPanel.components.find { it is javax.swing.JTabbedPane } as? javax.swing.JTabbedPane
        val collectionTab = tabbedPane?.getComponentAt(1) as? CollectionsTreePanel
        collectionTab?.reloadTree()
    }

    fun openApiFromCode(api: ApiDefinition) {
        requestEditor.renderApi(api)
    }

    // [修改] 实现 dispose 方法 (虽然这里没逻辑，但必须有这个方法才能当 Disposable 用)
    override fun dispose() {
        // 子组件会自动被 Disposer 释放，这里不需要手动调用
    }
}